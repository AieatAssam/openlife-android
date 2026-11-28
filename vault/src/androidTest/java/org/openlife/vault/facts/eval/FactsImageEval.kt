package org.openlife.vault.facts.eval

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.eval.facts.core.EmittedValue
import org.openlife.eval.facts.core.FactValueScorer
import org.openlife.eval.facts.core.ScoredValue
import org.openlife.eval.facts.core.TruthStatus
import org.openlife.eval.facts.core.TruthUnit
import org.openlife.eval.facts.core.ValueOutcome
import org.openlife.eval.ocr.core.Digests
import org.openlife.eval.ocr.core.Utf8Chunks
import org.openlife.vault.facts.extract.ExtractedCandidate
import org.openlife.vault.facts.extract.ExtractedKind
import org.openlife.vault.facts.extract.ExtractionSpan
import org.openlife.vault.facts.extract.FactExtractor
import org.openlife.vault.facts.extract.MoneyValue
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.Orientation
import org.openlife.vault.ocr.MlKitOcrEngine
import org.openlife.vault.ocr.OcrEngine
import org.openlife.vault.ocr.OcrEngineInput
import org.openlife.vault.ocr.OcrRunStateMachine
import org.openlife.vault.ocr.TessdataInstaller
import org.openlife.vault.ocr.TesseractOcrEngine
import org.openlife.vault.testing.OptInHarness
import org.openlife.vault.testing.OptInHarnessRunner
import org.openlife.vault.testing.Slow
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * P3-06-R1: the facts image part. Each of the 30 synthetic images (eval/facts/v1/images, rendered on the host by
 * :eval:generateFactsImages) goes through the production OCR engine, then the production extractor
 * ([FactExtractor.extract]) over the OCR spans, then is scored against the truth written from ADR-0008.
 *
 * Opt-in: `-Pandroid.testInstrumentationRunnerArguments.p306FactsImage=true`. Pairing and outcomes come from
 * the pure scorer in eval/src/core (FactValueScorer, JVM-tested in :eval:test): correctness is kind and canonical
 * value only; raw-text differences are counted separately. The currency predicate is unchanged. Results go to the
 * additional output directory and to logcat tag P306Facts.
 *
 * The run fails only when the set is invalid or an image cannot be processed; a gate miss is recorded as
 * measured, never tuned away.
 */
