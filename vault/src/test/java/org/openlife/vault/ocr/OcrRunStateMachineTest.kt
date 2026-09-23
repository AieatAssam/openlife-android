package org.openlife.vault.ocr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * P2-02 acceptance: no engine outcome other than validated Latin text may
 * lead to a READY revision, and every other outcome ends in a named
 * terminal state.
 */
class OcrRunStateMachineTest {
    private val latin = listOf(OcrSpanDraft("Appointment 14 October", null, null))
    private val mixedScript = listOf(OcrSpanDraft("hello мир", null, null))

    @Test
    fun validatedLatinTextIsTheOnlyOutcomeThatPersists() {
        assertEquals(
            OcrRunStateMachine.Decision.Persist(latin),
            OcrRunStateMachine.decide(OcrRunStateMachine.EngineOutcome.Output(latin)),
        )
    }

    @Test
    fun timeoutEndsCancelledWithTimeoutNotUserCancel() {
        assertEquals(
            OcrRunStateMachine.Decision.Terminal(OcrRevisionState.CANCELLED, OcrFailureReason.TIMEOUT),
            OcrRunStateMachine.decide(OcrRunStateMachine.EngineOutcome.TimedOut),
        )
    }

    @Test
    fun engineFailuresEndFailedWithTheirReason() {
        OcrFailureReason.entries.forEach { reason ->
            assertEquals(
                OcrRunStateMachine.Decision.Terminal(OcrRevisionState.FAILED, reason),
                OcrRunStateMachine.decide(OcrRunStateMachine.EngineOutcome.Failed(reason)),
            )
        }
    }

    @Test
    fun unsupportedScriptOutputNeverPersists() {
        assertEquals(
            OcrRunStateMachine.Decision.Terminal(OcrRevisionState.FAILED, OcrFailureReason.UNSUPPORTED_SCRIPT),
            OcrRunStateMachine.decide(OcrRunStateMachine.EngineOutcome.Output(mixedScript)),
        )
    }

    @Test
    fun outputBeyondTheSpanLimitNeverPersists() {
        val tooMany = List(OcrLimits.MAX_SPANS + 1) { OcrSpanDraft("x", null, null) }
        assertEquals(
            OcrRunStateMachine.Decision.Terminal(OcrRevisionState.FAILED, OcrFailureReason.LIMIT_EXCEEDED),
            OcrRunStateMachine.decide(OcrRunStateMachine.EngineOutcome.Output(tooMany)),
        )
    }

    @Test
    fun exceptionsMapToTypedReasonsAndIllegalArgumentIsNotALimit() {
        assertEquals(OcrFailureReason.UNSUPPORTED_SCRIPT, OcrRunStateMachine.reasonFor(OcrUnsupportedScriptException()))
        assertEquals(OcrFailureReason.LIMIT_EXCEEDED, OcrRunStateMachine.reasonFor(OcrLimitExceededException("x")))
        assertEquals(OcrFailureReason.ENGINE_FAILURE, OcrRunStateMachine.reasonFor(OcrDecodeException("x")))
        assertEquals(OcrFailureReason.ENGINE_FAILURE, OcrRunStateMachine.reasonFor(OcrEngineException("x")))
        assertEquals(OcrFailureReason.ENGINE_FAILURE, OcrRunStateMachine.reasonFor(IllegalArgumentException("x")))
    }

    @Test
    fun terminalResultsReportCancelOrFailureWithTheReason() {
        val id = UUID.randomUUID()
        assertEquals(
            OcrRunResult.Cancelled(id, OcrFailureReason.TIMEOUT),
            OcrRunStateMachine.resultFor(
                id,
                OcrRunStateMachine.Decision.Terminal(OcrRevisionState.CANCELLED, OcrFailureReason.TIMEOUT),
            ),
        )
        assertEquals(
            OcrRunResult.Failed(id, OcrFailureReason.ENGINE_FAILURE),
            OcrRunStateMachine.resultFor(
                id,
                OcrRunStateMachine.Decision.Terminal(OcrRevisionState.FAILED, OcrFailureReason.ENGINE_FAILURE),
            ),
        )
    }
}
