package org.openlife.app.settings

import androidx.annotation.StringRes
import org.openlife.app.R
import org.openlife.app.VaultFailureDiagnostics
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

/** One diagnostics line: a group, a counter ([SourceState], [RecoveryCount] or [VaultUnavailableCause]), its count. */
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
    fun rows(): List<DiagnosticRow> = stateRows() + recoveryRows() + failureRows()

    private fun stateRows(): List<DiagnosticRow> = sourcesByState?.let { counts ->
        SourceState.entries.map { DiagnosticRow(DiagnosticGroup.SOURCES_BY_STATE, it, counts[it] ?: 0) }
    }.orEmpty()

    private fun recoveryRows(): List<DiagnosticRow> = lastRecovery?.let { report ->
        RecoveryCount.entries.map { DiagnosticRow(DiagnosticGroup.LAST_RECOVERY, it, it.of(report)) }
    }.orEmpty()

    private fun failureRows(): List<DiagnosticRow> = VaultUnavailableCause.entries.mapNotNull { cause ->
        openFailures[cause]?.takeIf { it > 0 }?.let { DiagnosticRow(DiagnosticGroup.VAULT_OPEN_FAILURES, cause, it) }
    }
}

private fun RecoveryCount.of(report: RecoveryReport): Int = when (this) {
    RecoveryCount.CLEANED_STAGED -> report.cleanedStaged
    RecoveryCount.CONFIRMED_READY -> report.confirmedReady
    RecoveryCount.MARKED_CORRUPT -> report.markedCorrupt
    RecoveryCount.RESUMED_DELETIONS -> report.resumedDeletions
    RecoveryCount.REMOVED_ORPHAN_FILES -> report.removedOrphanFiles
    RecoveryCount.MARKED_STALE_OCR_REVISIONS -> report.markedStaleOcrRevisions
    RecoveryCount.UNRECOGNISED_ROWS -> report.unrecognisedRows
    RecoveryCount.KEPT_BLOBS_WITHOUT_ROWS -> report.keptBlobsWithoutRows
}

/** The resource naming a diagnostics counter. */
@StringRes
internal fun diagnosticLabel(item: Enum<*>): Int = when (item) {
    is SourceState -> stateLabel(item)
    is RecoveryCount -> recoveryLabel(item)
    is VaultUnavailableCause -> VaultFailureDiagnostics.causeLabel(item)
    else -> error("not a diagnostics counter: ${item::class.simpleName}")
}

@StringRes
private fun stateLabel(state: SourceState): Int = when (state) {
    SourceState.STAGED -> R.string.diagnostics_state_staged
    SourceState.READY -> R.string.diagnostics_state_ready
    SourceState.DELETING -> R.string.diagnostics_state_deleting
    SourceState.CORRUPT -> R.string.diagnostics_state_corrupt
    SourceState.UNRECOGNISED -> R.string.diagnostics_state_unrecognised
}

@StringRes
private fun recoveryLabel(count: RecoveryCount): Int = when (count) {
    RecoveryCount.CLEANED_STAGED -> R.string.diagnostics_recovery_cleaned_staged
    RecoveryCount.CONFIRMED_READY -> R.string.diagnostics_recovery_confirmed_ready
    RecoveryCount.MARKED_CORRUPT -> R.string.diagnostics_recovery_marked_corrupt
    RecoveryCount.RESUMED_DELETIONS -> R.string.diagnostics_recovery_resumed_deletions
    RecoveryCount.REMOVED_ORPHAN_FILES -> R.string.diagnostics_recovery_removed_orphans
    RecoveryCount.MARKED_STALE_OCR_REVISIONS -> R.string.diagnostics_recovery_stale_ocr
    RecoveryCount.UNRECOGNISED_ROWS -> R.string.diagnostics_recovery_unrecognised_rows
    RecoveryCount.KEPT_BLOBS_WITHOUT_ROWS -> R.string.diagnostics_recovery_kept_blobs
}