@Slow
@RunWith(OptInHarnessRunner::class)
@OptInHarness("p306FactsImage")
class FactsImageEval {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets
    private val dataDir = File(context.noBackupFilesDir, "facts-image-tessdata-${UUID.randomUUID()}")
    private val abandonable = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        abandonable.cancel()
        dataDir.deleteRecursively()
    }

    @Test
    fun measureBothEnginesOverTheFactsImages(): Unit = runBlocking {
        val gateText = asset("gate.json")
        val gate = parseThresholds(gateText)
        val cases = loadCases()
        assertEquals("30 image cases", 30, cases.size)

        val engines: List<OcrEngine> = listOf(
            TesseractOcrEngine(TessdataInstaller(dataDir, { context.assets.open(TessdataInstaller.ASSET_PATH) })),
            MlKitOcrEngine(),
        )
        for (engine in engines) {
            // Warm-up, not measured: installs tessdata once and warms each engine.
            runCatching { recognise(engine, cases.first()) }
            val runs = cases.map { measure(engine, it) }
            val summary = aggregate(runs)
            val verdict = verdictOf(summary, gate)
            val text = report(engine, runs, summary, verdict)
            val file = write("facts-image-${engine.id}-${LocalDate.now()}.txt", text)
            Log.i(TAG, "engine=${engine.id} results=${file.absolutePath} gate=${verdict.outcome}")
            Utf8Chunks.split(text, LOG_CHUNK).forEachIndexed { i, chunk -> Log.i(TAG, "report ${engine.id} $i $chunk") }
            assertEquals("every image ran", cases.size, runs.size)
        }
    }

    // ----- set loading (digests checked against the manifest) -----

    private class Case(
        val id: String,
        val png: ByteArray,
        val width: Int,
        val height: Int,
        val lines: List<String>,
        val items: List<Item>,
        val abstain: List<Pair<ExtractedKind?, String>>,
        val truth: List<String>,
    )

    private class Item(val kind: ExtractedKind, val raw: String, val canonical: String)

    private fun loadCases(): List<Case> {
        val manifestText = asset("manifest.tsv")
        val rows = manifestText.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals("gate.json digest", Digests.sha256(asset("gate.json")), rows.single { it.startsWith("gate.json\t") }.split('\t')[1])
        return rows.filter { it.startsWith("img-") }.map { line ->
            val cells = line.split('\t')
            val id = cells[0]
            val png = assetBytes("$id.png")
            val json = assetBytes("$id.json")
            assertEquals("$id PNG in the test APK", cells[1], Digests.sha256(png))
            assertEquals("$id truth in the test APK", cells[2], Digests.sha256(json))
            parseCase(id, png, json)
        }
    }

    private fun parseCase(id: String, png: ByteArray, json: ByteArray): Case {
        val obj = JSONObject(json.decodeToString())
        val lines = obj.getJSONArray("lines").let { a -> List(a.length()) { a.getString(it) } }
        val items = obj.getJSONArray("items").let { a ->
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Item(ExtractedKind.valueOf(o.getString("kind")), o.getString("raw"), o.getString("canonical"))
            }
        }
        val abstain = obj.getJSONArray("abstain").let { a ->
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                val kind = if (o.isNull("kind")) null else ExtractedKind.valueOf(o.getString("kind"))
                kind to o.getString("raw")
            }
        }
        val truth = obj.getJSONArray("truth").let { a -> List(a.length()) { a.getJSONObject(it).getString("canonical") } }
        return Case(id, png, obj.getInt("width"), obj.getInt("height"), lines, items, abstain, truth)
    }

    // ----- one image through one engine -----

    private class Run(
        val id: String,
        val ocrState: String,
        val ocrText: String,
        val emitted: List<Emitted>,
        val units: List<UnitRun>,
        val unexpectedAbstain: Int,
        val dmFlagged: List<Boolean>,
    )

    /** [rawDiffers]: the raw text is not the truth raw text (informational; correctness is kind and value only). */
    private class Emitted(
        val family: String,
        val kind: ExtractedKind,
        val rule: String,
        val outcome: ValueOutcome,
        val rawDiffers: Boolean,
        val definite: Boolean,
        val bare: Boolean,
        val raw: String,
        val canonical: String,
    )

    /**
     * [cause] classifies a miss by the exact surface form: the raw text is absent from the OCR text (OCR_MISREAD)
     * or present (an extractor cause). [causeWs] is the same test with whitespace removed from both sides, so a
     * dropped or inserted space is not an OCR misread. Both are recorded because the first run classified with
     * [causeWs] only and hid OCR spacing errors; the gate does not depend on either.
     */
    private class UnitRun(
        val family: String,
        val kind: ExtractedKind,
        val raw: String,
        val status: UnitStatus,
        val cause: String,
        val causeWs: String,
    )

    private enum class UnitStatus { EXACT, WRONG, ABSTAINED, SILENT }

    private class ExpectedUnit(val kind: ExtractedKind, val raw: String, val canonicals: Set<String>, val clarification: Boolean)

    private suspend fun measure(engine: OcrEngine, case: Case): Run {
        val decision = recognise(engine, case)
        val spans = (decision as? OcrRunStateMachine.Decision.Persist)?.spans
        val ocrState = when (decision) {
            is OcrRunStateMachine.Decision.Persist -> "PERSISTED"
            is OcrRunStateMachine.Decision.Terminal -> "TERMINAL-${decision.state.name}-${decision.reason.name}"
        }
        val ocrText = spans?.joinToString("\n") { it.text }.orEmpty()
        val units = expectedUnits(case.items)
        if (spans == null) {
            // No OCR output: every expected unit is a miss with cause OCR (there is no text to extract from).
            val unitRuns = units.map {
                UnitRun(familyOf(it.kind), it.kind, it.raw, UnitStatus.SILENT, "OCR_NO_OUTPUT", "OCR_NO_OUTPUT")
            }
            return Run(case.id, ocrState, ocrText, emptyList(), unitRuns, 0, dmFlags(case, emptyList()))
        }
        val extractorSpans = spans.mapIndexed { i, s -> ExtractionSpan("s$i", null, i, s.text) }
        val result = FactExtractor.extract(extractorSpans)
        val abstained = result.abstentions.map { a ->
            val text = extractorSpans.first { it.spanId == a.spanId }.text
            Triple(a.kind, text.substring(a.startOffset, a.endOffset), a.spanId)
        }
        val scored = FactValueScorer.score(
            units.map { TruthUnit(it.kind.name, it.raw, it.canonicals, it.clarification) },
            result.candidates.map { EmittedValue(it.kind.name, it.rawText, readingsOf(it), it.needsClarification) },
        )
        val emitted = result.candidates.mapIndexed { i, cand -> emit(cand, scored.values[i]) }
        val unitRuns = units.mapIndexed { i, unit ->
            val status = when (scored.units[i]) {
                TruthStatus.EXACT -> UnitStatus.EXACT
                TruthStatus.WRONG -> UnitStatus.WRONG
                TruthStatus.OPEN -> if (abstained.any { (k, raw, _) -> k == unit.kind && raw == unit.raw }) {
                    UnitStatus.ABSTAINED
                } else {
                    UnitStatus.SILENT
                }
            }
            val inOcrExact = unit.raw in ocrText
            val inOcrWs = normalised(unit.raw) in normalised(ocrText)
            val cause = causeOf(status, inOcrExact)
            val causeWs = causeOf(status, inOcrWs)
            UnitRun(familyOf(unit.kind), unit.kind, unit.raw, status, cause, causeWs)
        }
        val expectedAbstain = case.abstain
        val unexpected = abstained.count { (k, raw, _) -> expectedAbstain.none { it.first == k && it.second == raw } }
        return Run(case.id, ocrState, ocrText, emitted, unitRuns, unexpected, dmFlags(case, result.candidates))
    }

    private fun emit(cand: ExtractedCandidate, value: ScoredValue): Emitted = Emitted(
        family = familyOf(cand.kind),
        kind = cand.kind,
        rule = "${cand.ruleId}-${cand.ruleVersion}",
        outcome = value.outcome,
        rawDiffers = value.rawDiffers,
        definite = cand.canonicalValue != null,
        bare = bareCurrency(cand),
        raw = cand.rawText,
        canonical = cand.canonicalText,
    )

    private fun expectedUnits(items: List<Item>): List<ExpectedUnit> =
        items.groupBy { it.kind to it.raw }.map { (key, group) ->
            ExpectedUnit(key.first, key.second, group.map { it.canonical }.toSet(), group.size > 1)
        }

    /** One flag per truth entry: a clarification DATE candidate whose readings include the true reading. */
    private fun dmFlags(case: Case, candidates: List<ExtractedCandidate>): List<Boolean> =
        case.truth.map { truth ->
            candidates.any { it.needsClarification && it.kind == ExtractedKind.DATE && truth in readingsOf(it) }
        }

    private fun readingsOf(cand: ExtractedCandidate): Set<String> =
        cand.canonicalValue?.let { setOf(it.canonicalText()) } ?: cand.ambiguity.map { it.canonicalText() }.toSet()

    /** A MONEY value that took a currency from a multi-code symbol with no code and no context (ADR-0008 5.4). */
    private fun bareCurrency(cand: ExtractedCandidate): Boolean {
        val money = cand.canonicalValue as? MoneyValue ?: return false
        val coded = ISO_CODE.containsMatchIn(cand.rawText) || cand.currencyContext.any { it.codeStart != null }
        return money.currency != null && (money.rawSymbol ?: "") in MULTI_CODE_SYMBOLS && !coded
    }

    private fun familyOf(kind: ExtractedKind): String = when (kind) {
        ExtractedKind.DATE, ExtractedKind.TIME, ExtractedKind.DATETIME -> DATE_FAMILY
        else -> kind.name
    }

    private fun normalised(text: String): String = text.filterNot { it.isWhitespace() }

    private fun causeOf(status: UnitStatus, rawInOcr: Boolean): String = when (status) {
        UnitStatus.EXACT -> "-"
        UnitStatus.WRONG -> "EXTRACTOR_WRONG_VALUE"
        UnitStatus.ABSTAINED -> if (rawInOcr) "EXTRACTOR_ABSTAINED" else "OCR_MISREAD"
        UnitStatus.SILENT -> if (rawInOcr) "EXTRACTOR_MISS" else "OCR_MISREAD"
    }

    private suspend fun recognise(engine: OcrEngine, case: Case): OcrRunStateMachine.Decision {
        val input = OcrEngineInput(case.png.copyOf(), case.width, case.height, Orientation.NORMAL, ImageFormat.PNG)
        val outcome = try {
            val call = abandonable.async { engine.extract(input) }
            val output = withTimeoutOrNull(PER_IMAGE_TIMEOUT_MS) { call.await() }
            if (output == null) {
                call.cancel()
                OcrRunStateMachine.EngineOutcome.TimedOut
            } else {
                OcrRunStateMachine.EngineOutcome.Output(output.spans)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "engine=${engine.id} image=${case.id} failed: ${e.javaClass.simpleName}")
            OcrRunStateMachine.EngineOutcome.Failed(OcrRunStateMachine.reasonFor(e))
        }
        return OcrRunStateMachine.decide(outcome)
    }

    // ----- aggregation and the gate -----

    private class Rate(val hits: Int, val total: Int) {
        val value: Double? get() = if (total == 0) null else hits.toDouble() / total
    }

    private class Summary(
        val precision: Map<String, Rate>,
        val recall: Map<String, Rate>,
        val dmFlagged: Rate,
        val bareCurrency: Int,
        val wrongDefinite: Map<String, Int>,
        val wrongDefiniteTotal: Int,
        val unexpectedEmitted: Map<String, Int>,
        val misses: Map<String, Int>,
        val missesWs: Map<String, Int>,
        val unexpectedAbstentions: Int,
        val rawDiffers: Int,
    )

    private fun aggregate(runs: List<Run>): Summary {
        val emitted = runs.flatMap { it.emitted }
        val units = runs.flatMap { it.units }
        val dm = runs.flatMap { it.dmFlagged }
        return Summary(
            precision = GATED.associateWith { family ->
                Rate(emitted.count { it.family == family && it.outcome == ValueOutcome.EXACT }, emitted.count { it.family == family })
            },
            recall = GATED.associateWith { family ->
                Rate(units.count { it.family == family && it.status == UnitStatus.EXACT }, units.count { it.family == family })
            },
            dmFlagged = Rate(dm.count { it }, dm.size),
            bareCurrency = emitted.count { it.bare },
            wrongDefinite = emitted.filter { it.definite && it.outcome != ValueOutcome.EXACT }.groupingBy { it.family }.eachCount(),
            wrongDefiniteTotal = emitted.count { it.definite && it.outcome != ValueOutcome.EXACT },
            unexpectedEmitted = emitted.filter { it.outcome == ValueOutcome.UNEXPECTED }.groupingBy { it.family }.eachCount(),
            misses = units.filter { it.status != UnitStatus.EXACT }.groupingBy { it.cause }.eachCount(),
            missesWs = units.filter { it.status != UnitStatus.EXACT }.groupingBy { it.causeWs }.eachCount(),
            unexpectedAbstentions = runs.sumOf { it.unexpectedAbstain },
            rawDiffers = emitted.count { it.rawDiffers },
        )
    }

    private class Verdict(val outcome: String, val checks: List<String>)

    private fun verdictOf(s: Summary, t: Map<String, Double>): Verdict {
        val checks = mutableListOf<String>()
        fun atLeast(name: String, rate: Rate, min: Double) {
            val v = rate.value
            val ok = v != null && v >= min
            checks += "$name ${if (ok) "PASS" else "FAIL"} observed=${rate.hits}/${rate.total} min=$min"
        }
        atLeast("date_precision", s.precision.getValue(DATE_FAMILY), t.getValue("date_precision_min"))
        atLeast("date_recall", s.recall.getValue(DATE_FAMILY), t.getValue("date_recall_min"))
        atLeast("money_precision", s.precision.getValue("MONEY"), t.getValue("money_precision_min"))
        atLeast("url_precision", s.precision.getValue("URL"), t.getValue("url_precision_min"))
        atLeast("email_precision", s.precision.getValue("EMAIL"), t.getValue("email_precision_min"))
        atLeast("phone_precision", s.precision.getValue("PHONE"), t.getValue("phone_precision_min"))
        atLeast("ambiguous_dm_flagged", s.dmFlagged, t.getValue("ambiguous_dm_flagged_min"))
        val bareOk = s.bareCurrency <= t.getValue("bare_currency_inferred_max").toInt()
        checks += "bare_currency_inferred ${if (bareOk) "PASS" else "FAIL"} observed=${s.bareCurrency} max=0"
        val failed = checks.count { it.contains(" FAIL ") }
        return Verdict(if (failed == 0) "PASS" else "FAIL", checks)
    }

    private fun report(engine: OcrEngine, runs: List<Run>, s: Summary, verdict: Verdict): String = buildString {
        appendLine("schema\topenlife-facts-image-report/1")
        appendLine("engine\t${engine.id}\t${engine.modelVersion}")
        appendLine("date\t${LocalDate.now()}")
        appendLine("device\t${Build.MANUFACTURER}\t${Build.MODEL}\tsdk=${Build.VERSION.SDK_INT}\tabi=${Build.SUPPORTED_ABIS.first()}")
        appendLine("gate\t${verdict.outcome}")
        verdict.checks.forEach { appendLine("check\t$it") }
        GATED.forEach { f ->
            appendLine("precision\t$f\t${s.precision.getValue(f).hits}/${s.precision.getValue(f).total}")
            appendLine("recall\t$f\t${s.recall.getValue(f).hits}/${s.recall.getValue(f).total}")
        }
        appendLine("dm_flagged\t${s.dmFlagged.hits}/${s.dmFlagged.total}")
        appendLine("bare_currency\t${s.bareCurrency}")
        appendLine("wrong_definite\t${s.wrongDefiniteTotal}\t${s.wrongDefinite}")
        appendLine("unexpected_emitted\t${s.unexpectedEmitted}")
        appendLine("misses_exact\t${s.misses}")
        appendLine("misses_ws_insensitive\t${s.missesWs}")
        appendLine("unexpected_abstentions\t${s.unexpectedAbstentions}")
        appendLine("raw_text_differs\t${s.rawDiffers}\t(informational: matched candidates whose raw text is not the truth raw text)")
        runs.forEach { r ->
            appendLine("image\t${r.id}\t${r.ocrState}\tocr=${r.ocrText.replace('\n', '|')}")
            r.emitted.forEach { e ->
                appendLine("emit\t${r.id}\t${e.outcome}\t${e.family}\t${e.kind}\t${e.rule}\t${e.raw}\t${e.canonical}")
            }
            r.units.filter { it.status != UnitStatus.EXACT }.forEach { u ->
                appendLine("miss\t${r.id}\t${u.status}\t${u.cause}\t${u.causeWs}\t${u.kind}\t${u.raw}")
            }
        }
    }

    private fun parseThresholds(text: String): Map<String, Double> =
        Regex("\"(\\w+)\"\\s*:\\s*([0-9.]+)").findAll(text).associate { it.groupValues[1] to it.groupValues[2].toDouble() }

    private fun assetBytes(path: String): ByteArray = assets.open(path).use { it.readBytes() }

    private fun asset(path: String): String = assetBytes(path).decodeToString()

    /** AGP copies files written to additionalTestOutputDir into build/outputs; otherwise external files. */
    private fun write(name: String, text: String): File {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(requireNotNull(context.getExternalFilesDir(null)), "facts-image-eval")
        dir.mkdirs()
        return File(dir, name).apply { writeText(text) }
    }

    private companion object {
        const val TAG = "P306Facts"
        const val DATE_FAMILY = "DATE"
        const val PER_IMAGE_TIMEOUT_MS = 60_000L
        const val LOG_CHUNK = 3_000
        val GATED = listOf("DATE", "MONEY", "URL", "EMAIL", "PHONE")
        val MULTI_CODE_SYMBOLS = setOf("$", "£", "¥", "₩", "kr")
        val ISO_CODE = Regex("[A-Z]{3}")
    }
}
