package org.openlife.app.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.repository.ResetConfirmation
import org.openlife.vault.repository.VaultResetResult
import org.openlife.vault.repository.VerifyReport

/**
 * A-11 (P1-19-R15): Settings kept the Verify-all pass and the reset stage in
 * remember/rememberCoroutineScope, which a configuration change discards
 * (MainActivity declares no configChanges). The controller lives in a
 * ViewModel, so a new composition reads the same state and the pass keeps
 * running. The instrumented SettingsRotationTest rotates for real.
 */
class SettingsControllerTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val controller = SettingsController(scope)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun verifyAllKeepsRunningAndReportsToWhicheverScreenReadsIt() {
        val pass = CompletableDeferred<VerifyReport?>()
        controller.verifyAll { pass.await() }
        assertTrue(controller.verify.value.running)

        // A recreated screen reads the same holder: still running, then done.
        val report = VerifyReport(verified = 3, markedCorrupt = 0, transient = 0)
        pass.complete(report)
        assertFalse(controller.verify.value.running)
        assertEquals(VerifyOutcome.Done(report), controller.verify.value.outcome)

        controller.verifyAll { throw IllegalStateException("synthetic") }
        assertEquals(VerifyOutcome.Failed, controller.verify.value.outcome)
        controller.verifyAll { null }
        assertEquals(VerifyOutcome.Unavailable, controller.verify.value.outcome)
    }

    @Test
    fun resetStagesAndOutcomeLiveInTheController() {
        controller.openReset()
        assertEquals(ResetStage.TYPE_WORD, controller.reset.value.stage)
        controller.setWord("RES")
        assertEquals("RES", controller.reset.value.word)
        controller.setWord(WORD)
        controller.continueReset(WORD)
        assertEquals(ResetStage.FINAL_CONFIRMATION, controller.reset.value.stage)

        val result = CompletableDeferred<VaultResetResult>()
        controller.confirmReset { result.await() }
        assertTrue(controller.reset.value.running)
        result.complete(VaultResetResult.BUSY)
        assertEquals(ResetFailure.BUSY, controller.reset.value.failure)
        assertEquals(ResetStage.FINAL_CONFIRMATION, controller.reset.value.stage)

        controller.confirmReset { VaultResetResult.COMPLETED }
        assertEquals(ResetStage.CLOSED, controller.reset.value.stage)
        assertTrue("completion is state, so a recreated screen still navigates", controller.reset.value.completed)
        controller.consumeResetCompleted()
        assertFalse(controller.reset.value.completed)
    }

    /**
     * P1-08 (review, acting owner 2026-11-26): the gate is not UI-only. Only
     * the exact word moves past the first step, and the reset receives the
     * confirmation minted from it; without one, confirming runs nothing.
     */
    @Test
    fun resetRunsOnlyWithTheConfirmationMintedFromTheExactWord() {
        var received: ResetConfirmation? = null
        var runs = 0
        controller.openReset()
        listOf("", "RES", "reset", "RESET ", " RESET", "RESETS").forEach { typed ->
            controller.setWord(typed)
            controller.continueReset(WORD)
            assertEquals("'$typed' must not pass", ResetStage.TYPE_WORD, controller.reset.value.stage)
        }
        controller.confirmReset {
            runs++
            VaultResetResult.COMPLETED
        }
        assertEquals("no confirmation, no reset", 0, runs)

        controller.setWord(WORD)
        controller.continueReset(WORD)
        assertEquals(ResetStage.FINAL_CONFIRMATION, controller.reset.value.stage)
        controller.confirmReset { confirmation ->
            runs++
            received = confirmation
            VaultResetResult.COMPLETED
        }
        assertEquals(1, runs)
        assertNotNull(received)

        // The confirmation is spent: a dismissed or completed flow cannot reset again without the word.
        controller.confirmReset {
            runs++
            VaultResetResult.COMPLETED
        }
        assertEquals(1, runs)
    }

    private companion object {
        const val WORD = "RESET"
    }
}
