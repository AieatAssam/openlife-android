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
import org.openlife.vault.facts.FactStatus
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.FactWithLatestRevision
import org.openlife.vault.facts.StaleReason
import java.util.UUID

/** The app-side port the facts presenter reads through; the app container implements it over FactRepository. */
interface FactsGateway {
    fun facts(sourceId: UUID): Flow<List<FactWithLatestRevision>>

    suspend fun extractionCheck(sourceId: UUID): ExtractionCheck
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

    data class Ready(val items: List<FactItem>, val check: ExtractionCheck) : FactsUiState
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
                mutableState.value = FactsUiState.Ready(rows.map(::toItem), check)
            }
        }
    }

    fun clear() {
        job?.cancel()
        job = null
        mutableState.value = FactsUiState.Loading
    }

    private fun toItem(row: FactWithLatestRevision): FactItem = FactItem(
        factId = row.fact.id,
        revisionId = row.revision.id,
        kind = row.revision.kind,
        origin = row.revision.origin,
        status = row.revision.status,
        staleReason = row.revision.staleReason,
        value = row.revision.value,
        candidateTexts = row.revision.value.candidateTexts(),
    )
}
