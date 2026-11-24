package org.openlife.app.ui

/** RED stub (P1-19-R7). */
internal enum class ClearedAction { RELEASE_SLOT, CANCEL_ABANDONED, LEAVE_TO_SAVE }

/** RED stub (P1-19-R7). */
internal object IntakeLifecyclePolicy {
    fun onCleared(state: IntakeUiState): ClearedAction = TODO("P1-19-R7 $state")

    fun abandonsAfterSave(outcome: IntakeUiState): Boolean = TODO("P1-19-R7 $outcome")
}
