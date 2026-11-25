package org.openlife.eval.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.eval.ocr.core.Box
import org.openlife.eval.ocr.core.CheckStatus
import org.openlife.eval.ocr.core.EnginePin
import org.openlife.eval.ocr.core.EngineResults
import org.openlife.eval.ocr.core.FixtureCategory
import org.openlife.eval.ocr.core.FixtureTruth
import org.openlife.eval.ocr.core.GateBinding
import org.openlife.eval.ocr.core.GateOutcome
import org.openlife.eval.ocr.core.GateThresholds
import org.openlife.eval.ocr.core.ImageResult
import org.openlife.eval.ocr.core.LineMatch
import org.openlife.eval.ocr.core.ManifestEntry
import org.openlife.eval.ocr.core.OcrGate
import org.openlife.eval.ocr.core.OcrMetrics
import org.openlife.eval.ocr.core.PolicyCost
import org.openlife.eval.ocr.core.ResultsCodec
import org.openlife.eval.ocr.core.RunOutcome
import org.openlife.eval.ocr.core.ScriptClass
import org.openlife.eval.ocr.core.ScriptPolicyCost
import org.openlife.eval.ocr.core.SpanObservation
import org.openlife.eval.ocr.core.TruthLine
import org.openlife.eval.ocr.core.TruthSegment

/**
 * The tdd.red tests the P2-04 brief names under OcrEvaluationTest. They are
 * pure functions over hand-made cases, so they run on the JVM; the
 * instrumented class of the same name only runs the engines (P2-04 notes).
 * The same rules on the committed set are in [RealSetGateTest].
 */
class OcrEvaluationTest {
    @Test
    fun metricsAreComputedAgainstTruth() {
        // CER: Levenshtein over code points / reference length.
        assertEquals(3, OcrMetrics.levenshtein("kitten", "sitting"))
        assertEquals(0.5, OcrMetrics.cer("kitten", "sitting"), EPS)
        assertEquals(0.0, OcrMetrics.cer("TOTAL 9.99", "TOTAL 9.99"), EPS)
        assertEquals("an empty hypothesis deletes everything", 1.0, OcrMetrics.cer("abcd", ""), EPS)
        // U+1D400 is one code point but two UTF-16 units: one substitution in two code points.
        assertEquals(0.5, OcrMetrics.cer("𝐀a", "𝐀b"), EPS)

        assertEquals("TOTAL £12.40", OcrMetrics.normalizeLine("  TOTAL \t  £12.40 \n"))
        assertEquals("café", OcrMetrics.normalizeLine("café"))
        assertEquals(
            "a b\nc",
            OcrMetrics.referenceText(
                listOf(
                    TruthLine(" a  b ", Box(0, 0, 1, 1), ScriptClass.LATIN),
                    TruthLine("c", Box(0, 2, 1, 3), ScriptClass.LATIN),
                ),
            ),
        )
        // Same row (centres 115 and 116.5) ordered left to right; next row; then a span with no region.
        assertEquals(
            "COFFEE 7.49\nTOTAL\nloose",
            OcrMetrics.hypothesisText(
                listOf(
                    SpanObservation("7.49", Box(400, 100, 460, 130)),
                    SpanObservation("loose", null),
                    SpanObservation("TOTAL", Box(20, 200, 120, 230)),
                    SpanObservation("COFFEE", Box(20, 102, 150, 131)),
                ),
            ),
        )

        // IoU: 50 / 150; identical; disjoint; touching edges share no pixel.
        assertEquals(1.0 / 3.0, OcrMetrics.iou(Box(0, 0, 10, 10), Box(5, 0, 15, 10)), EPS)
        assertEquals(1.0, OcrMetrics.iou(Box(3, 4, 30, 40), Box(3, 4, 30, 40)), EPS)
        assertEquals(0.0, OcrMetrics.iou(Box(0, 0, 10, 10), Box(20, 20, 30, 30)), EPS)
        assertEquals(0.0, OcrMetrics.iou(Box(0, 0, 10, 10), Box(10, 0, 20, 10)), EPS)

        // Line matching: truth A=(0,0,100,20), B=(0,30,100,50).
        // s0 vs B = 1900/2000 = 0.95; s1 vs A = 1200/2000 = 0.6; s2 vs A = 800/2000 = 0.4.
        val truth = listOf(line("A", Box(0, 0, 100, 20)), line("B", Box(0, 30, 100, 50)))
        val spans = listOf(
            SpanObservation("b", Box(0, 31, 100, 50)),
            SpanObservation("a", Box(0, 0, 60, 20)),
            SpanObservation("a'", Box(0, 0, 40, 20)),
            SpanObservation("x", null),
        )
        assertEquals(
            listOf(LineMatch(0, listOf(1), false), LineMatch(1, listOf(0), false)),
            OcrMetrics.matchLines(truth, spans, 0.5),
        )
        assertEquals(listOf(LineMatch(1, listOf(0), false)), OcrMetrics.matchLines(truth, spans, 0.7))
        // One-to-one: two truth lines cannot share the one span that overlaps both.
        assertEquals(
            1,
            OcrMetrics.matchLines(
                listOf(line("s", Box(0, 0, 10, 10)), line("s", Box(0, 0, 10, 10))),
                listOf(SpanObservation("s", Box(0, 0, 10, 10))),
                0.5,
            ).size,
        )

        // p95 nearest rank: rank ceil(0.95 n).
        assertEquals(19L, OcrMetrics.p95NearestRank((1L..20L).toList().shuffled(java.util.Random(1))))
        assertEquals(95L, OcrMetrics.p95NearestRank((1L..100L).toList()))
        assertEquals(20L, OcrMetrics.p95NearestRank(listOf(20L, 10L)))
        assertEquals(5L, OcrMetrics.p95NearestRank(listOf(5L)))
    }

