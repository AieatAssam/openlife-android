package org.openlife.eval.ocr.core

/** How the production `extract` call ended for one image. */
enum class RunOutcome(val key: String) {
    OUTPUT("output"),
    ABSTAINED("abstained"),
    FAILED("failed"),
    ;

    companion object {
        fun of(key: String): RunOutcome =
            entries.firstOrNull { it.key == key } ?: throw JsonFormatException("unknown outcome $key")
    }
}

/**
 * One image's measurements (P2-04-R2). Every measured number is nullable:
 * null means "not measured" and the gate fails closed on it. Nothing here is
 * ever estimated.
 */
data class ImageResult(
    val id: String,
    val category: FixtureCategory,
    val expectedAbstain: Boolean,
    val outcome: RunOutcome,
    val cer: Double?,
    val linesTotal: Int?,
    val linesMatched: Int?,
    val durationMs: Long?,
    val baselinePssKib: Long?,
    val peakPssKib: Long?,
    val policy: ScriptPolicyComparison?,
    val spans: List<SpanObservation>,
    val rawSpans: List<SpanObservation>,
) {
    val abstained: Boolean get() = outcome == RunOutcome.ABSTAINED
}

data class EngineResults(
    val engineId: String,
    val modelVersion: String,
    val manifestSha256: String,
    val gateSha256: String,
    val runDate: String,
    val device: Map<String, String>,
    val images: List<ImageResult>,
)

object ResultsCodec {
    const val SCHEMA = "openlife-ocr-eval-results/1"

    fun encode(results: EngineResults): String = Json.write(
        jsonObject(
            "schema" to SCHEMA.json(),
            "engine" to jsonObject(
                "id" to results.engineId.json(),
                "model_version" to results.modelVersion.json(),
            ),
            "manifest_sha256" to results.manifestSha256.json(),
            "gate_sha256" to results.gateSha256.json(),
            "run_date" to results.runDate.json(),
            "device" to JsonObject(results.device.mapValues { it.value.json() }),
            "images" to jsonArray(results.images.map(::imageJson)),
        ),
    )

    fun decode(text: String): EngineResults {
        val root = Json.parseObject(text)
        if (root.string("schema") != SCHEMA) throw JsonFormatException("unknown results schema")
        val engine = root.obj("engine")
        return EngineResults(
            engineId = engine.string("id"),
            modelVersion = engine.string("model_version"),
            manifestSha256 = root.string("manifest_sha256"),
            gateSha256 = root.string("gate_sha256"),
            runDate = root.string("run_date"),
            device = root.obj("device").fields.mapValues {
                (it.value as? JsonString)?.value ?: throw JsonFormatException("device field ${it.key}")
            },
            images = root.array("images").map {
                decodeImage(it as? JsonObject ?: throw JsonFormatException("image is not an object"))
            },
        )
    }

    private fun imageJson(image: ImageResult): JsonObject = jsonObject(
        "id" to image.id.json(),
        "category" to image.category.key.json(),
        "expected_abstain" to image.expectedAbstain.json(),
        "outcome" to image.outcome.key.json(),
        "cer" to image.cer.jsonOrNull(),
        "lines_total" to image.linesTotal.jsonOrNull(),
        "lines_matched" to image.linesMatched.jsonOrNull(),
        "duration_ms" to image.durationMs.jsonOrNull(),
        "baseline_pss_kib" to image.baselinePssKib.jsonOrNull(),
        "peak_pss_kib" to image.peakPssKib.jsonOrNull(),
        "policy" to (image.policy?.let(::policyJson) ?: JsonNull),
        "spans" to spansJson(image.spans),
        "raw_spans" to spansJson(image.rawSpans),
    )

    private fun decodeImage(image: JsonObject): ImageResult = ImageResult(
        id = image.string("id"),
        category = FixtureCategory.of(image.string("category")),
        expectedAbstain = image.bool("expected_abstain"),
        outcome = RunOutcome.of(image.string("outcome")),
        cer = image.optDouble("cer"),
        linesTotal = image.optInt("lines_total"),
        linesMatched = image.optInt("lines_matched"),
        durationMs = image.optLong("duration_ms"),
        baselinePssKib = image.optLong("baseline_pss_kib"),
        peakPssKib = image.optLong("peak_pss_kib"),
        policy = image.optObj("policy")?.let(::decodePolicy),
        spans = decodeSpans(image.array("spans")),
        rawSpans = decodeSpans(image.array("raw_spans")),
    )

    private fun policyJson(policy: ScriptPolicyComparison): JsonObject = jsonObject(
        "latin_lines" to policy.latinLines.json(),
        "non_latin_lines" to policy.nonLatinLines.json(),
        "unmatched_lines" to policy.unmatchedLines.json(),
        "whole_revision" to costJson(policy.wholeRevision),
        "per_span" to costJson(policy.perSpan),
    )

    private fun costJson(cost: PolicyCost): JsonObject = jsonObject(
        "latin_lines_lost" to cost.latinLinesLost.json(),
        "non_latin_accepted" to cost.nonLatinAccepted.json(),
    )

    private fun decodePolicy(policy: JsonObject): ScriptPolicyComparison = ScriptPolicyComparison(
        latinLines = policy.int("latin_lines"),
        nonLatinLines = policy.int("non_latin_lines"),
        unmatchedLines = policy.int("unmatched_lines"),
        wholeRevision = decodeCost(policy.obj("whole_revision")),
        perSpan = decodeCost(policy.obj("per_span")),
    )

    private fun decodeCost(cost: JsonObject): PolicyCost =
        PolicyCost(cost.int("latin_lines_lost"), cost.int("non_latin_accepted"))

    private fun spansJson(spans: List<SpanObservation>): JsonArray = jsonArray(
        spans.map { jsonObject("text" to it.text.json(), "box" to (it.box?.toJson() ?: JsonNull)) },
    )

    private fun decodeSpans(items: List<JsonValue>): List<SpanObservation> = items.map {
        val span = it as? JsonObject ?: throw JsonFormatException("span is not an object")
        val box = span.value("box").let { value -> if (value == JsonNull) null else Box.fromJson(value) }
        SpanObservation(span.string("text"), box)
    }
}
