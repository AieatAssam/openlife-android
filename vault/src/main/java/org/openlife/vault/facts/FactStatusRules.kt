package org.openlife.vault.facts

/** C2 §5: the status table and its pure recomputation (C2-R22, C2-R23). */
object FactStatusRules {
    /** The status a revision starts in. */
    fun initial(origin: FactOrigin, isCandidates: Boolean): FactStatus = when {
        origin == FactOrigin.USER -> FactStatus.CONFIRMED
        isCandidates -> FactStatus.CLARIFICATION_NEEDED
        else -> FactStatus.UNREVIEWED
    }

    /** The status after [decision], or null when the §5 table has no such transition. */
    fun next(current: FactStatus, decision: ReviewDecision, initial: FactStatus, isCandidates: Boolean): FactStatus? =
        when (current) {
            FactStatus.UNREVIEWED -> when (decision) {
                ReviewDecision.CONFIRM -> FactStatus.CONFIRMED
                ReviewDecision.REJECT -> FactStatus.REJECTED
                else -> null
            }

            FactStatus.CONFIRMED -> FactStatus.REJECTED.takeIf { decision == ReviewDecision.REJECT }

            FactStatus.REJECTED -> when (decision) {
                ReviewDecision.CONFIRM -> FactStatus.CONFIRMED.takeUnless { isCandidates }
                ReviewDecision.REOPEN -> initial
                else -> null
            }

            FactStatus.CLARIFICATION_NEEDED -> FactStatus.REJECTED.takeIf { decision == ReviewDecision.REJECT }

            else -> null
        }

    /** C2-R22: the status follows from the initial status, the latest review event and staleness alone. */
    fun recompute(initial: FactStatus, latestDecision: ReviewDecision?, staleReason: StaleReason?): FactStatus = when {
        staleReason != null -> FactStatus.STALE
        latestDecision == ReviewDecision.CONFIRM -> FactStatus.CONFIRMED
        latestDecision == ReviewDecision.REJECT -> FactStatus.REJECTED
        else -> initial
    }
}
