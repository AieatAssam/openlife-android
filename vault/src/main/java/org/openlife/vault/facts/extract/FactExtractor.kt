package org.openlife.vault.facts.extract

/** The rule stages, in precedence order (ADR-0008 §1). */
internal enum class RuleStage { URL, EMAIL, DATE, TIME, MONEY, PHONE }

/**
 * Runs the C2 rules over the read text of eligible spans (ADR-0008). Pure
 * and deterministic: the same spans give the same result, and nothing is
 * logged, resolved or fetched. Rules run in precedence order over a shared
 * claim mask, so a lower rule never reads characters a higher rule
 * recognised, abstained ones included.
 */
object FactExtractor {
    /** `fact_extraction_runs.rulesVersion`, in kind order (C2 §4.6). */
    const val RULES_VERSION: String =
        "rules-date-v1,rules-time-v1,rules-datetime-v1,rules-money-v1,rules-url-v1,rules-email-v1,rules-phone-v1"

    private val NUMERIC = setOf(RuleStage.DATE, RuleStage.TIME, RuleStage.MONEY, RuleStage.PHONE)

    fun extract(spans: List<ExtractionSpan>, deadline: ExtractionDeadline = ExtractionDeadline.NONE): ExtractionResult =
        extract(spans, deadline, RuleStage.entries.toSet())

    internal fun extract(
        spans: List<ExtractionSpan>,
        deadline: ExtractionDeadline,
        stages: Set<RuleStage>,
    ): ExtractionResult {
        val context = CurrencyContextIndex(spans)
        val candidates = mutableListOf<ExtractedCandidate>()
        val abstentions = mutableListOf<Abstention>()
        for (span in spans) {
            deadline.check()
            val line = LineScan(span, deadline)
            run(line, stages, context)
            candidates += line.candidates.sortedBy { it.startOffset }
            abstentions += line.abstentions.sortedBy { it.startOffset }
        }
        return ExtractionResult(candidates, abstentions)
    }

    private fun run(line: LineScan, stages: Set<RuleStage>, context: CurrencyContextIndex) {
        stage(line, RuleStage.URL in stages) { UrlExtractor.scan(line) }
        stage(line, RuleStage.EMAIL in stages) { EmailExtractor.scan(line) }
        stage(line, stages.any { it in NUMERIC }) { NonAsciiDigitGuard.scan(line) }
        var dates = emptyList<DateToken>()
        var times = emptyList<TimeToken>()
        stage(line, RuleStage.DATE in stages) { dates = DateExtractor.scan(line) }
        stage(line, RuleStage.TIME in stages) { times = TimeExtractor.scan(line) }
        DateTimeExtractor.emit(line, dates, times)
        stage(line, RuleStage.PHONE in stages) { PhoneExtractor.scanPlusLed(line) }
        stage(line, RuleStage.MONEY in stages) { MoneyExtractor.scan(line, context) }
        stage(line, RuleStage.PHONE in stages) { PhoneExtractor.scan(line) }
    }

    private inline fun stage(line: LineScan, enabled: Boolean, body: () -> Unit) {
        if (!enabled) return
        line.beginStage()
        body()
    }
}
