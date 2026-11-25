package org.openlife.eval.ocr.core

/** The thresholds in eval/ocr/v1/gate.json (P2-04-R3, R4). */
data class GateThresholds(
    val lineMatchIou: Double,
    val cerMeanMax: Map<FixtureCategory, Double>,
    val lineMatchRateMin: Double,
    val abstentionCorrectRateMin: Double,
    val durationP95MsMax: Long,
    val peakPssDeltaKibMax: Long,
    val engines: List<String>,
)

object GateCodec {
    const val SCHEMA = "openlife-ocr-gate/1"

    /** Any missing or mistyped threshold is a [JsonFormatException]: the gate never runs on a partial file. */
    fun decode(text: String): GateThresholds {
        val root = Json.parseObject(text)
        if (root.string("schema") != SCHEMA) jsonFail("unknown gate schema")
        val thresholds = root.obj("thresholds")
        val cer = thresholds.obj("cer_mean_max")
        if (cer.fields.keys != FixtureCategory.entries.map { it.key }.toSet()) {
            jsonFail("cer_mean_max must name exactly screenshot, receipt and letter")
        }
        return GateThresholds(
            lineMatchIou = root.double("line_match_iou"),
            cerMeanMax = FixtureCategory.entries.associateWith { cer.double(it.key) },
            lineMatchRateMin = thresholds.double("line_match_rate_min"),
            abstentionCorrectRateMin = thresholds.double("abstention_correct_rate_min"),
            durationP95MsMax = thresholds.long("duration_p95_ms_max"),
            peakPssDeltaKibMax = thresholds.long("peak_pss_delta_kib_max"),
            engines = root.array("engines").map {
                (it as? JsonString)?.value ?: jsonFail("engine id is not a string")
            },
        )
    }
}

enum class CheckStatus { PASS, FAIL, NOT_MEASURED }

data class GateCheck(val name: String, val status: CheckStatus, val observed: Double?, val limit: Double)

/**
 * PASS only when every check passed. NO_MEASUREMENT when there is no results
 * file or some check had nothing to measure; INVALID when the results file
 * is malformed or not bound to this set and gate. Only PASS permits an
 * engine to be the default (P2-04-R3).
 */
enum class GateOutcome { PASS, FAIL, NO_MEASUREMENT, INVALID }

data class GateVerdict(
    val engineId: String,
    val outcome: GateOutcome,
    val checks: List<GateCheck>,
    val problems: List<String>,
) {
    val permitsDefault: Boolean get() = outcome == GateOutcome.PASS
}

/**
 * What a results file must be bound to: the committed set and gate, each
 * fixture's category and abstain flag, and its number of truth lines.
 */
data class GateBinding(
    val manifestSha256: String,
    val gateSha256: String,
    val fixtures: List<ManifestEntry>,
    val lineCounts: Map<String, Int>,
)

object OcrGate {
    const val CHECK_CER_PREFIX = "cer_mean_"
    const val CHECK_LINE_MATCH = "line_match_rate"
    const val CHECK_ABSTENTION = "abstention_correct_rate_non_latin"
    const val CHECK_DURATION = "duration_p95_ms"
    const val CHECK_PSS = "peak_pss_delta_kib_max"

    /** [resultsText] is the results file's content, or null when no such file exists. */
    fun evaluate(
        engineId: String,
        thresholds: GateThresholds,
        binding: GateBinding,
        resultsText: String?,
    ): GateVerdict {
        if (resultsText == null) {
            return GateVerdict(engineId, GateOutcome.NO_MEASUREMENT, notMeasured(thresholds), listOf("no results file"))
        }
        val results = try {
            ResultsCodec.decode(resultsText)
        } catch (malformed: IllegalArgumentException) {
            return GateVerdict(
                engineId,
                GateOutcome.INVALID,
                notMeasured(thresholds),
                listOf("malformed: ${malformed.message}"),
            )
        }
        val problems = ResultsValidator.problems(engineId, binding, results)
        if (problems.isNotEmpty()) return GateVerdict(engineId, GateOutcome.INVALID, notMeasured(thresholds), problems)
        val checks = checks(thresholds, results.images)
        val outcome = when {
            checks.any { it.status == CheckStatus.FAIL } -> GateOutcome.FAIL
            checks.any { it.status == CheckStatus.NOT_MEASURED } -> GateOutcome.NO_MEASUREMENT
            else -> GateOutcome.PASS
        }
        return GateVerdict(engineId, outcome, checks, emptyList())
    }

    private fun checks(thresholds: GateThresholds, images: List<ImageResult>): List<GateCheck> {
        val latin = images.filterNot { it.expectedAbstain }
        val cerChecks = FixtureCategory.entries.map { category ->
            val limit = thresholds.cerMeanMax.getValue(category)
            val values = latin.filter { it.category == category }.map { it.cer }
            val mean = if (values.isEmpty() || values.any { it == null }) null else values.filterNotNull().average()
            atMost(CHECK_CER_PREFIX + category.key, mean, limit)
        }
        val totals = latin.map { it.linesTotal }
        val matched = latin.map { it.linesMatched }
        val lineRate = if (totals.any { it == null } || matched.any { it == null } || totals.sumOf { it ?: 0 } == 0) {
            null
        } else {
            matched.sumOf { it ?: 0 }.toDouble() / totals.sumOf { it ?: 0 }
        }
        val nonLatin = images.filter { it.expectedAbstain }
        val abstention = if (nonLatin.isEmpty()) null else nonLatin.count { it.abstained }.toDouble() / nonLatin.size
        val durations = images.map { it.durationMs }
        val p95 = if (durations.any { it == null }) null else OcrMetrics.p95NearestRank(durations.filterNotNull())
        val deltas = images.map { image ->
            val baseline = image.baselinePssKib
            val peak = image.peakPssKib
            if (baseline == null || peak == null) null else peak - baseline
        }
        val pss = if (deltas.any { it == null }) null else deltas.filterNotNull().max()
        return cerChecks + listOf(
            atLeast(CHECK_LINE_MATCH, lineRate, thresholds.lineMatchRateMin),
            atLeast(CHECK_ABSTENTION, abstention, thresholds.abstentionCorrectRateMin),
            atMost(CHECK_DURATION, p95?.toDouble(), thresholds.durationP95MsMax.toDouble()),
            atMost(CHECK_PSS, pss?.toDouble(), thresholds.peakPssDeltaKibMax.toDouble()),
        )
    }

