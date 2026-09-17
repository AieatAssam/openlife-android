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
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRunResult
import java.util.UUID

class OcrViewModel(private val application: OpenLifeApp) : ViewModel() {
    private val _states = MutableStateFlow<Map<UUID, OcrUiState>>(emptyMap())
    val states: StateFlow<Map<UUID, OcrUiState>> = _states.asStateFlow()
    private val jobs = mutableMapOf<UUID, Job>()

    fun stateFor(sourceId: UUID): OcrUiState = _states.value[sourceId] ?: OcrUiState.Idle

    fun run(sourceId: UUID) {
        jobs[sourceId]?.cancel()
        setState(sourceId, OcrUiState.Running(sourceId))
        jobs[sourceId] = viewModelScope.launch {
            val access = application.vault() as? VaultAccess.Ready ?: return@launch
            when (val result = access.ocrRepository.runOcr(sourceId)) {
                is OcrRunResult.Completed -> setState(
                    sourceId,
                    OcrUiState.Ready(sourceId, result.revisionId, result.spans),
                )

                is OcrRunResult.Failed -> setState(sourceId, OcrUiState.Failed(sourceId, result.reason))

                is OcrRunResult.Cancelled -> setState(sourceId, OcrUiState.Cancelled(sourceId))

                is OcrRunResult.Stale -> setState(sourceId, OcrUiState.Stale(sourceId))
            }
        }
    }

    fun cancel(sourceId: UUID) {
        jobs.remove(sourceId)?.cancel()
        setState(sourceId, OcrUiState.Cancelled(sourceId))
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
        jobs.values.forEach(Job::cancel)
        jobs.clear()
    }

    companion object {
        fun factory(application: OpenLifeApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { OcrViewModel(application) }
        }
    }
}
