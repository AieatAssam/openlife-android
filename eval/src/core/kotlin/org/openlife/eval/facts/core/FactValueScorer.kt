package org.openlife.eval.facts.core

/**
 * One expected unit of a row (ADR-0008 truth): a definite value has one reading, a clarification has several.
 * [kind] is the extractor kind name; [readings] are the canonical texts.
 */
data class TruthUnit(val kind: String, val raw: String, val readings: Set<String>, val clarification: Boolean)

/**
 * One emitted candidate reduced to what scoring reads: kind, raw slice, readings, and whether it is a
 * clarification.
 */
data class EmittedValue(val kind: String, val raw: String, val readings: Set<String>, val clarification: Boolean)

/** How one emitted candidate compares with the truth units of its row. */
enum class ValueOutcome { EXACT, SUBSET, SUPERSET, WRONG_VALUE, UNEXPECTED }

/** How one truth unit was recovered: still open (abstained or silent), exact, or wrong. */
enum class TruthStatus { OPEN, EXACT, WRONG }

/**
 * [rawDiffers] is set when the matched candidate's raw text is not the truth raw text. It is informational:
 * it never changes [outcome].
 */
data class ScoredValue(val outcome: ValueOutcome, val rawDiffers: Boolean)

/** Per-candidate outcomes, in emitted order, and per-truth-unit statuses, in truth order. */
data class ScoredRow(val values: List<ScoredValue>, val units: List<TruthStatus>)

/**
 * P3-06 follow-up, finding F1. Correctness of an image candidate is kind and canonical value only. OCR may drop
 * or insert a space in the raw text, so raw text is never the key for a correct value.
 *
 * Pairing, per emitted candidate in order, against the still-open truth units:
 * 1. Same kind, same clarification flag and same readings: EXACT.
 * 2. Else same kind and the identical raw text: a wrong value (SUBSET, SUPERSET or WRONG_VALUE by the readings).
 * 3. Else same kind and raw text equal once whitespace is removed: a wrong value, as in 2 (raw differs).
 * 4. Else UNEXPECTED. A truth unit is paired at most once.
 */
object FactValueScorer {
    fun score(truth: List<TruthUnit>, emitted: List<EmittedValue>): ScoredRow {
        val status = Array(truth.size) { TruthStatus.OPEN }
        val values = emitted.map { candidate ->
            val exact = truth.indices.firstOrNull { i ->
                status[i] == TruthStatus.OPEN && sameValue(truth[i], candidate)
            }
            if (exact != null) {
                status[exact] = TruthStatus.EXACT
                ScoredValue(ValueOutcome.EXACT, rawDiffers = truth[exact].raw != candidate.raw)
            } else {
                val wrong = truth.indices.firstOrNull { i ->
                    status[i] == TruthStatus.OPEN && sameRaw(truth[i], candidate)
                }
                if (wrong == null) {
                    ScoredValue(ValueOutcome.UNEXPECTED, rawDiffers = false)
                } else {
                    status[wrong] = TruthStatus.WRONG
                    ScoredValue(wrongOutcome(truth[wrong], candidate), rawDiffers = truth[wrong].raw != candidate.raw)
                }
            }
        }
        return ScoredRow(values, status.toList())
    }

    private fun sameValue(unit: TruthUnit, candidate: EmittedValue): Boolean {
        if (unit.kind != candidate.kind || unit.clarification != candidate.clarification) return false
        return unit.readings == candidate.readings
    }

    /** Same kind and raw text, exactly or with every whitespace character removed from both sides. */
    private fun sameRaw(unit: TruthUnit, candidate: EmittedValue): Boolean {
        if (unit.kind != candidate.kind) return false
        return unit.raw == candidate.raw || squeezed(unit.raw) == squeezed(candidate.raw)
    }

    private fun squeezed(text: String): String = text.filterNot { it.isWhitespace() }

    private fun wrongOutcome(unit: TruthUnit, candidate: EmittedValue): ValueOutcome = when {
        !candidate.clarification || !unit.clarification -> ValueOutcome.WRONG_VALUE
        candidate.readings.containsAll(unit.readings) -> ValueOutcome.SUPERSET
        unit.readings.containsAll(candidate.readings) -> ValueOutcome.SUBSET
        else -> ValueOutcome.WRONG_VALUE
    }
}
