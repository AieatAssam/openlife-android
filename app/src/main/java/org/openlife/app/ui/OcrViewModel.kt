package org.openlife.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRunResult
import java.util.UUID

class OcrViewModel(private val application: OpenLifeApp) : ViewModel() {
    private val _states = MutableStateFlow<Map<UUID, OcrUiState>>(emptyMap())
    val states: StateFlow<Map<UUID, OcrUiState>> = _states.asStateFlow()
    private val jobs = mutableMapOf<UUID, Job>()
    private val unregisterResetCallback = application.registerPreResetCallback(::clearSensitiveContent)
    private val unregisterTrimCallback = application.registerSensitiveContentClearer {
        _states.value = OcrTrim.dropExtractedText(_states.value)
    }

    init {
        forgetDeletedSources()
    }

    /**
     * P2-02-R6: when a source leaves the visible list (it was deleted), cancel
     * its OCR job and drop its text from memory, so the panel clears and a
     * late engine result cannot resurface.
     */
    private fun forgetDeletedSources() {
        viewModelScope.launch {
            val access = application.vault() as? VaultAccess.Ready ?: return@launch
            access.viewRepository.observeVisibleSources().collect { sources ->
                val visible = sources.mapTo(HashSet()) { it.id }
                val gone = (_states.value.keys + jobs.keys).filterNot(visible::contains)
                if (gone.isNotEmpty()) {
                    gone.forEach { jobs.remove(it)?.cancel() }
                    _states.value = _states.value - gone.toSet()
                }
            }
        }
    }

    fun stateFor(sourceId: UUID): OcrUiState = _states.value[sourceId] ?: OcrUiState.Idle

    fun run(sourceId: UUID) {
        jobs[sourceId]?.cancel()
        setState(sourceId, OcrUiState.Running(sourceId))
        jobs[sourceId] = viewModelScope.launch {
            application.appLock.awaitContentAccess()
            val access = application.vault() as? VaultAccess.Ready ?: return@launch
            when (val result = access.ocrRepository.runOcr(sourceId)) {
                is OcrRunResult.Completed -> setState(
                    sourceId,
                    OcrUiState.Ready(sourceId, result.revisionId, result.spans),
                )

                is OcrRunResult.Failed -> setState(sourceId, OcrUiState.Failed(sourceId, result.reason))

                // A timeout is explained, not shown as the user's own Cancel.
                is OcrRunResult.Cancelled -> setState(
                    sourceId,
                    if (result.reason == OcrFailureReason.TIMEOUT) {
                        OcrUiState.Failed(sourceId, OcrFailureReason.TIMEOUT)
                    } else {
                        OcrUiState.Cancelled(sourceId)
                    },
                )

                is OcrRunResult.Stale -> setState(sourceId, OcrUiState.Stale(sourceId))
            }
        }
    }

    fun cancel(sourceId: UUID) {
        jobs.remove(sourceId)?.cancel()
        setState(sourceId, OcrUiState.Cancelled(sourceId))
    }

    private fun clearSensitiveContent() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        _states.value = emptyMap()
    }

    fun correct(revisionId: UUID, spanId: UUID, correctedText: String) {
        viewModelScope.launch {
            val access = application.vault() as? VaultAccess.Ready ?: return@launch
            access.ocrRepository.addCorrection(revisionId, spanId, correctedText)
        }
    }

    fun review(revisionId: UUID, reviewState: OcrReviewState) {
        viewModelScope.launch {
            val access = application.vault() as? VaultAccess.Ready ?: return@launch
            access.ocrRepository.setReviewState(revisionId, reviewState)
        }
    }

    private fun setState(sourceId: UUID, state: OcrUiState) {
        _states.value = _states.value.toMutableMap().apply { put(sourceId, state) }
    }

    override fun onCleared() {
        unregisterResetCallback()
        unregisterTrimCallback()
        clearSensitiveContent()
    }

    companion object {
        fun factory(application: OpenLifeApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { OcrViewModel(application) }
        }
    }
}
