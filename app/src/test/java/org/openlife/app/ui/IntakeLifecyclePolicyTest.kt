package org.openlife.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.storage.VaultUnavailableCause
import java.util.UUID

/**
 * A-03 (P1-19-R7): Save runs on the application scope, so leaving the
 * intake screen mid-save neither cancels the save nor cancels its stage.
 * Once the save finishes with no screen to show it, a failure that kept the
 * stage is handed to abandoned-import cleanup so no slot stays held.
 */
class IntakeLifecyclePolicyTest {
    private val id = UUID.randomUUID()

    @Test
    fun saveInFlightIsDecidedAndNeverCancelledOnClear() {
        assertEquals(ClearedAction.LEAVE_TO_SAVE, IntakeLifecyclePolicy.onCleared(IntakeUiState.Saving(id)))
        assertEquals(ClearedAction.RELEASE_SLOT, IntakeLifecyclePolicy.onCleared(IntakeUiState.Saved(id)))
        assertEquals(ClearedAction.RELEASE_SLOT, IntakeLifecyclePolicy.onCleared(IntakeUiState.Duplicate(id)))
        assertEquals(ClearedAction.RELEASE_SLOT, IntakeLifecyclePolicy.onCleared(IntakeUiState.Cancelled))
        assertEquals(ClearedAction.CANCEL_ABANDONED, IntakeLifecyclePolicy.onCleared(IntakeUiState.Preparing))
        assertEquals(ClearedAction.CANCEL_ABANDONED, IntakeLifecyclePolicy.onCleared(IntakeUiState.Failed))
    }

    @Test
    fun aSaveThatEndsWithoutAScreenNeverLeavesTheSlotHeld() {
        assertFalse("saved: nothing to clean", IntakeLifecyclePolicy.abandonsAfterSave(IntakeUiState.Saved(id)))
        assertFalse("duplicate: stage already dropped", IntakeLifecyclePolicy.abandonsAfterSave(IntakeUiState.Duplicate(id)))
        assertFalse("stage gone", IntakeLifecyclePolicy.abandonsAfterSave(IntakeUiState.Cancelled))
        assertTrue("failed save keeps its stage and slot", IntakeLifecyclePolicy.abandonsAfterSave(IntakeUiState.Failed))
        assertTrue(IntakeLifecyclePolicy.abandonsAfterSave(IntakeUiState.StorageUnavailable))
        assertTrue(
            IntakeLifecyclePolicy.abandonsAfterSave(IntakeUiState.VaultUnavailable(VaultUnavailableCause.STORAGE_IO_ERROR)),
        )
    }
}
