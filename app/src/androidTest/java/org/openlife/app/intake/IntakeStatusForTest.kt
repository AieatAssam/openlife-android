package org.openlife.app.intake

import org.openlife.app.R
import org.openlife.app.ui.IntakeUiState

/**
 * English status strings for the instrumented intake tests (moved out of the
 * shipping IntakeActivity by P1-19-R20). Rejections use the same resource the
 * screen shows (C0-R12).
 */
fun IntakeActivity.currentStatusForTest(): String = describeForTest(this, currentStateForTest())

internal fun describeForTest(activity: IntakeActivity, state: IntakeUiState): String = when (state) {
    IntakeUiState.Preparing -> "Preparing"

    is IntakeUiState.Preview -> "Prepared ${state.format} ${state.width}x${state.height}"

    is IntakeUiState.Saving -> "Saving"

    is IntakeUiState.Saved -> "Saved on this device"

    is IntakeUiState.Duplicate -> "Not imported again"

    // C0-R12: a vanished provider must prompt reselection. Report the same
    // user-visible copy the screen shows, not the enum name.
    is IntakeUiState.Rejected -> activity.getString(
        R.string.intake_not_imported,
        activity.getString(state.message.stringRes),
    )

    IntakeUiState.Busy -> "Another import is already in progress."

    IntakeUiState.Failed -> "Import failed"

    IntakeUiState.StorageUnavailable -> "Storage unavailable"

    is IntakeUiState.VaultUnavailable -> "Vault unavailable: ${state.cause.name}"

    IntakeUiState.Cancelled -> "Cancelled"
}
