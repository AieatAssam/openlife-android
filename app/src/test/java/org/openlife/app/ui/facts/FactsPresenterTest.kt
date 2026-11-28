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
import org.openlife.vault.facts.FactEvidence
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactOrigin
import org.openlife.vault.facts.FactRevision
import org.openlife.vault.facts.FactStatus
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.FactWithLatestRevision
import org.openlife.vault.facts.FactWriteResult
import org.openlife.vault.facts.ReviewDecision
import org.openlife.vault.facts.extract.UserValueRefusal
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
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
        val reviews = mutableListOf<Pair<UUID, ReviewDecision>>()
        val clarifications = mutableListOf<Pair<UUID, FactValue>>()
        val additions = mutableListOf<Triple<UUID, FactKind, FactValue>>()
        val corrections = mutableListOf<Pair<UUID, FactValue>>()
        val findAgainSources = mutableListOf<UUID>()
        val candidateSets = mutableMapOf<UUID, FactValue>()

        override fun facts(sourceId: UUID) = rows

        override suspend fun findAgain(sourceId: UUID): Boolean {
            findAgainSources += sourceId
            return true
        }
        override suspend fun extractionCheck(sourceId: UUID): ExtractionCheck = ExtractionCheck.Completed
        override suspend fun currentReadingId(sourceId: UUID): UUID? = null
        override suspend fun evidenceOf(revisionId: UUID): List<FactEvidence> = emptyList()

        override suspend fun candidatesOf(revisionId: UUID): FactValue? = candidateSets[revisionId]

        override suspend fun review(revisionId: UUID, decision: ReviewDecision): FactWriteResult {
            reviews += revisionId to decision
            return FactWriteResult.Failed
        }

        override suspend fun clarify(revisionId: UUID, chosen: FactValue): FactWriteResult {
            clarifications += revisionId to chosen
            return FactWriteResult.Failed
        }

        override suspend fun addUserFact(sourceId: UUID, kind: FactKind, value: FactValue): FactWriteResult {
            additions += Triple(sourceId, kind, value)
            return FactWriteResult.Failed
        }

        override suspend fun correct(revisionId: UUID, value: FactValue): FactWriteResult {
            corrections += revisionId to value
            return FactWriteResult.Failed
        }
    }

    private fun candidateRow(): FactWithLatestRevision {
        val base = row(FactOrigin.EXTRACTED, FactStatus.CLARIFICATION_NEEDED, "2026-03-04 | 2026-04-03")
        val value = FactValue(
            "{\"candidates\":[{\"date\":\"2026-03-04\"},{\"date\":\"2026-04-03\"}]}",
            "2026-03-04 | 2026-04-03",
        )
        return base.copy(revision = base.revision.copy(value = value))
    }

    @Test
    fun reviewPassesTheRevisionAndDecisionToTheGateway() = runBlocking {
        val gateway = FakeGateway(MutableStateFlow(emptyList()))
        val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))
        val item = toItemFor(row(FactOrigin.EXTRACTED, FactStatus.UNREVIEWED, "2026-04-03"))
        presenter.review(item, ReviewDecision.CONFIRM)
        assertEquals(listOf(item.revisionId to ReviewDecision.CONFIRM), gateway.reviews)
    }

    @Test
    fun chooseCandidateSendsTheChosenEntryAndItsText() = runBlocking {
        val row = candidateRow()
        val gateway = FakeGateway(MutableStateFlow(listOf(row)))
        gateway.candidateSets[row.revision.id] = row.revision.value
        val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))
        presenter.start()
        val item = (presenter.state.value as FactsUiState.Ready).items.single()
        presenter.chooseCandidate(item, 1)
        assertEquals(
            listOf(item.revisionId to FactValue("{\"date\":\"2026-04-03\"}", "2026-04-03")),
            gateway.clarifications,
        )
    }

    /**
     * P3-05-R1 (C2-R37): a correction goes through the same input as Add a fact;
     * a refusal never reaches the gateway.
     */
    @Test
    fun aClarifiedValueOffersItsCandidatesAndRechoosingSendsOneOfThem() = runBlocking {
        val candidates = "{\"candidates\":[{\"date\":\"2026-03-04\"},{\"date\":\"2026-04-03\"}]}"
        val clarified = row(FactOrigin.USER, FactStatus.CONFIRMED, "2026-04-03")
        val gateway = FakeGateway(MutableStateFlow(listOf(clarified)))
        gateway.candidateSets[clarified.revision.id] = FactValue(candidates, "2026-03-04 | 2026-04-03")
        val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))
        presenter.start()
        val item = (presenter.state.value as FactsUiState.Ready).items.single()
        assertEquals(candidates, item.candidates?.valueJson)
        presenter.chooseCandidate(item, 0)
        assertEquals(
            listOf(item.revisionId to FactValue("{\"date\":\"2026-03-04\"}", "2026-03-04")),
            gateway.clarifications,
        )
    }

    @Test
    fun correctSendsTheNormalisedValueForThatRevisionAndNeverARefusedOne() = runBlocking {
        val gateway = FakeGateway(MutableStateFlow(emptyList()))
        val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))
        val item = toItemFor(row(FactOrigin.EXTRACTED, FactStatus.UNREVIEWED, "2026-04-03"))
        val saved = presenter.correct(item, UserFactInput.Date(LocalDate.of(2026, 11, 27)))
        assertTrue(saved is UserFactOutcome.Saved)
        val (revisionId, value) = gateway.corrections.single()
        assertEquals(item.revisionId, revisionId)
        assertEquals("2026-11-27", value.canonicalText)
        val refused = presenter.correct(item, UserFactInput.Date(null))
        assertEquals(UserFactOutcome.Refused(UserValueRefusal.NOT_CHOSEN), refused)
        assertEquals(1, gateway.corrections.size)
    }

    @Test
    fun aBlankUserValueIsRefusedAndNeverSaved() = runBlocking {
        val gateway = FakeGateway(MutableStateFlow(emptyList()))
        val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))
        val outcome = presenter.addUserText(FactKind.EMAIL, "   ")
        assertEquals(UserFactOutcome.Refused(UserValueRefusal.BLANK), outcome)
        assertTrue(gateway.additions.isEmpty())
    }

    @Test
    fun anAcceptedUserEmailIsSavedWithTheUserKind() = runBlocking {
        val gateway = FakeGateway(MutableStateFlow(emptyList()))
        val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))
        val outcome = presenter.addUserText(FactKind.EMAIL, "name@example.com")
        assertTrue(outcome is UserFactOutcome.Saved)
        assertEquals(1, gateway.additions.size)
        assertEquals(FactKind.EMAIL, gateway.additions.single().second)
    }

    @Test
    fun aPickedDateIsSavedAsADateValueWithItsDay() = runBlocking {
        val gateway = FakeGateway(MutableStateFlow(emptyList()))
        val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))
        val outcome = presenter.addUserDate(LocalDate.of(2026, 4, 3))
        assertTrue(outcome is UserFactOutcome.Saved)
        val (_, kind, value) = gateway.additions.single()
        assertEquals(FactKind.DATE, kind)
        assertEquals("2026-04-03", value.canonicalText)
    }

    @Test
    fun noDateChosenIsRefusedAndNeverSaved() = runBlocking {
        val gateway = FakeGateway(MutableStateFlow(emptyList()))
        val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(UserFactOutcome.Refused(UserValueRefusal.NOT_CHOSEN), presenter.addUserDate(null))
        assertTrue(gateway.additions.isEmpty())
    }

    @Test
    fun aPickedTimeIsSavedAsATimeValueWithItsHourAndMinute() = runBlocking {
        val gateway = FakeGateway(MutableStateFlow(emptyList()))
        val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))
        val outcome = presenter.addUserTime(9, 30)
        assertTrue(outcome is UserFactOutcome.Saved)
        val (_, kind, value) = gateway.additions.single()
        assertEquals(FactKind.TIME, kind)
        assertEquals("09:30", value.canonicalText)
    }

    @Test
    fun noTimeChosenIsRefusedAndNeverSaved() = runBlocking {
        val gateway = FakeGateway(MutableStateFlow(emptyList()))
        val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(UserFactOutcome.Refused(UserValueRefusal.NOT_CHOSEN), presenter.addUserTime(null, null))
        assertTrue(gateway.additions.isEmpty())
    }

    @Test
    fun anAmountWithAChosenCurrencyIsSavedAsAMoneyValue() = runBlocking {
        val gateway = FakeGateway(MutableStateFlow(emptyList()))
        val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))
        val outcome = presenter.addUserMoney("12.50", "EUR")
        assertTrue(outcome is UserFactOutcome.Saved)
        val (_, kind, value) = gateway.additions.single()
        assertEquals(FactKind.MONEY, kind)
        assertTrue(value.canonicalText, value.canonicalText.contains("12.50") && value.canonicalText.contains("EUR"))
    }

    @Test
    fun anAmountWithNoCurrencyIsRefusedAndNeverSaved() = runBlocking {
        val gateway = FakeGateway(MutableStateFlow(emptyList()))
        val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(UserFactOutcome.Refused(UserValueRefusal.MONEY_NEEDS_CODE), presenter.addUserMoney("12.50", null))
        assertTrue(gateway.additions.isEmpty())
    }

    @Test
    fun aPickedDayStaysTheSameDayInEveryTimeZone() {
        val before = TimeZone.getDefault()
        try {
            val picked = LocalDate.of(2026, 4, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            for (zone in listOf("America/Los_Angeles", "Pacific/Honolulu", "Pacific/Auckland", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)))
                assertEquals(zone, LocalDate.of(2026, 4, 3), PickedDate.fromMillis(picked))
            }
            assertEquals(null, PickedDate.fromMillis(null))
        } finally {
            TimeZone.setDefault(before)
        }
    }

    private fun toItemFor(row: FactWithLatestRevision): FactItem {
        val gateway = FakeGateway(MutableStateFlow(listOf(row)))
        val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))
        presenter.start()
        return (presenter.state.value as FactsUiState.Ready).items.single()
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
    fun findAgainAsksTheExtractionPortForThisSource() = runBlocking {
        val gateway = FakeGateway(MutableStateFlow(emptyList()))
        val presenter = FactsPresenter(gateway, sourceId, CoroutineScope(Dispatchers.Unconfined))

        assertTrue(presenter.findAgain())
        assertEquals(listOf(sourceId), gateway.findAgainSources)
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
