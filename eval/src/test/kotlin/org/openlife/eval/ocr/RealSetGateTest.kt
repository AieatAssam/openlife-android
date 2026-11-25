package org.openlife.eval.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.eval.ocr.core.CheckStatus
import org.openlife.eval.ocr.core.Digests
import org.openlife.eval.ocr.core.EngineResults
import org.openlife.eval.ocr.core.FixtureCategory
import org.openlife.eval.ocr.core.FixtureTruth
import org.openlife.eval.ocr.core.GateBinding
import org.openlife.eval.ocr.core.GateCodec
import org.openlife.eval.ocr.core.GateOutcome
import org.openlife.eval.ocr.core.ImageResult
import org.openlife.eval.ocr.core.ManifestCodec
import org.openlife.eval.ocr.core.OcrGate
import org.openlife.eval.ocr.core.OcrMetrics
import org.openlife.eval.ocr.core.ResultsCodec
import org.openlife.eval.ocr.core.RunOutcome
import org.openlife.eval.ocr.core.SpanObservation
import org.openlife.eval.ocr.core.TruthCodec
import java.io.File
import kotlin.math.abs

/** The committed set, gate and truth, loaded as the gate CLI loads them. */
internal object RealSet {
    val dir = File(System.getProperty("openlife.rootDir") ?: "..", "eval/ocr/v1")
    private val manifestText = File(dir, "manifest.json").readText()
    private val gateText = File(dir, "gate.json").readText()
    val manifest = ManifestCodec.decode(manifestText)
    val gate = GateCodec.decode(gateText)
    val truths: Map<String, FixtureTruth> =
        manifest.fixtures.associate { it.id to TruthCodec.decode(File(dir, it.truth).readText()) }
    val binding = GateBinding(Digests.sha256(manifestText), Digests.sha256(gateText), manifest.fixtures, truths)

    /** One span per truth line. */
    fun wholeRows(truth: FixtureTruth) = truth.lines.map { SpanObservation(it.text, it.box) }

    /** One span per column: item and price apart, as an engine may segment a receipt. */
    fun columns(truth: FixtureTruth) = truth.lines.flatMap { line ->
        line.segments.map {
            SpanObservation(
                it.text,
                it.box,
            )
        }
    }

    /** Results a perfect recogniser would record for [engineId]: correct text, abstaining exactly where expected. */
    fun perfectResults(engineId: String, spans: (FixtureTruth) -> List<SpanObservation> = ::columns): EngineResults {
        val pin = gate.engines.single { it.id == engineId }
        return EngineResults(
            engineId = engineId,
            modelVersion = pin.modelVersion,
            codeCommit = GateFixtures.COMMIT,
            manifestSha256 = binding.manifestSha256,
            gateSha256 = binding.gateSha256,
            runDate = "2026-11-25",
            device = mapOf("manufacturer" to "fake", "model" to "fake", "sdk" to "36", "abi" to "arm64-v8a"),
            images = manifest.fixtures.map { entry ->
                val truth = truths.getValue(entry.id)
                val output = if (truth.expectedAbstain) emptyList() else spans(truth)
                scored(
                    ImageResult(
                        id = truth.id,
                        category = truth.category,
                        expectedAbstain = truth.expectedAbstain,
                        outcome = if (truth.expectedAbstain) RunOutcome.ABSTAINED else RunOutcome.OUTPUT,
                        cer = null,
                        linesTotal = truth.lines.size,
                        linesMatched = null,
                        durationMs = 1_000,
                        baselinePssKib = 50_000,
                        peakPssKib = 90_000,
                        policy = null,
                        spans = output,
                        rawSpans = emptyList(),
                    ),
                )
            },
        )
    }

    /** Records the scores the harness would compute for [image]'s spans. */
    fun scored(image: ImageResult): ImageResult {
        val score = OcrMetrics.score(truths.getValue(image.id), image.spans, gate.lineMatchIou)
        return image.copy(cer = score.cer, linesMatched = score.linesMatched)
    }
}

