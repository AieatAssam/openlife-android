package org.openlife.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRunResult
import org.openlife.vault.ocr.OcrView
import java.util.UUID

/**
 * P2-01: a thin layer over [OcrPresenter]. What the viewer shows comes from
 * the database, so it survives a new ViewModel or a process restart; the
 * only in-memory state is which extraction is running.
 */
class OcrViewModel(private val application: OpenLifeApp) : ViewModel() {
    private val presenter = OcrPresenter(AppOcrBackend(application), viewModelScope)
    private val unregisterResetCallback = application.registerPreResetCallback(presenter::forgetAll)

    // P1-16-R3: memory pressure and hiding drop OCR text; a visible panel re-reads it.
    private val unregisterTrimCallback = application.registerSensitiveContentClearer(presenter::clearTransient)

    /** Changes when held text is dropped; the viewer re-requests [state] so a visible panel repopulates. */
    val generation: StateFlow<Int> = presenter.generation

    init {
        forgetDeletedSources()
    }

    /**
     * P2-02-R6: when a source leaves the visible list (it was deleted), stop
     * its extraction and forget it, so a late engine result cannot resurface.
     */
    private fun forgetDeletedSources() {
        viewModelScope.launch {
            val access = application.vault() as? VaultAccess.Ready ?: return@launch
            var previous = emptySet<UUID>()
            access.viewRepository.observeVisibleSources().collect { sources ->
                val visible = sources.mapTo(HashSet()) { it.id }
                val gone = previous - visible
                if (gone.isNotEmpty()) presenter.forget(gone)
                previous = visible
            }
        }
    }

    fun state(sourceId: UUID): StateFlow<OcrUiState> = presenter.state(sourceId)

    fun run(sourceId: UUID) = presenter.run(sourceId)

    fun cancel(sourceId: UUID) = presenter.cancel(sourceId)

    /** P2-01-R4: called when the activity is hidden; the database remains the record. */
    fun clearTransient() = presenter.clearTransient()

    fun correct(revisionId: UUID, spanId: UUID, correctedText: String) =
        presenter.correct(revisionId, spanId, correctedText)

    fun review(revisionId: UUID, reviewState: OcrReviewState) = presenter.review(revisionId, reviewState)

    override fun onCleared() {
        unregisterResetCallback()
        unregisterTrimCallback()
        presenter.forgetAll()
    }

    companion object {
        fun factory(application: OpenLifeApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { OcrViewModel(application) }
        }
    }
}

/** The vault behind the app lock: persisted OCR text is content, read only once unlocked (ADR-0004). */
private class AppOcrBackend(private val application: OpenLifeApp) : OcrBackend {
    override fun observe(sourceId: UUID): Flow<OcrView?> = flow {
        application.appLock.awaitContentAccess()
        when (val access = application.vault()) {
            is VaultAccess.Ready -> emitAll(access.ocrRepository.observeOcrView(sourceId))
            is VaultAccess.Unavailable -> emit(null)
        }
    }

    override suspend fun run(sourceId: UUID): OcrRunResult {
        application.appLock.awaitContentAccess()
        val access = application.vault() as? VaultAccess.Ready
            ?: return OcrRunResult.Failed(null, OcrFailureReason.SOURCE_NOT_READY)
        return access.ocrRepository.runOcr(sourceId)
    }

    override suspend fun correct(revisionId: UUID, spanId: UUID?, correctedText: String) {
        (application.vault() as? VaultAccess.Ready)?.ocrRepository?.addCorrection(revisionId, spanId, correctedText)
    }

    override suspend fun review(revisionId: UUID, reviewState: OcrReviewState) {
        (application.vault() as? VaultAccess.Ready)?.ocrRepository?.setReviewState(revisionId, reviewState)
    }
}
