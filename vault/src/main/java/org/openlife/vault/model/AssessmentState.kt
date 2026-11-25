package org.openlife.vault.model

/**
 * Communication assessment state of a Source (design §6, P2-07-R1). Every
 * Source carries one; it defaults to [NOT_ASSESSED]. This build runs no
 * assessment: nothing in C1 writes [NOT_APPLICABLE] (a future C10 rule) or
 * [NO_INDICATORS_FOUND] (P2-07-R2). [NO_INDICATORS_FOUND] would mean only
 * that a completed assessment found no indicators, never that the item is
 * safe.
 */
enum class AssessmentState {
    NOT_APPLICABLE,
    NOT_ASSESSED,
    INCONCLUSIVE,
    CONCERNS_FOUND,
    NO_INDICATORS_FOUND,

    /** A stored value this build does not know (P1-18-R14); never written back. */
    UNRECOGNISED,
}