class RealSetGateTest {
    /** Review X-01 and X-02 on the committed set: both ways of reading columns are perfect. */
    @Test
    fun perfectWholeRowAndPerColumnRecognisersScorePerfectly() {
        val latin = RealSet.truths.values.filterNot { it.expectedAbstain }
        val columnRows = latin.sumOf { truth -> truth.lines.count { it.segments.size > 1 } }
        assertTrue("the set has receipt column rows ($columnRows)", columnRows >= 100)
        val skewedReceipts = latin.filter { it.category == FixtureCategory.RECEIPT && abs(it.skewDegrees) >= 1.0 }
        assertTrue("skewed receipts exist", skewedReceipts.size >= 5)
        for (truth in latin) {
            for ((name, spans) in listOf(
                "whole rows" to RealSet.wholeRows(truth),
                "columns" to RealSet.columns(truth),
            )) {
                val score = OcrMetrics.score(truth, spans, RealSet.gate.lineMatchIou)
                assertEquals("${truth.id} $name (skew ${truth.skewDegrees}) CER", 0.0, score.cer, 0.0)
                assertEquals("${truth.id} $name lines matched", truth.lines.size, score.linesMatched)
            }
        }
        for (engine in RealSet.gate.engines) {
            for (reader in listOf(RealSet::wholeRows, RealSet::columns)) {
                val verdict = evaluate(RealSet.perfectResults(engine.id, reader))
                assertEquals(verdict.problems.toString(), GateOutcome.PASS, verdict.outcome)
                assertEquals(1.0, verdict.checks.single { it.name == OcrGate.CHECK_LINE_MATCH }.observed!!, 0.0)
            }
        }
    }

    /**
     * Acting-owner ruling (review X-03): one abstention on a Latin fixture
     * fails the engine. On the real set it would otherwise be diluted to a
     * pass: every CER mean and the line match rate still pass.
     */
    @Test
    fun oneAbstentionOnALatinFixtureFailsTheGate() {
        val perfect = RealSet.perfectResults(ENGINE)
        // Screenshots: 1 of 14 Latin images at CER 1.0 is a mean of 0.071 <= 0.08; receipts: 1 of 13 is 0.077 <= 0.15.
        for (id in listOf("screenshot-02", "receipt-01")) {
            val results = perfect.copy(
                images = perfect.images.map {
                    if (it.id == id) {
                        RealSet.scored(
                            it.copy(outcome = RunOutcome.ABSTAINED, spans = emptyList()),
                        )
                    } else {
                        it
                    }
                },
            )
            val verdict = evaluate(results)
            assertEquals(id, GateOutcome.FAIL, verdict.outcome)
            assertEquals(
                "$id: only the abstention check fails; the averages alone would have passed",
                listOf(OcrGate.CHECK_LATIN_ABSTENTIONS),
                verdict.checks.filter { it.status == CheckStatus.FAIL }.map { it.name },
            )
        }
    }

    /** Review X-04 and X-05: recorded scores are recomputed, and results are bound to the pinned model. */
    @Test
    fun recordedScoresAndProvenanceAreCheckedAgainstTheSet() {
        val perfect = RealSet.perfectResults(ENGINE)
        val id = "receipt-04"
        fun withImage(change: (ImageResult) -> ImageResult) =
            perfect.copy(images = perfect.images.map { if (it.id == id) change(it) else it })
        val dropped = withImage { it.copy(spans = it.spans.drop(2)) }
        val cases = mapOf(
            "spans changed, scores not" to dropped,
            "CER flattered" to RealSet.scored(dropped.images.single { it.id == id }).let { honest ->
                withImage { honest.copy(cer = 0.0) }
            },
            "matches flattered" to RealSet.scored(dropped.images.single { it.id == id }).let { honest ->
                withImage { honest.copy(linesMatched = honest.linesTotal) }
            },
            "stale model" to perfect.copy(modelVersion = "t4a-4.8.0+tessdata_fast-4.0.0"),
            "no commit" to perfect.copy(codeCommit = ""),
        )
        for ((case, results) in cases) {
            val verdict = evaluate(results)
            assertEquals(case, GateOutcome.INVALID, verdict.outcome)
        }
        val honest = withImage { RealSet.scored(it.copy(spans = it.spans.drop(2))) }
        assertTrue(evaluate(honest).outcome != GateOutcome.INVALID)
    }

    @Test
    fun committedGatePinsBothRegisteredEngines() {
        assertEquals(
            listOf("tesseract-eng-fast" to "t4a-4.9.0+tessdata_fast-4.1.0-7d4322bd", "mlkit-latin" to "16.0.1"),
            RealSet.gate.engines.map { it.id to it.modelVersion },
        )
    }

    private fun evaluate(results: EngineResults) =
        OcrGate.evaluate(results.engineId, RealSet.gate, RealSet.binding, ResultsCodec.encode(results))

    private companion object {
        const val ENGINE = "tesseract-eng-fast"
    }
}
