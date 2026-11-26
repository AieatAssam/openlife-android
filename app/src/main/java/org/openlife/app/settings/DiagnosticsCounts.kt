package org.openlife.app.settings

import org.openlife.vault.model.SourceState
import org.openlife.vault.repository.RecoveryReport
import org.openlife.vault.storage.VaultUnavailableCause

/** The counters of the last startup recovery pass, by name (P1-08-R4). */
enum class RecoveryCount {
    CLEANED_STAGED,
    CONFIRMED_READY,
    MARKED_CORRUPT,
    RESUMED_DELETIONS,
    REMOVED_ORPHAN_FILES,
    MARKED_STALE_OCR_REVISIONS,
    UNRECOGNISED_ROWS,
    KEPT_BLOBS_WITHOUT_ROWS,
}

enum class DiagnosticGroup { SOURCES_BY_STATE, LAST_RECOVERY, VAULT_OPEN_FAILURES }

/** One diagnostics line: a group, a named counter ([SourceState], [RecoveryCount] or [VaultUnavailableCause]) and its count. */
data class DiagnosticRow(val group: DiagnosticGroup, val item: Enum<*>, val count: Int)

/**
 * P1-08-R4: About's diagnostics, counts only. Built from enums and integers,
 * so no identifier, digest, name or content can reach it. [sourcesByState]
 * and [lastRecovery] are null while the vault is not open.
 */
data class DiagnosticsCounts(
    val sourcesByState: Map<SourceState, Int>?,
    val lastRecovery: RecoveryReport?,
    val openFailures: Map<VaultUnavailableCause, Int>,
) {
    fun rows(): List<DiagnosticRow> = TODO("P1-08 RED")
}