    /** Review X-01: a receipt row may be read as one span or as item and price; both match it. */
    @Test
    fun columnRowsMatchAsOneSpanOrOneSpanPerColumn() {
        // Row (40,100)-(540,130); item (40,100)-(220,130); price (470,101)-(540,130).
        val row = TruthLine(
            "SCREWS 40MM      4.73",
            Box(40, 100, 540, 130),
            ScriptClass.LATIN,
            listOf(
                TruthSegment("SCREWS 40MM", Box(40, 100, 220, 130), ScriptClass.LATIN),
                TruthSegment("4.73", Box(470, 101, 540, 130), ScriptClass.LATIN),
            ),
        )
        val whole = listOf(SpanObservation("SCREWS 40MM 4.73", Box(41, 100, 540, 130)))
        val split = listOf(
            SpanObservation("4.73", Box(470, 100, 540, 130)),
            SpanObservation("SCREWS 40MM", Box(40, 100, 221, 130)),
        )
        assertEquals(listOf(LineMatch(0, listOf(0), false)), OcrMetrics.matchLines(listOf(row), whole, 0.5))
        assertEquals(
            "spans in segment order",
            listOf(LineMatch(0, listOf(1, 0), true)),
            OcrMetrics.matchLines(listOf(row), split, 0.5),
        )
        // Item alone: 181/500 = 0.36 against the row, so half a row is no match.
        assertEquals(emptyList<LineMatch>(), OcrMetrics.matchLines(listOf(row), split.drop(1), 0.5))
        // Price alone matches only its own segment.
        assertEquals(emptyList<LineMatch>(), OcrMetrics.matchLines(listOf(row), split.take(1), 0.5))
    }

    /**
     * Review X-02: on a page rotated by -2 degrees the price of a row sits
     * higher than its item, so ordering by raw centre reads the price first.
     * Rotating centres back by the recorded skew keeps them on one row.
     */
    @Test
    fun readingOrderUndoesTheRecordedSkew() {
        // Unrotated: item centre (150, 300), price centre (450, 300), page 600x600 centred at (300, 300).
        // Rotated by -2 degrees: dy = sin(-2deg) * (x - 300): item +5.24, price -5.24.
        // Box centres: item 305.5, price 295 (10.5 apart, more than half the 20 px height), next row 340.5.
        val item = SpanObservation("MILK 1L", Box(90, 295, 210, 316))
        val price = SpanObservation("1.20", Box(420, 285, 480, 305))
        val next = SpanObservation("TOTAL", Box(90, 330, 190, 351))
        assertEquals("1.20\nMILK 1L\nTOTAL", OcrMetrics.hypothesisText(listOf(item, price, next)))
        assertEquals("MILK 1L 1.20\nTOTAL", OcrMetrics.hypothesisText(listOf(item, price, next), -2.0, 600, 600))
    }

