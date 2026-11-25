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
        if (root.string("schema") != SCHEMA) throw JsonFormatException("unknown gate schema")
        val thresholds = root.obj("thresholds")
        val cer = thresholds.obj("cer_mean_max")
        if (cer.fields.keys != FixtureCategory.entries.map { it.key }.toSet()) {
            throw JsonFormatException("cer_mean_max must name exactly screenshot, receipt and letter")
        }
        return GateThresholds(
            lineMatchIou = root.double("line_match_iou"),
            cerMeanMax = FixtureCategory.entries.associateWith { cer.double(it.key) },
            lineMatchRateMin = thresholds.double("line_match_rate_min"),
            abstentionCorrectRateMin = thresholds.double("abstention_correct_rate_min"),
            durationP95MsMax = thresholds.long("duration_p95_ms_max"),
            peakPssDeltaKibMax = thresholds.long("peak_pss_delta_kib_max"),
            engines = root.array("engines").map {
                (it as? JsonString)?.value ?: throw JsonFormatException("engine id is not a string")
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
    fun evaluate(engineId: String, thresholds: GateThresholds, binding: GateBinding, resultsText: String?): GateVerdict =
        TODO("P2-04 GREEN")
}
