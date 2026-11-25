package org.openlife.eval.ocr.core

import kotlin.math.abs

/**
 * An engine the gate evaluates, pinned to the model version it was set for
 * (P2-04 review X-05): results measured with another version are stale.
 */
data class EnginePin(val id: String, val modelVersion: String)

/** The thresholds in eval/ocr/v1/gate.json (P2-04-R3, R4). */
data class GateThresholds(
    val lineMatchIou: Double,
    val cerMeanMax: Map<FixtureCategory, Double>,
    val lineMatchRateMin: Double,
    val abstentionCorrectRateMin: Double,
    val latinAbstentionsMax: Long,
    val timeoutsMax: Long,
    val durationP95MsMax: Long,
    val peakPssDeltaKibMax: Long,
    val engines: List<EnginePin>,
)

object GateCodec {
    const val SCHEMA = "openlife-ocr-gate/2"

    /** Any missing or mistyped threshold is a [JsonFormatException]: the gate never runs on a partial file. */
    fun decode(text: String): GateThresholds {
        val root = Json.parseObject(text)
        if (root.string("schema") != SCHEMA) jsonFail("unknown gate schema")
        val thresholds = root.obj("thresholds")
        val cer = thresholds.obj("cer_mean_max")
        if (cer.fields.keys != FixtureCategory.entries.map { it.key }.toSet()) {
            jsonFail("cer_mean_max must name exactly screenshot, receipt and letter")
        }
        val engines = root.array("engines").map {
            val engine = it as? JsonObject ?: jsonFail("engine is not an object")
            EnginePin(engine.string("id"), engine.string("model_version"))
        }
        if (engines.isEmpty() || engines.map { it.id }.toSet().size != engines.size) jsonFail("engine ids")
        return GateThresholds(
            lineMatchIou = root.double("line_match_iou"),
            cerMeanMax = FixtureCategory.entries.associateWith { cer.double(it.key) },
            lineMatchRateMin = thresholds.double("line_match_rate_min"),
            abstentionCorrectRateMin = thresholds.double("abstention_correct_rate_min"),
            latinAbstentionsMax = thresholds.long("latin_abstentions_max"),
            timeoutsMax = thresholds.long("timeouts_max"),
            durationP95MsMax = thresholds.long("duration_p95_ms_max"),
            peakPssDeltaKibMax = thresholds.long("peak_pss_delta_kib_max"),
            engines = engines,
        )
    }
}

enum class CheckStatus { PASS, FAIL, NOT_MEASURED }

data class GateCheck(val name: String, val status: CheckStatus, val observed: Double?, val limit: Double)

/**
 * PASS only when every check passed. NO_MEASUREMENT when there is no results
 * file or some check had nothing to measure; INVALID when the results file
 * is malformed, stale, or not bound to this set and gate, or when its
 * recorded scores disagree with the scores recomputed from its spans. Only
 * PASS permits an engine to be the default (P2-04-R3).
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

/** What a results file must be bound to: the committed set and gate, and every fixture's truth. */
data class GateBinding(
    val manifestSha256: String,
    val gateSha256: String,
    val fixtures: List<ManifestEntry>,
    val truths: Map<String, FixtureTruth>,
)

object OcrGate {
    const val CHECK_CER_PREFIX = "cer_mean_"
    const val CHECK_LINE_MATCH = "line_match_rate"
    const val CHECK_ABSTENTION = "abstention_correct_rate_non_latin"
    const val CHECK_LATIN_ABSTENTIONS = "abstentions_on_latin"
    const val CHECK_TIMEOUTS = "timeouts"
    const val CHECK_DURATION = "duration_p95_ms"
    const val CHECK_PSS = "peak_pss_delta_kib_max"

    /** Recorded CER may differ from the recomputed CER by at most this (JSON round trip of a double). */
    const val CER_TOLERANCE = 1e-9

    /** [resultsText] is the results file's content, or null when no such file exists. */
    fun evaluate(
        engineId: String,
        thresholds: GateThresholds,
        binding: GateBinding,
        resultsText: String?,
    ): GateVerdict {
        if (resultsText == null) {
            return notEvaluated(
                engineId,
                thresholds,
                GateOutcome.NO_MEASUREMENT,
                "no results file",
            )
        }
        val results = try {
            ResultsCodec.decode(resultsText)
        } catch (malformed: IllegalArgumentException) {
            return notEvaluated(engineId, thresholds, GateOutcome.INVALID, "malformed: ${malformed.message}")
        }
        val problems = ResultsValidator.problems(engineId, thresholds, binding, results)
        if (problems.isNotEmpty()) return GateVerdict(engineId, GateOutcome.INVALID, notMeasured(thresholds), problems)
        return verdict(engineId, thresholds, results.images)
    }

    /** One verdict for a failure that leaves nothing to evaluate, such as two results files for one engine. */
    fun notEvaluated(engineId: String, thresholds: GateThresholds, outcome: GateOutcome, problem: String) =
        GateVerdict(engineId, outcome, notMeasured(thresholds), listOf(problem))

