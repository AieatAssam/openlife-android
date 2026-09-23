package org.openlife.vault.ocr

/**
 * Pure decision rules for one OCR run (P2-02 REFACTOR). The repository
 * performs I/O around these; nothing here touches storage or the engine.
 *
 * Only [Decision.Persist] may lead to a READY revision, and only through the
 * single transactional commit. Every other outcome ends the revision in a
 * terminal, non-READY state, so no interleaving of cancel, timeout, failure
 * or limit violation can yield confirmed-looking text.
 */
object OcrRunStateMachine {
    /** What the engine stage produced. */
    sealed interface EngineOutcome {
        data class Output(val spans: List<OcrSpanDraft>) : EngineOutcome
        data object TimedOut : EngineOutcome
        data class Failed(val reason: OcrFailureReason) : EngineOutcome
    }

    sealed interface Decision {
        data class Persist(val spans: List<OcrSpanDraft>) : Decision
        data class Terminal(val state: OcrRevisionState, val reason: OcrFailureReason) : Decision
    }

    fun decide(outcome: EngineOutcome): Decision = when (outcome) {
        EngineOutcome.TimedOut -> Decision.Terminal(OcrRevisionState.CANCELLED, OcrFailureReason.TIMEOUT)
        is EngineOutcome.Failed -> Decision.Terminal(OcrRevisionState.FAILED, outcome.reason)
        is EngineOutcome.Output -> decideOutput(outcome.spans)
    }

    /** Maps an engine exception to its failure reason (P2-02-R3). */
    fun reasonFor(error: Exception): OcrFailureReason = when (error) {
        is OcrUnsupportedScriptException -> OcrFailureReason.UNSUPPORTED_SCRIPT
        is OcrLimitExceededException -> OcrFailureReason.LIMIT_EXCEEDED
        is OcrDecodeException, is OcrEngineException -> OcrFailureReason.ENGINE_FAILURE
        else -> OcrFailureReason.ENGINE_FAILURE
    }

    /** The run result a terminal decision reports to the caller. */
    fun resultFor(revisionId: java.util.UUID, decision: Decision.Terminal): OcrRunResult =
        if (decision.state == OcrRevisionState.CANCELLED) {
            OcrRunResult.Cancelled(revisionId, decision.reason)
        } else {
            OcrRunResult.Failed(revisionId, decision.reason)
        }

    private fun decideOutput(spans: List<OcrSpanDraft>): Decision {
        val validated = try {
            OcrOutputValidator.validate(spans)
        } catch (_: OcrLimitExceededException) {
            return Decision.Terminal(OcrRevisionState.FAILED, OcrFailureReason.LIMIT_EXCEEDED)
        }
        return if (OcrScriptPolicy.classify(validated) == OcrScriptStatus.UNSUPPORTED) {
            Decision.Terminal(OcrRevisionState.FAILED, OcrFailureReason.UNSUPPORTED_SCRIPT)
        } else {
            Decision.Persist(validated)
        }
    }
}
