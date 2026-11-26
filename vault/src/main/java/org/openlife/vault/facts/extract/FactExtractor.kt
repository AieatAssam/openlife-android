package org.openlife.vault.facts.extract

/** The rule stages, in precedence order (ADR-0008 §1). */
internal enum class RuleStage { URL, EMAIL, DATE, TIME, MONEY, PHONE }

/** Runs the C2 rules over the read text of eligible spans. RED stub. */
object FactExtractor {
    /** `fact_extraction_runs.rulesVersion`, in kind order (C2 §4.6). */
    const val RULES_VERSION: String =
        "rules-date-v1,rules-time-v1,rules-datetime-v1,rules-money-v1,rules-url-v1,rules-email-v1,rules-phone-v1"

    fun extract(spans: List<ExtractionSpan>, deadline: ExtractionDeadline = ExtractionDeadline.NONE): ExtractionResult =
        extract(spans, deadline, RuleStage.entries.toSet())

    internal fun extract(
        spans: List<ExtractionSpan>,
        deadline: ExtractionDeadline,
        stages: Set<RuleStage>,
    ): ExtractionResult = ExtractionResult(emptyList(), emptyList())
}

object DateExtractor {
    const val RULE_ID = "rules-date"
    const val RULES_VERSION = "v1"
}

object TimeExtractor {
    const val RULE_ID = "rules-time"
    const val RULES_VERSION = "v1"
}

object DateTimeExtractor {
    const val RULE_ID = "rules-datetime"
    const val RULES_VERSION = "v1"
}

object MoneyExtractor {
    const val RULE_ID = "rules-money"
    const val RULES_VERSION = "v1"
}

object UrlExtractor {
    const val RULE_ID = "rules-url"
    const val RULES_VERSION = "v1"
}

object EmailExtractor {
    const val RULE_ID = "rules-email"
    const val RULES_VERSION = "v1"
}

object PhoneExtractor {
    const val RULE_ID = "rules-phone"
    const val RULES_VERSION = "v1"
}
