package org.openlife.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRevisionState
import org.openlife.vault.ocr.OcrRunResult
import org.openlife.vault.ocr.OcrView
import java.util.UUID

/** What the OCR presenter needs from the vault; the app wires it to OcrRepository behind the app lock. */
interface OcrBackend {
    fun observe(sourceId: UUID): Flow<OcrView?>

    suspend fun run(sourceId: UUID): OcrRunResult

    suspend fun correct(revisionId: UUID, spanId: UUID?, correctedText: String)

    suspend fun review(revisionId: UUID, reviewState: OcrReviewState)
}

/**
 * P2-01-R2/R4: OCR state is derived from the persisted view plus a transient
 * running marker. Text is held only while someone observes a source: when
 * the last observer leaves, or [clearTransient] runs, the state returns to
 * [OcrUiState.Loading] and the next observer re-reads the database.
 */
class OcrPresenter(private val backend: OcrBackend, private val scope: CoroutineScope) {
    private class Entry(val state: MutableStateFlow<OcrUiState>, val job: Job)

    private val entries = mutableMapOf<UUID, Entry>()
    private val running = MutableStateFlow<Set<UUID>>(emptySet())
    private val runs = mutableMapOf<UUID, Job>()

    // A run that failed before it could record a revision has nothing in the database to show.
    private val unrecordedFailures = MutableStateFlow<Map<UUID, OcrFailureReason>>(emptyMap())
    private val mutableGeneration = MutableStateFlow(0)

    /** Changes on [clearTransient]; observers re-request [state] so a visible panel repopulates. */
    val generation: StateFlow<Int> = mutableGeneration.asStateFlow()

    @Synchronized
    fun state(sourceId: UUID): StateFlow<OcrUiState> = entries.getOrPut(sourceId) { start(sourceId) }.state

    private fun start(sourceId: UUID): Entry {
        val state = MutableStateFlow<OcrUiState>(OcrUiState.Loading)
        val upstream = combine(
            backend.observe(sourceId)
                .map<OcrView?, Persisted> { Persisted.Loaded(it) }
                .onStart { emit(Persisted.Pending) },
            running,
            unrecordedFailures,
        ) { persisted, runningIds, failures ->
            OcrStateMapper.map(sourceId, persisted, sourceId in runningIds, failures[sourceId])
        }
        val job = scope.launch {
            // Read only while observed; drop the text as soon as nobody is looking.
            state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { observed ->
                if (observed) upstream.collect { state.value = it } else state.value = OcrUiState.Loading
            }
        }
        return Entry(state, job)
    }

    @Synchronized
    fun run(sourceId: UUID) {
        runs.remove(sourceId)?.cancel()
        unrecordedFailures.update { it - sourceId }
        running.update { it + sourceId }
        val job = scope.launch {
            try {
                val result = backend.run(sourceId)
                if (result is OcrRunResult.Failed && result.revisionId == null) {
                    unrecordedFailures.update { it + (sourceId to result.reason) }
                }
            } finally {
                running.update { it - sourceId }
            }
        }
        runs[sourceId] = job
        job.invokeOnCompletion { synchronized(this) { if (runs[sourceId] === job) runs.remove(sourceId) } }
    }

    /** Cancelling a run records it as cancelled in the database (P2-02); the view shows that. */
    @Synchronized
    fun cancel(sourceId: UUID) {
        runs.remove(sourceId)?.cancel()
    }

    /** P2-01-R4: drop every held OCR text; running extractions continue and are recorded. */
    @Synchronized
    fun clearTransient() {
        entries.values.forEach { entry ->
            entry.job.cancel()
            entry.state.value = OcrUiState.Loading
        }
        entries.clear()
        mutableGeneration.update { it + 1 }
    }

    /** Before a vault reset or when sources disappear: stop runs as well. */
    @Synchronized
    fun forget(sourceIds: Collection<UUID>) {
        sourceIds.forEach { id ->
            runs.remove(id)?.cancel()
            entries.remove(id)?.let { entry ->
                entry.job.cancel()
                entry.state.value = OcrUiState.Loading
            }
        }
        unrecordedFailures.update { it - sourceIds.toSet() }
        // A panel still showing a forgotten source re-reads it (and finds nothing).
        mutableGeneration.update { it + 1 }
    }

    @Synchronized
    fun forgetAll() {
        forget(entries.keys + runs.keys)
        clearTransient()
    }

    fun correct(revisionId: UUID, spanId: UUID?, correctedText: String) {
        scope.launch { backend.correct(revisionId, spanId, correctedText) }
    }

    fun review(revisionId: UUID, reviewState: OcrReviewState) {
        scope.launch { backend.review(revisionId, reviewState) }
    }
}

/** Whether the database has answered yet, and with what. */
sealed interface Persisted {
    data object Pending : Persisted

    data class Loaded(val view: OcrView?) : Persisted
}

/** Pure mapping from persisted OCR state to what the viewer shows (P2-01-R2). */
internal object OcrStateMapper {
    fun map(sourceId: UUID, persisted: Persisted, running: Boolean, unrecordedFailure: OcrFailureReason?): OcrUiState {
        val view = (persisted as? Persisted.Loaded)?.view
        val history = view?.history.orEmpty().map { revision ->
            OcrHistoryItem(
                revision.id,
                revision.state,
                revision.extractedAt ?: revision.startedAt,
                revision.engineId,
                revision.modelVersion,
            )
        }
        return when {
            running -> OcrUiState.Running(sourceId, history)
            persisted is Persisted.Pending -> OcrUiState.Loading
            view == null -> unrecordedFailure?.let { OcrUiState.Failed(sourceId, it) } ?: OcrUiState.Idle
            else -> fromRevision(sourceId, view, history)
        }
    }

    private fun fromRevision(sourceId: UUID, view: OcrView, history: List<OcrHistoryItem>): OcrUiState {
        val revision = view.revision
        return when (revision.state) {
            OcrRevisionState.READY -> OcrUiState.Ready(
                sourceId = sourceId,
                revisionId = revision.id,
                spans = view.spans.map { it.span },
                corrections = view.spans.mapNotNull { spanView ->
                    spanView.correction?.let { spanView.span.id to it.correctedText }
                }.toMap(),
                revisionCorrection = view.revisionCorrection?.correctedText,
                reviewState = revision.reviewState,
                engineId = revision.engineId,
                modelVersion = revision.modelVersion,
                extractedAt = revision.extractedAt,
                history = history,
            )

            OcrRevisionState.FAILED ->
                OcrUiState.Failed(sourceId, revision.failureReason ?: OcrFailureReason.ENGINE_FAILURE, history)

            // A timeout is explained as a failure, not shown as the user's own Cancel (P2-02).
            OcrRevisionState.CANCELLED -> if (revision.failureReason == OcrFailureReason.TIMEOUT) {
                OcrUiState.Failed(sourceId, OcrFailureReason.TIMEOUT, history)
            } else {
                OcrUiState.Cancelled(sourceId, history)
            }

            OcrRevisionState.STALE -> OcrUiState.Stale(sourceId, history)

            OcrRevisionState.RUNNING -> OcrUiState.Running(sourceId, history)

            OcrRevisionState.NOT_STARTED -> OcrUiState.Idle
        }
    }
}