    @Test
    fun gateFailsWhenAnyThresholdIsMissed() {
        val passing = GateFixtures.passingImages()
        val baseline = verdict(passing)
        assertEquals(GateOutcome.PASS, baseline.outcome)
        assertTrue(baseline.permitsDefault)
        assertEquals(9, baseline.checks.size)
        assertTrue(baseline.checks.all { it.status == CheckStatus.PASS })

        val misses = mapOf(
            // (0.15 + 0.02) / 2 = 0.085 > 0.08; s2 is an abstain fixture and is not in the mean.
            "cer_mean_screenshot" to passing.withImage("s1") { it.copy(cer = 0.15) },
            // (0.29 + 0.02) / 2 = 0.155 > 0.15
            "cer_mean_receipt" to passing.withImage("r1") { it.copy(cer = 0.29) },
            "cer_mean_letter" to passing.withImage("l1") { it.copy(cer = 0.11) },
            // 17 of 19 lines = 0.894 < 0.90
            OcrGate.CHECK_LINE_MATCH to passing.withImage("r1") { it.copy(linesMatched = 2) },
            OcrGate.CHECK_ABSTENTION to passing.withImage("s2") {
                it.copy(outcome = RunOutcome.OUTPUT, cer = 0.9, linesMatched = 1)
            },
            // One abstention on a Latin screenshot fails on its own, whatever the CER mean.
            OcrGate.CHECK_LATIN_ABSTENTIONS to passing.withImage("s3") {
                it.copy(outcome = RunOutcome.ABSTAINED, cer = 0.02, linesMatched = 4)
            },
            OcrGate.CHECK_TIMEOUTS to passing.withImage("l1") { it.copy(outcome = RunOutcome.TIMED_OUT) },
            OcrGate.CHECK_DURATION to passing.withImage("l1") { it.copy(durationMs = 6_001) },
            OcrGate.CHECK_PSS to passing.withImage("r1") { it.copy(peakPssKib = 10_000L + 122_881L) },
        )
        for ((check, images) in misses) {
            val verdict = verdict(images)
            assertEquals(check, GateOutcome.FAIL, verdict.outcome)
            assertFalse(check, verdict.permitsDefault)
            assertEquals(check, listOf(check), verdict.checks.filter { it.status == CheckStatus.FAIL }.map { it.name })
        }

        // Exactly at a threshold passes; the limits are inclusive.
        val atLimits = passing
            .withImage("s1") { it.copy(cer = 0.08) }
            .withImage("s3") { it.copy(cer = 0.08) }
            .withImage("l1") { it.copy(durationMs = 6_000) }
            .withImage("r1") { it.copy(peakPssKib = 10_000L + 122_880L) }
        assertEquals(GateOutcome.PASS, verdict(atLimits).outcome)
    }

    @Test
    fun gateReportsNoMeasurementWhenThereIsNothingToMeasure() {
        val absent = OcrGate.evaluate(GateFixtures.ENGINE, GateFixtures.thresholds, GateFixtures.binding, null)
        assertEquals(GateOutcome.NO_MEASUREMENT, absent.outcome)
        assertFalse(absent.permitsDefault)
        assertTrue(absent.checks.all { it.status == CheckStatus.NOT_MEASURED })

        val noDuration = verdict(GateFixtures.passingImages().withImage("l1") { it.copy(durationMs = null) })
        assertEquals(GateOutcome.NO_MEASUREMENT, noDuration.outcome)
        assertFalse(noDuration.permitsDefault)
        assertEquals(
            listOf(OcrGate.CHECK_DURATION),
            noDuration.checks.filter { it.status == CheckStatus.NOT_MEASURED }.map { it.name },
        )

        assertEquals(
            GateOutcome.NO_MEASUREMENT,
            verdict(GateFixtures.passingImages().withImage("s1") { it.copy(peakPssKib = null) }).outcome,
        )

        // A category with no Latin fixture has no mean: not measured, never 0.
        val onlyAbstainLetters = GateFixtures.passingImages().withImage("l1") {
            it.copy(expectedAbstain = true, outcome = RunOutcome.ABSTAINED, cer = 1.0, linesMatched = 0)
        }
        val verdict = verdict(onlyAbstainLetters)
        assertEquals(GateOutcome.NO_MEASUREMENT, verdict.outcome)
        assertEquals(CheckStatus.NOT_MEASURED, verdict.checks.single { it.name == "cer_mean_letter" }.status)

        // A failure outranks a missing measurement: the verdict is FAIL, still not PASS.
        val failAndMissing = GateFixtures.passingImages()
            .withImage("l1") { it.copy(durationMs = null) }
            .withImage("s1") { it.copy(cer = 0.5) }
        assertEquals(GateOutcome.FAIL, verdict(failAndMissing).outcome)
    }

