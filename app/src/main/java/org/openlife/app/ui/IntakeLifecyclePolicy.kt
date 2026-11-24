package org.openlife.app.ui

/** What an intake ViewModel does with its stage and slot when its screen goes away. */
internal enum class ClearedAction { RELEASE_SLOT, CANCEL_ABANDONED, LEAVE_TO_SAVE }

/**
 * P1-19-R7 (A-03): decisions for an intake screen that goes away. A save in
 * flight is left to finish on the application scope; once it ends with no
 * screen, an outcome that kept the stage is cancelled as abandoned.
 */
internal object IntakeLifecyclePolicy {
    fun onCleared(state: IntakeUiState): ClearedAction = when (state) {
        is IntakeUiState.Saving -> ClearedAction.LEAVE_TO_SAVE
        is IntakeUiState.Saved, is IntakeUiState.Duplicate, IntakeUiState.Cancelled -> ClearedAction.RELEASE_SLOT
        else -> ClearedAction.CANCEL_ABANDONED
    }

    fun abandonsAfterSave(outcome: IntakeUiState): Boolean = when (outcome) {
        is IntakeUiState.Saved, is IntakeUiState.Duplicate, IntakeUiState.Cancelled -> false
        else -> true
    }
}
