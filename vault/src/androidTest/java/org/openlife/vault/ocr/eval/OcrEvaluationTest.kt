package org.openlife.vault.ocr.eval

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.eval.ocr.core.Box
import org.openlife.eval.ocr.core.Digests
import org.openlife.eval.ocr.core.EngineResults
import org.openlife.eval.ocr.core.FixtureTruth
import org.openlife.eval.ocr.core.GateBinding
import org.openlife.eval.ocr.core.GateCodec
import org.openlife.eval.ocr.core.GateOutcome
import org.openlife.eval.ocr.core.GateThresholds
import org.openlife.eval.ocr.core.ImageResult
import org.openlife.eval.ocr.core.ManifestCodec
import org.openlife.eval.ocr.core.OcrGate
import org.openlife.eval.ocr.core.OcrMetrics
import org.openlife.eval.ocr.core.ResultsCodec
import org.openlife.eval.ocr.core.RunOutcome
import org.openlife.eval.ocr.core.ScriptPolicyCost
import org.openlife.eval.ocr.core.SpanObservation
import org.openlife.eval.ocr.core.TruthCodec
import org.openlife.eval.ocr.core.Utf8Chunks
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.Orientation
import org.openlife.vault.ocr.MlKitOcrEngine
import org.openlife.vault.ocr.NativeTessRecognizer
import org.openlife.vault.ocr.OcrEngine
import org.openlife.vault.ocr.OcrEngineInput
import org.openlife.vault.ocr.OcrEngineOutput
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrRunStateMachine
import org.openlife.vault.ocr.OcrScriptPolicy
import org.openlife.vault.ocr.OcrScriptStatus
import org.openlife.vault.ocr.OcrUnsupportedScriptException
import org.openlife.vault.ocr.RecognizedLine
import org.openlife.vault.ocr.TessRecognizer
import org.openlife.vault.ocr.TessdataInstaller
import org.openlife.vault.ocr.TesseractLineMapper
import org.openlife.vault.ocr.TesseractOcrEngine
import org.openlife.vault.testing.OptInHarness
import org.openlife.vault.testing.OptInHarnessRunner
import org.openlife.vault.testing.Slow
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * P2-04-R2 harness: runs each registered engine over eval/ocr/v1 (packaged
 * into this test APK only, under assets/v1) and writes
 * results-<engine>-<date>.json. Scoring uses eval's shared core, the same
 * code the host gate tests prove. The gate itself is evaluated on the host
 * (`./gradlew :eval:checkOcrGate`); here a results file only has to be
 * complete and bound to the committed set and gate.json.
 *
 * Per image: the production `extract` call is timed and PSS-sampled; its
 * outcome is what production would do (OUTPUT, ABSTAINED on an unsupported
 * script, FAILED otherwise). When production abstains, its spans are gone,
 * so the raw spans for the per-span policy (R5) come from a tee on the
 * Tesseract recognizer, or from a second, untimed ML Kit call.
 *
 * Each `extract` has [PER_IMAGE_TIMEOUT_MS]; a call that has not returned
 * is recorded as TIMED_OUT, which fails the gate.
 *
 * Results land in the `additionalTestOutputDir` instrumentation argument,
 * which AGP copies into vault/build/outputs/connected_android_test_additional_output/
 * after a Gradle run; when that argument is absent, in the test package's
 * external files dir under `ocr-eval/`, readable with `adb pull` without
 * root. Logcat (tag P204Eval) carries a second copy.
 *
 * Opt-in: `-Pandroid.testInstrumentationRunnerArguments.p204Eval=true`, with
 * `p204Commit=<full git commit>`. Without the opt-in the class reports
 * ignored (a JUnit skip), never a pass.
 */
