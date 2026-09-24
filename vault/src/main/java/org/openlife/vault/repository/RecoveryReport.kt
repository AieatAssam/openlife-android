package org.openlife.vault.repository

/** Summary of one [RecoveryRepository.recover] pass, for diagnostics only. */
data class RecoveryReport(
    val cleanedStaged: Int = 0,
    /** READY rows whose blob exists with consistent framing; not an authentication result. */
    val confirmedReady: Int = 0,
    val markedCorrupt: Int = 0,
    val resumedDeletions: Int = 0,
    val removedOrphanFiles: Int = 0,
    val markedStaleOcrRevisions: Int = 0,
)

/** Outcome of an explicit "Verify all items" pass (P1-14-R3). */
data class VerifyReport(val verified: Int = 0, val markedCorrupt: Int = 0, val transient: Int = 0)
