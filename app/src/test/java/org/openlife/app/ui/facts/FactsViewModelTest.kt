package org.openlife.app.ui.facts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openlife.vault.facts.ExtractionCheck
import org.openlife.vault.facts.FactEvidence
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactOrigin
import org.openlife.vault.facts.FactRefusal
import org.openlife.vault.facts.FactStatus
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.FactWithLatestRevision
import org.openlife.vault.facts.FactWriteResult
import org.openlife.vault.facts.ReviewDecision
import java.util.UUID

/** P3-05 review U-05: the facts writes run outside the screen's scope, and a refused write reports a message. */
class FactsViewModelTest {
    private val sourceId = UUID.randomUUID()
    private val revisionId = UUID.randomUUID()
    private val gateway = RecordingGateway()
    private val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))
    private val item = FactItem(
        factId = UUID.randomUUID(),
        revisionId = revisionId,
        kind = FactKind.DATE,
        origin = FactOrigin.EXTRACTED,
        status = FactStatus.UNREVIEWED,
        staleReason = null,
        value = FactValue("{}", "2026-04-03"),
        candidateTexts = emptyList(),
    )

    @Test
    fun refusedReviewReportsMessage() = runBlocking {
        gateway.reviewResult = FactWriteResult.Refused(FactRefusal.NOT_ALLOWED)
        val commands = FactsCommands(presenter, CoroutineScope(Dispatchers.Unconfined))
        commands.review(item, ReviewDecision.CONFIRM)
        assertEquals(FactMessage.NOT_SAVED, commands.message.value)
    }

    /** A write is not cancelled by clearing the screen: the gateway still receives it and finishes. */
    @Test
    fun writeCompletesAfterClear() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        gateway.reviewResult = FactWriteResult.Written(UUID.randomUUID(), revisionId)
        val writeScope = CoroutineScope(Dispatchers.Unconfined)
        val commands = FactsCommands(presenter, writeScope)
        commands.review(item, ReviewDecision.CONFIRM)
        commands.clear()
        gate.complete(Unit)
        assertEquals(listOf(revisionId to ReviewDecision.CONFIRM), gateway.reviews)
        assertEquals(null, commands.message.value)
    }

    /** P3-05 review U-05 (follow-up): a write that finishes after the screen cleared must not set a message. */
    @Test
    fun aLateRefusalAfterClearSetsNoMessage() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        gateway.reviewResult = FactWriteResult.Refused(FactRefusal.NOT_ALLOWED)
        val commands = FactsCommands(presenter, CoroutineScope(Dispatchers.Unconfined))
        commands.review(item, ReviewDecision.CONFIRM)
        commands.clear()
        gate.complete(Unit)
        assertEquals("a late result does not set the cleared message", null, commands.message.value)
    }

    private class RecordingGateway : FactsGateway {
        val reviews = mutableListOf<Pair<UUID, ReviewDecision>>()
        var reviewResult: FactWriteResult = FactWriteResult.Failed
        var gate: CompletableDeferred<Unit>? = null

        override fun facts(sourceId: UUID): Flow<List<FactWithLatestRevision>> = flowOf(emptyList())

        override suspend fun extractionCheck(sourceId: UUID): ExtractionCheck = throw UnsupportedOperationException()

        override suspend fun currentReadingId(sourceId: UUID): UUID? = null

        override suspend fun evidenceOf(revisionId: UUID): List<FactEvidence> = emptyList()

        override suspend fun candidatesOf(revisionId: UUID): FactValue? = null

        override suspend fun review(revisionId: UUID, decision: ReviewDecision): FactWriteResult {
            gate?.await()
            reviews += revisionId to decision
            return reviewResult
        }

        override suspend fun clarify(revisionId: UUID, chosen: FactValue): FactWriteResult =
            throw UnsupportedOperationException()

        override suspend fun correct(revisionId: UUID, value: FactValue): FactWriteResult =
            throw UnsupportedOperationException()

        override suspend fun addUserFact(sourceId: UUID, kind: FactKind, value: FactValue): FactWriteResult =
            throw UnsupportedOperationException()

        override suspend fun findAgain(sourceId: UUID): Boolean = false
    }
}