    @Test
    fun gateIsInvalidForMalformedOrUnboundResults() {
        val passing = GateFixtures.passingResults()
        assertEquals(GateOutcome.PASS, evaluate(ResultsCodec.encode(passing)).outcome)
        val invalid = mapOf(
            "not JSON" to "{ this is not json",
            "trailing data" to ResultsCodec.encode(passing) + "{}",
            "another set" to ResultsCodec.encode(passing.copy(manifestSha256 = "0".repeat(64))),
            "another gate" to ResultsCodec.encode(passing.copy(gateSha256 = "1".repeat(64))),
            "another engine" to ResultsCodec.encode(passing.copy(engineId = "mlkit-latin")),
            "stale model" to ResultsCodec.encode(passing.copy(modelVersion = "t4a-4.8.0")),
            "no commit" to ResultsCodec.encode(passing.copy(codeCommit = "HEAD")),
            "no device" to ResultsCodec.encode(passing.copy(device = mapOf("model" to "x"))),
            "missing fixture" to ResultsCodec.encode(passing.copy(images = passing.images.drop(1))),
            "duplicate fixture" to ResultsCodec.encode(passing.copy(images = passing.images + passing.images[0])),
            "unknown fixture" to ResultsCodec.encode(
                passing.copy(images = passing.images + passing.images[0].copy(id = "x9")),
            ),
            "category disagrees with truth" to encode(
                passing,
            ) { "s1" to { it.copy(category = FixtureCategory.LETTER) } },
            "abstain flag disagrees with truth" to encode(passing) { "s1" to { it.copy(expectedAbstain = true) } },
            "recorded CER is not the spans' CER" to encode(passing) { "s1" to { it.copy(cer = 0.01) } },
            "recorded CER missing" to encode(passing) { "s1" to { it.copy(cer = null) } },
            "recorded matches are not the spans' matches" to encode(passing) { "s1" to { it.copy(linesMatched = 3) } },
            "line total disagrees with truth" to encode(passing) { "s1" to { it.copy(linesTotal = 3) } },
            "negative duration" to encode(passing) { "s1" to { it.copy(durationMs = -1) } },
            "peak below baseline" to encode(passing) { "s1" to { it.copy(peakPssKib = 1) } },
            "abstained but kept its spans" to encode(passing) { "r1" to { it.copy(outcome = RunOutcome.ABSTAINED) } },
            "abstained but scored as text" to encode(passing) {
                "r1" to { it.copy(outcome = RunOutcome.ABSTAINED, spans = emptyList()) }
            },
        )
        for ((case, text) in invalid) {
            val verdict = evaluate(text)
            assertEquals(case, GateOutcome.INVALID, verdict.outcome)
            assertFalse(case, verdict.permitsDefault)
            assertTrue(case, verdict.problems.isNotEmpty())
        }
    }

    @Test
    fun strayNonLatinGlyphCostIsMeasuredForBothPolicies() {
        val truth = listOf(
            line("SHOP LTD", Box(10, 10, 200, 40)),
            TruthLine("ПЛЯЖ TOWEL 9.99", Box(10, 50, 300, 80), ScriptClass.MIXED),
            line("TOTAL 9.99", Box(10, 90, 200, 120)),
            TruthLine("Привет", Box(10, 130, 150, 160), ScriptClass.NON_LATIN),
            line("THANK YOU", Box(10, 170, 200, 200)),
        )
        val cyrillic: (String) -> Boolean = { text ->
            text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.CYRILLIC }
        }

