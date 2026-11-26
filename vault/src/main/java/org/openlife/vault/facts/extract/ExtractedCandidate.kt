package org.openlife.vault.facts.extract

/**
 * The fact kinds the C2 rules extract (ADR-0008). A plain value inside the
 * extractor package: P3-02 owns the persisted kind column, and P3-04 maps
 * this enum onto it.
 */
enum class ExtractedKind {
    DATE,
    TIME,
    DATETIME,
    MONEY,
    URL,
    EMAIL,
    PHONE,
}

/**
 * The read text of one eligible span (C2 §1). The caller (P3-04) chooses the
 * eligible spans and the text actually read: the current correction's text
 * when [correctionId] is set, the span's own text otherwise.
 */
data class ExtractionSpan(val spanId: String, val correctionId: String?, val ordinal: Int, val text: String)

/**
 * A neighbouring span inspected for a currency code (ADR-0008 §5.4): one
 * `CURRENCY_CONTEXT` evidence row. The offsets locate the code when one was
 * found there and are both null otherwise.
 */
data class CurrencyContext(val spanId: String, val correctionId: String?, val codeStart: Int?, val codeEnd: Int?)

/**
 * One extractor output before persistence (P3-03-R2, C2-R26).
 *
 * Exactly one of [canonicalValue] (a single value) and [ambiguity] (2 to 64
 * candidate values, status `CLARIFICATION_NEEDED`) is set. [rawText] is
 * always `text.substring(startOffset, endOffset)` of the span read.
 */
data class ExtractedCandidate(
    val kind: ExtractedKind,
    val rawText: String,
    val spanId: String,
    val correctionId: String?,
    val startOffset: Int,
    val endOffset: Int,
    val canonicalValue: FactValue?,
    val ambiguity: List<FactValue>,
    val ruleId: String,
    val ruleVersion: String,
    val locale: String,
    val currencyContext: List<CurrencyContext>,
) {
    init {
        require((canonicalValue == null) != ambiguity.isEmpty()) { "Exactly one of value and candidates" }
        require(ambiguity.isEmpty() || ambiguity.size in MIN_CANDIDATES..MAX_CANDIDATES) { "Candidate count" }
        require(startOffset in 0 until endOffset) { "Offsets must enclose at least one character" }
    }

    /** True when the user must choose among [ambiguity] (initial status `CLARIFICATION_NEEDED`). */
    val needsClarification: Boolean get() = ambiguity.isNotEmpty()

    /** Canonical JSON (ADR-0008 §2): equal values are equal bytes. */
    val valueJson: String get() = CanonicalJson.of(canonicalValue, ambiguity)

    /** ADR-0008 §3; a pure function of [valueJson]. */
    val canonicalText: String get() = canonicalValue?.canonicalText() ?: ambiguity.joinToString(" | ") {
        it.canonicalText()
    }

    /** `rules-<kind>`, plus `+correction` when any evidence was read from a correction (C2-R9). */
    val methodId: String get() = if (correctionId != null || currencyContext.any { it.correctionId != null }) {
        "$ruleId+correction"
    } else {
        ruleId
    }

    companion object {
        const val MIN_CANDIDATES = 2
        const val MAX_CANDIDATES = 64
    }
}

/** Why a recognised token was abstained on. Carries no text (C2-R27). */
enum class AbstentionReason {
    INVALID_VALUE,
    NO_YEAR,
    YEAR_MONTH_ONLY,
    RANGE,
    WEEKDAY_MISMATCH,
    ZONE_OR_ANNOTATION,
    ATTACHED_CHARACTERS,
    NON_ASCII_DIGIT,
    AMBIGUOUS_FORM,
    MARKER_CONFLICT,
    SIGN_OR_CREDIT,
    LIMIT,
    USERINFO,
    HOST,
    SCHEME,
    DOTTED_QUAD,
    CARD_LIKE,
    OVERLAPS_CLAIM,
}

/**
 * A token a rule recognised and did not emit (ADR-0008 §1). Its characters
 * are claimed: no lower-precedence rule reads them.
 */
data class Abstention(
    val kind: ExtractedKind,
    val spanId: String,
    val startOffset: Int,
    val endOffset: Int,
    val reason: AbstentionReason,
)

data class ExtractionResult(val candidates: List<ExtractedCandidate>, val abstentions: List<Abstention>)

/**
 * Cooperative deadline (C2-R29). [check] throws to stop extraction; it is
 * called between spans and at least every 1,024 characters inside a span.
 */
fun interface ExtractionDeadline {
    fun check()

    companion object {
        val NONE = ExtractionDeadline { }
    }
}