    /** The thresholds applied to results that [evaluate] has already validated against the truth. */
    fun verdict(engineId: String, thresholds: GateThresholds, images: List<ImageResult>): GateVerdict {
        val checks = checks(thresholds, images)
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
        // Acting-owner ruling 2026-11-25 (review X-03): one abstention on a Latin fixture fails the
        // engine outright instead of being averaged into a category's CER.
        val latinAbstentions = if (latin.isEmpty()) null else latin.count { it.abstained }.toDouble()
        val timeouts = images.count { it.outcome == RunOutcome.TIMED_OUT }.toDouble()
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
            atMost(CHECK_LATIN_ABSTENTIONS, latinAbstentions, thresholds.latinAbstentionsMax.toDouble()),
            atMost(CHECK_TIMEOUTS, timeouts, thresholds.timeoutsMax.toDouble()),
            atMost(CHECK_DURATION, p95?.toDouble(), thresholds.durationP95MsMax.toDouble()),
            atMost(CHECK_PSS, pss?.toDouble(), thresholds.peakPssDeltaKibMax.toDouble()),
        )
    }

    private fun notMeasured(thresholds: GateThresholds): List<GateCheck> = FixtureCategory.entries.map {
        GateCheck(CHECK_CER_PREFIX + it.key, CheckStatus.NOT_MEASURED, null, thresholds.cerMeanMax.getValue(it))
    } + listOf(
        GateCheck(CHECK_LINE_MATCH, CheckStatus.NOT_MEASURED, null, thresholds.lineMatchRateMin),
        GateCheck(CHECK_ABSTENTION, CheckStatus.NOT_MEASURED, null, thresholds.abstentionCorrectRateMin),
        GateCheck(CHECK_LATIN_ABSTENTIONS, CheckStatus.NOT_MEASURED, null, thresholds.latinAbstentionsMax.toDouble()),
        GateCheck(CHECK_TIMEOUTS, CheckStatus.NOT_MEASURED, null, thresholds.timeoutsMax.toDouble()),
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

/**
 * A results file is evaluated only when it is complete, current, bound to
 * this set and gate, and when every recorded score equals the score
 * recomputed here from its spans and the truth (review X-04): reported
 * aggregates are never trusted.
 */
internal object ResultsValidator {
    private val commit = Regex("[0-9a-f]{40}")
    private val deviceFields = listOf("manufacturer", "model", "sdk", "abi")

    fun problems(
        engineId: String,
        thresholds: GateThresholds,
        binding: GateBinding,
        results: EngineResults,
    ): List<String> {
        val problems = provenanceProblems(engineId, thresholds, binding, results).toMutableList()
        val ids = results.images.map { it.id }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) problems += "duplicate fixtures: $duplicates"
        val expected = binding.fixtures.associateBy { it.id }
        val missing = expected.keys - ids.toSet()
        val unknown = ids.toSet() - expected.keys
        if (missing.isNotEmpty()) problems += "missing fixtures: ${missing.sorted()}"
        if (unknown.isNotEmpty()) problems += "unknown fixtures: ${unknown.sorted()}"
        results.images.forEach { image ->
            val entry = expected[image.id] ?: return@forEach
            val truth = binding.truths[image.id]
            if (truth == null) {
                problems += "${image.id}: no truth to score against"
            } else {
                problems += bindingProblems(image, entry) + scoreProblems(image, truth, thresholds.lineMatchIou) +
                    costProblems(image)
            }
        }
        return problems
    }

    private fun provenanceProblems(
        engineId: String,
        thresholds: GateThresholds,
        binding: GateBinding,
        results: EngineResults,
    ): List<String> {
        val pin = thresholds.engines.firstOrNull { it.id == engineId }
        val missingDevice = deviceFields.filter { results.device[it].isNullOrBlank() }
        return listOfNotNull(
            "results are for ${results.engineId}, not $engineId".takeIf { results.engineId != engineId },
            "gate.json pins no engine $engineId".takeIf { pin == null },
            "stale: measured with model ${results.modelVersion}, gate.json pins ${pin?.modelVersion}"
                .takeIf { pin != null && pin.modelVersion != results.modelVersion },
            "code_commit is not a full git commit id".takeIf { !commit.matches(results.codeCommit) },
            "device lacks $missingDevice".takeIf { missingDevice.isNotEmpty() },
            "results were measured on another fixture set".takeIf { results.manifestSha256 != binding.manifestSha256 },
            "results were measured under another gate.json".takeIf { results.gateSha256 != binding.gateSha256 },
        )
    }

    private fun bindingProblems(image: ImageResult, entry: ManifestEntry): List<String> = listOfNotNull(
        "${image.id}: category ${image.category.key} is not ${entry.category.key}"
            .takeIf { image.category != entry.category },
        "${image.id}: expected_abstain disagrees with truth".takeIf { image.expectedAbstain != entry.expectedAbstain },
    )

    /** Without output there are no spans; with or without, the recorded scores must be the recomputed ones. */
    private fun scoreProblems(image: ImageResult, truth: FixtureTruth, minIou: Double): List<String> {
        val score = OcrMetrics.score(truth, image.spans, minIou)
        val cer = image.cer
        return listOfNotNull(
            "${image.id}: ${image.outcome.key} yet ${image.spans.size} spans"
                .takeIf { image.outcome != RunOutcome.OUTPUT && image.spans.isNotEmpty() },
            "${image.id}: lines_total ${image.linesTotal}, truth has ${truth.lines.size}"
                .takeIf { image.linesTotal != truth.lines.size },
            "${image.id}: recorded CER $cer, recomputed ${score.cer}"
                .takeIf { cer == null || !cer.isFinite() || abs(cer - score.cer) > OcrGate.CER_TOLERANCE },
            "${image.id}: recorded ${image.linesMatched} matched lines, recomputed ${score.linesMatched}"
                .takeIf { image.linesMatched != score.linesMatched },
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
}
