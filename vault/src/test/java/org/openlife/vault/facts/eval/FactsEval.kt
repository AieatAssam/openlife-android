package org.openlife.vault.facts.eval

/** A count of hits out of a denominator. A zero denominator has no value and fails the gate. */
internal data class Rate(val hits: Int, val total: Int) {
    val value: Double? get() = if (total == 0) null else hits.toDouble() / total
}

/**
 * The measured facts report (P3-06-R1). Precision is keyed by gated family (DATE covers DATE, TIME
 * and DATETIME, as Q-3 requires). Recall is keyed the same way.
 */
internal data class FactsReport(
    val precision: Map<String, Rate>,
    val recall: Map<String, Rate>,
    val ambiguousDmFlagged: Rate,
    val bareCurrencyInferred: Int,
    val wrongActionableDates: Int,
)

/**
 * RED stub (P3-06). It measures nothing yet, so every gate rate has no denominator. GREEN replaces
 * it with the scorer over eval/facts/v1/text.tsv.
 */
internal object FactsEval {
    fun measure(): FactsReport = FactsReport(
        precision = emptyMap(),
        recall = emptyMap(),
        ambiguousDmFlagged = Rate(0, 0),
        bareCurrencyInferred = 0,
        wrongActionableDates = 0,
    )
}
