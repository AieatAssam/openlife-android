package org.openlife.vault.repository

/** Summary of one [RecoveryRepository.recover] pass, for diagnostics only. */
data class RecoveryReport(
    val cleanedStaged: Int = 0,
    val confirmedReady: Int = 0,
    val markedCorrupt: Int = 0,
    val resumedDeletions: Int = 0,
    val removedOrphanFiles: Int = 0,
    val markedStaleOcrRevisions: Int = 0,
)