        // An engine that reads the Cyrillic: production abstains on the whole revision.
        val readsCyrillic = listOf(
            SpanObservation("SHOP LTD", Box(10, 11, 200, 40)),
            SpanObservation("ПЛЯЖ TOWEL 9.99", Box(10, 50, 300, 80)),
            SpanObservation("TOTAL 9.99", Box(11, 90, 200, 120)),
            SpanObservation("Привет", Box(10, 130, 150, 160)),
        )
        val abstaining = ScriptPolicyCost.compare(truth, readsCyrillic, true, 0.5, cyrillic)
        assertEquals(3, abstaining.latinLines)
        assertEquals(2, abstaining.nonLatinLines)
        assertEquals("THANK YOU was never recognised", 1, abstaining.unmatchedLines)
        assertEquals("whole revision drops SHOP LTD and TOTAL", PolicyCost(2, 0), abstaining.wholeRevision)
        assertEquals("per span keeps every Latin line", PolicyCost(0, 0), abstaining.perSpan)

        // An English-only model reads Cyrillic as Latin look-alikes: nothing abstains, both policies accept it.
        val transliterates = listOf(
            SpanObservation("SHOP LTD", Box(10, 11, 200, 40)),
            SpanObservation("NJIRK TOWEL 9.99", Box(10, 50, 300, 80)),
            SpanObservation("TOTAL 9.99", Box(11, 90, 200, 120)),
            SpanObservation("Npnbet", Box(10, 130, 150, 160)),
        )
        val accepting = ScriptPolicyCost.compare(truth, transliterates, false, 0.5, cyrillic)
        assertEquals(PolicyCost(0, 2), accepting.wholeRevision)
        assertEquals(PolicyCost(0, 2), accepting.perSpan)

        // A Latin line misread with a Cyrillic look-alike is lost by both policies.
        val misread = readsCyrillic.toMutableList().apply {
            set(
                2,
                SpanObservation("TОTAL 9.99", Box(11, 90, 200, 120)),
            )
        }
        val perSpanLoss = ScriptPolicyCost.compare(truth, misread, true, 0.5, cyrillic)
        assertEquals(PolicyCost(2, 0), perSpanLoss.wholeRevision)
        assertEquals(PolicyCost(1, 0), perSpanLoss.perSpan)

        // Columns: the Latin price kept beside a dropped Cyrillic item is not an acceptance.
        val columnRow = TruthLine(
            "ПЛЯЖ TOWEL      9.99",
            Box(10, 50, 300, 80),
            ScriptClass.MIXED,
            listOf(
                TruthSegment("ПЛЯЖ TOWEL", Box(10, 50, 150, 80), ScriptClass.MIXED),
                TruthSegment("9.99", Box(240, 50, 300, 80), ScriptClass.LATIN),
            ),
        )
        val splitSpans = listOf(
            SpanObservation("ПЛЯЖ TOWEL", Box(10, 50, 150, 80)),
            SpanObservation("9.99", Box(240, 50, 300, 80)),
        )
        val columns = ScriptPolicyCost.compare(listOf(columnRow), splitSpans, true, 0.5, cyrillic)
        assertEquals(0, columns.unmatchedLines)
        assertEquals(PolicyCost(0, 0), columns.perSpan)
    }

    private fun verdict(images: List<ImageResult>) = OcrGate.verdict(
        GateFixtures.ENGINE,
        GateFixtures.thresholds,
        images,
    )

    private fun evaluate(text: String) =
        OcrGate.evaluate(GateFixtures.ENGINE, GateFixtures.thresholds, GateFixtures.binding, text)

    private fun encode(results: EngineResults, change: () -> Pair<String, (ImageResult) -> ImageResult>): String {
        val (id, edit) = change()
        return ResultsCodec.encode(results.copy(images = results.images.withImage(id, edit)))
    }

    private fun List<ImageResult>.withImage(id: String, change: (ImageResult) -> ImageResult): List<ImageResult> =
        map { if (it.id == id) change(it) else it }

    private fun line(text: String, box: Box) = TruthLine(text, box, ScriptClass.LATIN)

    private companion object {
        const val EPS = 1e-9
    }
}

