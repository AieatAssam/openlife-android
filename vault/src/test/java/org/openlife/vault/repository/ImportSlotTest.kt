package org.openlife.vault.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** P1-18-R5 (V-03) on the JVM: which Save outcomes keep the import slot. */
class ImportSlotTest {

    /** Review R-06: after a decided save nothing is read, so a read failure cannot turn Saved into an exception. */
    @Test
    fun aDecidedSaveNeverReadsTheRowOrTheStage(): Unit = runBlocking {
        val id = UUID.randomUUID()
        for (decided in listOf(SaveResult.Saved(id), SaveResult.DuplicateFound(id), SaveResult.StageNotFound)) {
            assertFalse(
                keepsSlotAfterSave(
                    decided,
                    rowStillStaged = { error("row read after $decided") },
                    stageExists = { error("stage read after $decided") },
                ),
            )
        }
    }

    @Test
    fun aRetryableFailureThatKeptItsStageKeepsTheSlot(): Unit = runBlocking {
        assertTrue(keepsSlotAfterSave(SaveResult.Failed, rowStillStaged = { true }, stageExists = { true }))
        assertTrue(keepsSlotAfterSave(SaveResult.StorageUnavailable, rowStillStaged = { true }, stageExists = { true }))
    }

    @Test
    fun everyDecidedOutcomeFreesTheSlot(): Unit = runBlocking {
        val id = UUID.randomUUID()
        assertFalse(keepsSlotAfterSave(SaveResult.Saved(id), rowStillStaged = { false }, stageExists = { false }))
        assertFalse(
            keepsSlotAfterSave(SaveResult.DuplicateFound(id), rowStillStaged = { false }, stageExists = { false }),
        )
        assertFalse(keepsSlotAfterSave(SaveResult.StageNotFound, rowStillStaged = { true }, stageExists = { false }))
        // A failure after the rename (stage gone) or one that lost the row is recovery's.
        assertFalse(keepsSlotAfterSave(SaveResult.Failed, rowStillStaged = { true }, stageExists = { false }))
        assertFalse(keepsSlotAfterSave(SaveResult.Failed, rowStillStaged = { false }, stageExists = { true }))
    }

    @Test
    fun aKeptSlotStillBlocksANewImportUntilReleased() {
        val slot = ImportSlot()
        val id = UUID.randomUUID()
        assertTrue(slot.tryBeginPreparing())
        slot.preparedAs(id)
        assertFalse("design §12: one import at a time", slot.tryBeginPreparing())
        slot.release(id)
        assertEquals(null, slot.activeSourceId)
        assertTrue(slot.tryBeginPreparing())
    }
}
