package org.openlife.app.ui.facts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.facts.ExtractionCheck
import org.openlife.vault.facts.Fact
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactOrigin
import org.openlife.vault.facts.FactRevision
import org.openlife.vault.facts.FactStatus
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.FactWithLatestRevision
import java.util.UUID

/** P3-05-R1, R2: the facts presenter shows origin and status per fact and holds nothing once cleared. */
class FactsPresenterTest {
    private val sourceId = UUID.randomUUID()

    private fun row(origin: FactOrigin, status: FactStatus, text: String): FactWithLatestRevision {
        val factId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        return FactWithLatestRevision(
            fact = Fact(factId, sourceId, FactKind.DATE, 0, revisionId, 1L),
            revision = FactRevision(
                id = revisionId,
                factId = factId,
                revisionNo = 1,
                origin = origin,
                kind = FactKind.DATE,
                value = FactValue("{\"date\":\"2026-04-03\"}", text),
                rawText = null,
                status = status,
                staleReason = null,
                staleAt = null,
                methodId = "test",
                methodVersion = null,
                locale = null,
                createdAt = 1L,
                supersedesRevisionId = null,
            ),
        )
    }

    private class FakeGateway(val rows: MutableStateFlow<List<FactWithLatestRevision>>) : FactsGateway {
        override fun facts(sourceId: UUID) = rows
        override suspend fun extractionCheck(sourceId: UUID): ExtractionCheck = ExtractionCheck.Completed
    }

    @Test
    fun eachFactCarriesItsOriginAndStatus() = runBlocking {
        val rows = MutableStateFlow(
            listOf(
                row(FactOrigin.EXTRACTED, FactStatus.CONFIRMED, "2026-04-03"),
                row(FactOrigin.USER, FactStatus.CONFIRMED, "2026-05-01"),
            ),
        )
        val presenter = FactsPresenter(FakeGateway(rows), sourceId, CoroutineScope(Dispatchers.Unconfined))
        presenter.start()
        val ready = presenter.state.value as FactsUiState.Ready
        assertEquals(listOf(FactOrigin.EXTRACTED, FactOrigin.USER), ready.items.map { it.origin })
        assertEquals(listOf(FactStatus.CONFIRMED, FactStatus.CONFIRMED), ready.items.map { it.status })
    }

    @Test
    fun aClarificationNeededFactKeepsItsCandidates() = runBlocking {
        val rows = MutableStateFlow(listOf(row(FactOrigin.EXTRACTED, FactStatus.CLARIFICATION_NEEDED, "2026-03-04")))
        val presenter = FactsPresenter(FakeGateway(rows), sourceId, CoroutineScope(Dispatchers.Unconfined))
        presenter.start()
        val item = (presenter.state.value as FactsUiState.Ready).items.single()
        assertEquals(FactStatus.CLARIFICATION_NEEDED, item.status)
        assertTrue(item.needsClarification)
    }

    @Test
    fun clearDropsTheFactsAndTheirValues() = runBlocking {
        val rows = MutableStateFlow(listOf(row(FactOrigin.EXTRACTED, FactStatus.UNREVIEWED, "2026-04-03")))
        val presenter = FactsPresenter(FakeGateway(rows), sourceId, CoroutineScope(Dispatchers.Unconfined))
        presenter.start()
        presenter.clear()
        assertEquals(FactsUiState.Loading, presenter.state.value)
    }

    @Test
    fun aFactItemNeverPrintsItsValue() = runBlocking {
        val rows = MutableStateFlow(listOf(row(FactOrigin.USER, FactStatus.CONFIRMED, "2026-04-03")))
        val presenter = FactsPresenter(FakeGateway(rows), sourceId, CoroutineScope(Dispatchers.Unconfined))
        presenter.start()
        val item = (presenter.state.value as FactsUiState.Ready).items.single()
        assertFalse(item.toString().contains("2026-04-03"))
    }
}
