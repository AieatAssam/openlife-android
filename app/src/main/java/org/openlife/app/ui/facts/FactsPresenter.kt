package org.openlife.app.ui.facts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import org.openlife.vault.facts.StaleReason
import org.openlife.vault.facts.extract.UserValueNormaliser
import org.openlife.vault.facts.extract.UserValueRefusal
import org.openlife.vault.facts.extract.UserValueResult
import java.time.LocalDate
import java.util.UUID

/** The app-side port the facts presenter reads through; the app container implements it over FactRepository. */
interface FactsGateway {
    fun facts(sourceId: UUID): Flow<List<FactWithLatestRevision>>

    suspend fun extractionCheck(sourceId: UUID): ExtractionCheck

    /** The reading the source currently shows; evidence on any other reading cannot be opened (P3-05-R1). */
    suspend fun currentReadingId(sourceId: UUID): UUID?

    suspend fun evidenceOf(revisionId: UUID): List<FactEvidence>

    suspend fun review(revisionId: UUID, decision: ReviewDecision): FactWriteResult

    suspend fun clarify(revisionId: UUID, chosen: FactValue): FactWriteResult

    suspend fun addUserFact(sourceId: UUID, kind: FactKind, value: FactValue): FactWriteResult

    /** P3-05-R4: "Find facts again" for the source; true when the extraction finished. */
    suspend fun findAgain(sourceId: UUID): Boolean
}

/** What a typed user value did: saved through the repository, or refused with the reason shown to the user (R2). */
sealed interface UserFactOutcome {
    data class Saved(val result: FactWriteResult) : UserFactOutcome

    data class Refused(val reason: UserValueRefusal) : UserFactOutcome
}

/** One fact as the panel shows it. The value is held in memory only and never printed (see [toString]). */
class FactItem(
    val factId: UUID,
    val revisionId: UUID,
    val kind: FactKind,
    val origin: FactOrigin,
    val status: FactStatus,
    val staleReason: StaleReason?,
    val value: FactValue,
    val candidateTexts: List<String>,
    val evidence: List<FactEvidence> = emptyList(),
) {
    val needsClarification: Boolean get() = status == FactStatus.CLARIFICATION_NEEDED
    val isStale: Boolean get() = status == FactStatus.STALE

    /** Ids, kind, origin and status only: a fact value must not reach a log or a test message. */
    override fun toString(): String = "FactItem(kind=$kind, origin=$origin, status=$status)"
}

sealed interface FactsUiState {
    /** Nothing held: before the first load, and after [FactsPresenter.clear]. */
    data object Loading : FactsUiState

    data class Ready(val items: List<FactItem>, val check: ExtractionCheck, val currentReadingId: UUID?) : FactsUiState
}

/**
 * P3-05-R1: the facts of one source as UI state. Values live here, in memory, only: [clear] drops them
 * (on hide, on a content scrub and before a vault reset). Nothing is written to saved state.
 */
class FactsPresenter(
    private val gateway: FactsGateway,
    private val sourceId: UUID,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<FactsUiState>(FactsUiState.Loading)
    val state: StateFlow<FactsUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            gateway.facts(sourceId).collect { rows ->
                val check = gateway.extractionCheck(sourceId)
                val current = gateway.currentReadingId(sourceId)
                val items = rows.map { row -> toItem(row, gateway.evidenceOf(row.revision.id)) }
                mutableState.value = FactsUiState.Ready(items, check, current)
            }
        }
    }

    fun clear() {
        job?.cancel()
        job = null
        mutableState.value = FactsUiState.Loading
    }

    /** Confirms, rejects or reopens one revision; the repository decides whether the status table allows it. */
    suspend fun review(item: FactItem, decision: ReviewDecision): FactWriteResult =
        gateway.review(item.revisionId, decision)

    /** Chooses one candidate of a CLARIFICATION_NEEDED fact; the entry and its text stay exactly as stored (C2-R36). */
    suspend fun chooseCandidate(item: FactItem, index: Int): FactWriteResult {
        val entries = item.value.candidateEntries()
        val texts = item.value.candidateTexts()
        if (index !in entries.indices || index !in texts.indices) {
            return FactWriteResult.Refused(FactRefusal.INVALID_VALUE)
        }
        return gateway.clarify(item.revisionId, FactValue(entries[index], texts[index]))
    }

    /** P3-05-R4: asks the extraction port to run again for this source; the list updates from its own flow. */
    suspend fun findAgain(): Boolean = gateway.findAgain(sourceId)

    /**
     * Adds a user-typed URL, email or phone through the vault's single-value normaliser. A refused input is
     * returned with its reason and never reaches the repository.
     */
    suspend fun addUserText(kind: FactKind, input: String): UserFactOutcome =
        saveResult(kind, UserValueNormaliser.text(kind, input))

    private suspend fun saveResult(kind: FactKind, result: UserValueResult): UserFactOutcome = when (result) {
        is UserValueResult.Accepted -> UserFactOutcome.Saved(gateway.addUserFact(sourceId, kind, result.value))
        is UserValueResult.Refused -> UserFactOutcome.Refused(result.reason)
    }

    /** P3-05-R2: a day from the date picker; no chosen day is refused and never reaches the repository. */
    suspend fun addUserDate(date: LocalDate?): UserFactOutcome = saveResult(FactKind.DATE, UserValueNormaliser.date(date))

    /** P3-05-R2: a time chosen in the time picker; a missing hour or minute is refused and never saved. */
    suspend fun addUserTime(hour: Int?, minute: Int?): UserFactOutcome =
        saveResult(FactKind.TIME, UserValueNormaliser.time(hour, minute, null))

    /** Routes one form submission to the normaliser for its kind; nothing else reaches the repository. */
    suspend fun addUserInput(input: UserFactInput): UserFactOutcome = when (input) {
        is UserFactInput.Typed -> addUserText(input.kind, input.text)
        is UserFactInput.Date -> addUserDate(input.date)
        is UserFactInput.Time -> addUserTime(input.hour, input.minute)
    }

    private fun toItem(row: FactWithLatestRevision, evidence: List<FactEvidence>): FactItem = FactItem(
        factId = row.fact.id,
        revisionId = row.revision.id,
        kind = row.revision.kind,
        origin = row.revision.origin,
        status = row.revision.status,
        staleReason = row.revision.staleReason,
        value = row.revision.value,
        candidateTexts = row.revision.value.candidateTexts(),
        evidence = evidence,
    )
}