    private fun notMeasured(thresholds: GateThresholds): List<GateCheck> = FixtureCategory.entries.map {
        GateCheck(CHECK_CER_PREFIX + it.key, CheckStatus.NOT_MEASURED, null, thresholds.cerMeanMax.getValue(it))
    } + listOf(
        GateCheck(CHECK_LINE_MATCH, CheckStatus.NOT_MEASURED, null, thresholds.lineMatchRateMin),
        GateCheck(CHECK_ABSTENTION, CheckStatus.NOT_MEASURED, null, thresholds.abstentionCorrectRateMin),
        GateCheck(CHECK_DURATION, CheckStatus.NOT_MEASURED, null, thresholds.durationP95MsMax.toDouble()),
        GateCheck(CHECK_PSS, CheckStatus.NOT_MEASURED, null, thresholds.peakPssDeltaKibMax.toDouble()),
    )

    private fun atMost(name: String, observed: Double?, limit: Double): GateCheck = GateCheck(
        name,
        when {
            observed == null -> CheckStatus.NOT_MEASURED
            observed <= limit -> CheckStatus.PASS
            else -> CheckStatus.FAIL
        },
        observed,
        limit,
    )

    private fun atLeast(name: String, observed: Double?, limit: Double): GateCheck = GateCheck(
        name,
        when {
            observed == null -> CheckStatus.NOT_MEASURED
            observed >= limit -> CheckStatus.PASS
            else -> CheckStatus.FAIL
        },
        observed,
        limit,
    )
}

/** A results file is evaluated only when it is complete, consistent and bound to this set and gate. */
internal object ResultsValidator {
    fun problems(engineId: String, binding: GateBinding, results: EngineResults): List<String> {
        val problems = mutableListOf<String>()
        if (results.engineId != engineId) problems += "results are for ${results.engineId}, not $engineId"
        if (results.manifestSha256 != binding.manifestSha256) problems += "results were measured on another fixture set"
        if (results.gateSha256 != binding.gateSha256) problems += "results were measured under another gate.json"
        val ids = results.images.map { it.id }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) problems += "duplicate fixtures: $duplicates"
        val expected = binding.fixtures.associateBy { it.id }
        val missing = expected.keys - ids.toSet()
        val unknown = ids.toSet() - expected.keys
        if (missing.isNotEmpty()) problems += "missing fixtures: ${missing.sorted()}"
        if (unknown.isNotEmpty()) problems += "unknown fixtures: ${unknown.sorted()}"
        results.images.forEach { image ->
            expected[image.id]?.let { entry -> problems += imageProblems(image, entry, binding.lineCounts[image.id]) }
        }
        return problems
    }

    private fun imageProblems(image: ImageResult, entry: ManifestEntry, truthLines: Int?): List<String> =
        bindingProblems(image, entry) + lineProblems(image, truthLines) + costProblems(image) + outcomeProblems(image)

    private fun bindingProblems(image: ImageResult, entry: ManifestEntry): List<String> = listOfNotNull(
        "${image.id}: category ${image.category.key} is not ${entry.category.key}"
            .takeIf { image.category != entry.category },
        "${image.id}: expected_abstain disagrees with truth".takeIf { image.expectedAbstain != entry.expectedAbstain },
    )

    private fun lineProblems(image: ImageResult, truthLines: Int?): List<String> {
        val cer = image.cer
        val total = image.linesTotal
        val matched = image.linesMatched
        val matchedOutOfRange = matched != null && (total == null || matched !in 0..total)
        return listOfNotNull(
            "${image.id}: CER $cer".takeIf { cer != null && !(cer.isFinite() && cer >= 0.0) },
            "${image.id}: lines_total $total, truth has $truthLines".takeIf { total != null && total != truthLines },
            "${image.id}: lines_matched $matched".takeIf { matchedOutOfRange },
        )
    }

    private fun costProblems(image: ImageResult): List<String> {
        val baseline = image.baselinePssKib
        val peak = image.peakPssKib
        return listOfNotNull(
            "${image.id}: negative duration".takeIf { image.durationMs != null && image.durationMs < 0 },
            "${image.id}: negative baseline PSS".takeIf { baseline != null && baseline < 0 },
            "${image.id}: peak PSS below baseline".takeIf { baseline != null && peak != null && peak < baseline },
        )
    }

    /** An abstention or failure produced no text: it must be scored as CER 1.0 with no matched lines. */
    private fun outcomeProblems(image: ImageResult): List<String> {
        val scoredAsText = (image.cer != null && image.cer != 1.0) || (image.linesMatched ?: 0) != 0
        return listOfNotNull(
            "${image.id}: no output, yet CER ${image.cer} and ${image.linesMatched} matched lines"
                .takeIf { image.outcome != RunOutcome.OUTPUT && scoredAsText },
        )
    }
}