@Slow
@RunWith(OptInHarnessRunner::class)
@OptInHarness("p204Eval")
class OcrEvaluationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets
    private val dataDir = File(context.noBackupFilesDir, "eval-tessdata-${UUID.randomUUID()}")
    private val abandonable = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        abandonable.cancel()
        dataDir.deleteRecursively()
    }

    @Test
    fun evaluateRegisteredEnginesOverTheFixtureSet(): Unit = runBlocking {
        val manifestText = asset("manifest.json").decodeToString()
        val gateText = asset("gate.json").decodeToString()
        val manifest = ManifestCodec.decode(manifestText)
        val gate = GateCodec.decode(gateText)
        val fixtures = manifest.fixtures.map { entry ->
            val png = asset(entry.image)
            val truthText = asset(entry.truth).decodeToString()
            assertEquals("${entry.id} PNG in the test APK", entry.pngSha256, Digests.sha256(png))
            assertEquals("${entry.id} truth in the test APK", entry.truthSha256, Digests.sha256(truthText))
            TruthCodec.decode(truthText) to png
        }
        val binding = GateBinding(
            manifestSha256 = Digests.sha256(manifestText),
            gateSha256 = Digests.sha256(gateText),
            fixtures = manifest.fixtures,
            truths = fixtures.associate { (truth, _) -> truth.id to truth },
        )
        val commit = InstrumentationRegistry.getArguments().getString(COMMIT_ARGUMENT).orEmpty()
        assertTrue(
            "pass -Pandroid.testInstrumentationRunnerArguments.$COMMIT_ARGUMENT=\$(git rev-parse HEAD)",
            commit.matches(FULL_COMMIT),
        )

        val tee = TeeFactory()
        val tesseract = TesseractOcrEngine(
            TessdataInstaller(dataDir, { context.assets.open(TessdataInstaller.ASSET_PATH) }),
            recognizerFactory = tee::create,
        )
        val engines = listOf(
            tesseract to { truth: FixtureTruth, _: ByteArray -> tee.lastSpans(truth) },
            MlKitOcrEngine() to { truth: FixtureTruth, png: ByteArray -> mlKitRawSpans(truth, png) },
        )
        // Review X-05: the engines that run are the ones gate.json pins, at the pinned model versions.
        assertEquals(
            gate.engines.map { it.id to it.modelVersion },
            engines.map { it.first.id to it.first.modelVersion },
        )

        for ((engine, rawSpans) in engines) {
            val (warmTruth, warmPng) = fixtures.first()
            // Warm-up, not measured: installs and digests tessdata once, loads ML Kit's pipeline, warms the JIT.
            // Tesseract still opens its model in every extract, as production does, so each measured duration
            // includes that model load.
            runCatching { extractWithin(engine, input(warmTruth, warmPng)) }
            val images = fixtures.map { (truth, png) -> measure(engine, truth, png, gate, rawSpans) }
            val results = EngineResults(
                engineId = engine.id,
                modelVersion = engine.modelVersion,
                codeCommit = commit,
                manifestSha256 = binding.manifestSha256,
                gateSha256 = binding.gateSha256,
                runDate = LocalDate.now().toString(),
                device = mapOf(
                    "manufacturer" to Build.MANUFACTURER,
                    "model" to Build.MODEL,
                    "sdk" to Build.VERSION.SDK_INT.toString(),
                    "abi" to Build.SUPPORTED_ABIS.first(),
                ),
                images = images,
            )
            val encoded = ResultsCodec.encode(results)
            val file = write("results-${engine.id}-${results.runDate}.json", encoded)
            val verdict = OcrGate.evaluate(engine.id, gate, binding, encoded)
            Log.i(TAG, "engine=${engine.id} results=${file.absolutePath} gate=${verdict.outcome}")
            verdict.checks.forEach {
                Log.i(TAG, "engine=${engine.id} check=${it.name} ${it.status} observed=${it.observed}")
            }
            // A secondary copy for when the results file cannot be pulled. Only synthetic fixture text (the
            // committed eval/ocr/v1 set and the engines' readings of it) may ever be logged here.
            Utf8Chunks.split(encoded, LOG_CHUNK_BYTES).forEachIndexed { i, chunk ->
                Log.i(TAG, "json ${engine.id} $i $chunk")
            }
            assertNotEquals(verdict.problems.toString(), GateOutcome.INVALID, verdict.outcome)
        }
    }

    private suspend fun measure(
        engine: OcrEngine,
        truth: FixtureTruth,
        png: ByteArray,
        gate: GateThresholds,
        rawSpansOf: (FixtureTruth, ByteArray) -> List<SpanObservation>,
    ): ImageResult {
        Runtime.getRuntime().gc()
        val baseline = Debug.getPss()
        val peak = AtomicLong(baseline)
        val sampling = AtomicBoolean(true)
        val sampler = thread {
            while (sampling.get()) {
                peak.accumulateAndGet(Debug.getPss(), ::maxOf)
                // P1-20-R8: the PSS sampling interval of the measurement, not a wait.
                Thread.sleep(SAMPLE_MS)
            }
        }
        val start = System.nanoTime()
        var spans = emptyList<SpanObservation>()
        val outcome = try {
            val drafts = extractWithin(engine, input(truth, png))?.spans
            if (drafts == null) {
                Log.w(TAG, "engine=${engine.id} fixture=${truth.id} timed out after $PER_IMAGE_TIMEOUT_MS ms")
                RunOutcome.TIMED_OUT
            } else {
                val decision = OcrRunStateMachine.decide(OcrRunStateMachine.EngineOutcome.Output(drafts))
                if (decision is OcrRunStateMachine.Decision.Persist) {
                    spans = decision.spans.map { SpanObservation(it.text, it.region?.toBox()) }
                }
                outcomeOf(decision)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OcrUnsupportedScriptException) {
            RunOutcome.ABSTAINED
        } catch (failure: Exception) {
            Log.w(TAG, "engine=${engine.id} fixture=${truth.id} failed: ${failure.javaClass.simpleName}")
            RunOutcome.FAILED
        }
        val durationMs = (System.nanoTime() - start) / NANOS_PER_MS
        sampling.set(false)
        sampler.join()

        val raw = when (outcome) {
            RunOutcome.OUTPUT -> spans
            RunOutcome.ABSTAINED -> rawSpansOf(truth, png)
            RunOutcome.FAILED, RunOutcome.TIMED_OUT -> null
        }
        val score = OcrMetrics.score(truth, spans, gate.lineMatchIou)
        val policy = raw?.let {
            ScriptPolicyCost.compare(truth.lines, it, outcome == RunOutcome.ABSTAINED, gate.lineMatchIou) { text ->
                OcrScriptPolicy.classify(text) == OcrScriptStatus.UNSUPPORTED
            }
        }
        return ImageResult(
            id = truth.id,
            category = truth.category,
            expectedAbstain = truth.expectedAbstain,
            outcome = outcome,
            cer = score.cer,
            linesTotal = truth.lines.size,
            linesMatched = score.linesMatched,
            durationMs = durationMs,
            baselinePssKib = baseline,
            peakPssKib = peak.get(),
            policy = policy,
            spans = spans,
            rawSpans = if (outcome == RunOutcome.ABSTAINED) raw.orEmpty() else emptyList(),
        )
    }

    private fun outcomeOf(decision: OcrRunStateMachine.Decision): RunOutcome = when (decision) {
        is OcrRunStateMachine.Decision.Persist -> RunOutcome.OUTPUT
        is OcrRunStateMachine.Decision.Terminal ->
            if (decision.reason == OcrFailureReason.UNSUPPORTED_SCRIPT) RunOutcome.ABSTAINED else RunOutcome.FAILED
    }

    /**
     * The production `extract`, or null when it has not returned within [PER_IMAGE_TIMEOUT_MS], so a hung
     * engine yields a failing result instead of a hung run. The abandoned call is cancelled and left on its
     * own scope; a native call that ignores cancellation may keep running and disturb later measurements.
     */
    private suspend fun extractWithin(engine: OcrEngine, input: OcrEngineInput): OcrEngineOutput? {
        val call = abandonable.async { engine.extract(input) }
        return withTimeoutOrNull(PER_IMAGE_TIMEOUT_MS) { call.await() } ?: run {
            call.cancel()
            null
        }
    }

    private fun input(truth: FixtureTruth, png: ByteArray) =
        OcrEngineInput(png.copyOf(), truth.width, truth.height, Orientation.NORMAL, ImageFormat.PNG)

    /** ML Kit's own spans for an image production abstained on; fixtures are upright and under the decode limit. */
    private fun mlKitRawSpans(truth: FixtureTruth, png: ByteArray): List<SpanObservation> {
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            return text.textBlocks.flatMap { block ->
                block.lines.map { line ->
                    val box = line.boundingBox?.let {
                        val left = it.left.coerceIn(0, truth.width)
                        val top = it.top.coerceIn(0, truth.height)
                        Box(left, top, it.right.coerceIn(left, truth.width), it.bottom.coerceIn(top, truth.height))
                    }
                    SpanObservation(line.text, box)
                }
            }
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    private fun asset(path: String): ByteArray = assets.open("v1/$path").use { it.readBytes() }

    /** AGP copies files written to additionalTestOutputDir into build/outputs; otherwise external files. */
    private fun write(name: String, text: String): File {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(requireNotNull(context.getExternalFilesDir(null)), "ocr-eval")
        dir.mkdirs()
        return File(dir, name).apply { writeText(text) }
    }

    /** Wraps each native recognizer so the raw lines survive an abstaining `extract`. */
    private class TeeFactory {
        @Volatile private var last: Tee? = null

        fun create(dataPath: File): TessRecognizer = Tee(NativeTessRecognizer(dataPath)).also { last = it }

        fun lastSpans(truth: FixtureTruth): List<SpanObservation> {
            val tee = last ?: return emptyList()
            return TesseractLineMapper.toSpans(
                tee.lines,
                tee.width,
                tee.height,
                truth.width,
                truth.height,
                Orientation.NORMAL,
            )
                .map { SpanObservation(it.text, it.region?.toBox()) }
        }
    }

    private class Tee(private val delegate: TessRecognizer) : TessRecognizer {
        @Volatile var lines: List<RecognizedLine> = emptyList()

        @Volatile var width = 1

        @Volatile var height = 1

        override fun recognize(bitmap: Bitmap, onRecognizing: () -> Unit): List<RecognizedLine> {
            width = bitmap.width
            height = bitmap.height
            return delegate.recognize(bitmap, onRecognizing).also { lines = it }
        }

        override fun stop() = delegate.stop()

        override fun release() = delegate.release()
    }

    private companion object {
        const val TAG = "P204Eval"
        const val SAMPLE_MS = 50L
        const val NANOS_PER_MS = 1_000_000L
        const val LOG_CHUNK_BYTES = 3_000
        const val PER_IMAGE_TIMEOUT_MS = 60_000L
        const val COMMIT_ARGUMENT = "p204Commit"
        val FULL_COMMIT = Regex("[0-9a-f]{40}")


        fun OcrEvidenceRegion.toBox() = Box(left, top, right, bottom)
    }
}