/**
 * A tiny bound set: 3 screenshots (one non-Latin), 2 receipts, 1 letter; 19
 * Latin lines. [passingImages] carries hand-set scores for threshold tests;
 * [passingResults] carries a perfect recogniser's spans, so the gate's
 * recomputation agrees with every recorded score.
 */
internal object GateFixtures {
    const val ENGINE = "tesseract-eng-fast"
    const val MODEL = "t4a-test"
    const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
    private val MANIFEST = "a".repeat(64)
    private val GATE = "b".repeat(64)
    private const val LINE_PITCH = 40

    val thresholds = GateThresholds(
        lineMatchIou = 0.5,
        cerMeanMax = mapOf(
            FixtureCategory.SCREENSHOT to 0.08,
            FixtureCategory.RECEIPT to 0.15,
            FixtureCategory.LETTER to 0.10,
        ),
        lineMatchRateMin = 0.90,
        abstentionCorrectRateMin = 1.0,
        latinAbstentionsMax = 0,
        timeoutsMax = 0,
        durationP95MsMax = 6_000,
        peakPssDeltaKibMax = 122_880,
        engines = listOf(EnginePin(ENGINE, MODEL), EnginePin("mlkit-latin", "16.0.1")),
    )

    private val fixtures = listOf(
        Triple("s1", FixtureCategory.SCREENSHOT, false) to 4,
        Triple("s2", FixtureCategory.SCREENSHOT, true) to 3,
        Triple("s3", FixtureCategory.SCREENSHOT, false) to 4,
        Triple("r1", FixtureCategory.RECEIPT, false) to 4,
        Triple("r2", FixtureCategory.RECEIPT, false) to 4,
        Triple("l1", FixtureCategory.LETTER, false) to 3,
    )

    private val truths: Map<String, FixtureTruth> = fixtures.associate { (fixture, count) ->
        val (id, category, abstain) = fixture
        val script = if (abstain) ScriptClass.NON_LATIN else ScriptClass.LATIN
        id to FixtureTruth(
            id = id,
            category = category,
            image = "fixtures/$id.png",
            width = 400,
            height = 400,
            seed = 1,
            style = "test",
            skewDegrees = 0.0,
            noiseDensity = 0.0,
            scriptClass = script,
            expectedAbstain = abstain,
            lines = List(count) { i ->
                TruthLine("$id line $i", Box(10, 10 + i * LINE_PITCH, 300, 40 + i * LINE_PITCH), script)
            },
        )
    }

    val binding = GateBinding(
        manifestSha256 = MANIFEST,
        gateSha256 = GATE,
        fixtures = fixtures.map { (fixture, _) ->
            ManifestEntry(fixture.first, fixture.second, fixture.third, "fixtures/${fixture.first}.png", "", "", "", "")
        },
        truths = truths,
    )

    fun passingImages(): List<ImageResult> = fixtures.map { (fixture, lines) ->
        val (id, category, abstain) = fixture
        image(id, category, abstain, lines).copy(cer = if (abstain) 1.0 else 0.02)
    }

    fun passingResults(): EngineResults = EngineResults(
        engineId = ENGINE,
        modelVersion = MODEL,
        codeCommit = COMMIT,
        manifestSha256 = MANIFEST,
        gateSha256 = GATE,
        runDate = "2026-11-25",
        device = mapOf("manufacturer" to "fake", "model" to "fake", "sdk" to "36", "abi" to "arm64-v8a"),
        images = fixtures.map { (fixture, lines) ->
            val (id, category, abstain) = fixture
            val spans = if (abstain) emptyList() else truths.getValue(id).lines.map { SpanObservation(it.text, it.box) }
            image(id, category, abstain, lines).copy(cer = if (abstain) 1.0 else 0.0, spans = spans)
        },
    )

    private fun image(id: String, category: FixtureCategory, abstain: Boolean, lines: Int) = ImageResult(
        id = id,
        category = category,
        expectedAbstain = abstain,
        outcome = if (abstain) RunOutcome.ABSTAINED else RunOutcome.OUTPUT,
        cer = null,
        linesTotal = lines,
        linesMatched = if (abstain) 0 else lines,
        durationMs = 1_000,
        baselinePssKib = 10_000,
        peakPssKib = 60_000,
        policy = null,
        spans = emptyList(),
        rawSpans = emptyList(),
    )
}
