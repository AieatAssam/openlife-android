package org.openlife.eval.ocr.core

/** What one script policy cost on one image (P2-04-R5, V-15). */
data class PolicyCost(val latinLinesLost: Int, val nonLatinAccepted: Int)

/**
 * The two policies side by side for one image.
 *
 * - Whole revision (production today, `OcrRunStateMachine.decideOutput` and
 *   the engines' `extract`): one unsupported span discards every span.
 * - Per span (the VS-3 alternative, evaluated offline only): each span is
 *   kept or dropped on its own script status.
 *
 * Truth lines are paired with the engine's raw spans by [OcrMetrics.matchLines].
 * A Latin truth line is "lost" when its paired span is dropped. A mixed or
 * non-Latin truth line is "wrongly accepted" when its paired span is kept.
 * Unpaired truth lines are recognition misses, counted in [unmatchedLines]
 * and in neither cost.
 */
data class ScriptPolicyComparison(
    val latinLines: Int,
    val nonLatinLines: Int,
    val unmatchedLines: Int,
    val wholeRevision: PolicyCost,
    val perSpan: PolicyCost,
)

object ScriptPolicyCost {
    /**
     * [wholeRevisionAbstained] is the production outcome for this image.
     * [spanIsUnsupported] is the production span classifier, passed in so the
     * rule is never copied into the evaluation code.
     */
    fun compare(
        truth: List<TruthLine>,
        rawSpans: List<SpanObservation>,
        wholeRevisionAbstained: Boolean,
        minIou: Double,
        spanIsUnsupported: (String) -> Boolean,
    ): ScriptPolicyComparison = TODO("P2-04 GREEN")
}
