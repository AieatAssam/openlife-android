package org.openlife.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.SourceState
import org.openlife.vault.repository.ImageRejectionReason
import org.openlife.vault.repository.ImportLimits
import org.openlife.vault.repository.PrepareResult
import org.openlife.vault.repository.SaveResult

private fun describeImageRejection(reason: ImageRejectionReason): String = when (reason) {
    ImageRejectionReason.EXCEEDS_BYTE_LIMIT -> "the file is too large"
    ImageRejectionReason.UNSUPPORTED_FORMAT -> "unsupported format"
    ImageRejectionReason.DECLARED_FORMAT_MISMATCH -> "the file doesn't match its declared type"
    ImageRejectionReason.CORRUPT_CONTENT -> "the file appears to be corrupt"
    ImageRejectionReason.ANIMATED_NOT_SUPPORTED -> "animated images aren't supported"
    ImageRejectionReason.EXCEEDS_DIMENSION_LIMIT -> "the image is too large"
}

private const val KEY_SOURCE_ID = "org.openlife.app.ui.IntakeViewModel.sourceId"

/**
 * Backs the intake preview screen hosted by `IntakeActivity`. Design §8:
 * rotation may retain the active preview only through a ViewModel and
 * Source UUID kept in [SavedStateHandle] — never plaintext image bytes in
 * saved instance state. On process recreation, [restorePreviewIfNeeded]
 * re-authenticates the stage from disk rather than trusting anything held
 * in memory across the recreation.
 */
class IntakeViewModel(
    private val application: OpenLifeApp,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow<IntakeUiState>(IntakeUiState.Preparing)
    val state: StateFlow<IntakeUiState> = _state

    /** The active provider read, so cancelling while still preparing closes it immediately. */
    private var activeImportJob: Job? = null
    private var activeInputStream: InputStream? = null

    private var sourceId: UUID?
        get() = savedStateHandle.get<String>(KEY_SOURCE_ID)?.let(UUID::fromString)
        set(value) {
            savedStateHandle[KEY_SOURCE_ID] = value?.toString()
        }

    init {
        sourceId?.let { restorePreviewIfNeeded(it) }
    }

    fun startImport(stream: InputStream, declaredMimeType: String, intakeKind: IntakeKind) {
        activeImportJob?.cancel()
        activeInputStream?.let { closeQuietly(it) }
        activeInputStream = stream
        activeImportJob = viewModelScope.launch {
            try {
                // The intake boundary owns the provider descriptor after opening
                // it. Closing through `use` covers normal completion, a failed
                // read, vault/bootstrap failure, and coroutine cancellation.
                stream.use { input ->
                    when (val access = application.vault()) {
                        is VaultAccess.Unavailable -> {
                            closeQuietly(input)
                            _state.value = IntakeUiState.VaultUnavailable(access.reason)
                        }
                        is VaultAccess.Ready -> {
                            val result = withContext(Dispatchers.IO) {
                                // Cooperative cancellation: closing the descriptor is
                                // what unblocks a stuck provider read, which then
                                // fails closed through prepareImport's own I/O
                                // handling (design §12).
                                val deadline = launch {
                                    delay(ImportLimits.PROVIDER_READ_DEADLINE_SECONDS * 1000)
                                    input.close()
                                }
                                try {
                                    access.importRepository.prepareImport(input, declaredMimeType, intakeKind)
                                } finally {
                                    deadline.cancel()
                                }
                            }
                            // Publish no terminal state while the provider
                            // descriptor is still open; this makes cancellation
                            // and failure observable as fully released at the
                            // state boundary.
                            closeQuietly(input)
                            applyPrepareResult(result, access)
                        }
                    }
                }
            } finally {
                if (activeInputStream === stream) activeInputStream = null
            }
        }
    }

    private suspend fun applyPrepareResult(result: PrepareResult, access: VaultAccess.Ready) {
        _state.value = when (result) {
            is PrepareResult.Prepared -> {
                sourceId = result.sourceId
                val previewBytes = access.viewRepository.loadStagePreviewBytes(result.sourceId)
                IntakeUiState.Preview(
                    result.sourceId, result.format, result.width, result.height, result.byteCount, previewBytes
                )
            }
            is PrepareResult.Rejected -> IntakeUiState.Rejected(describeImageRejection(result.reason))
            PrepareResult.Busy -> IntakeUiState.Busy
            PrepareResult.Failed -> IntakeUiState.Failed
        }
    }

    private fun restorePreviewIfNeeded(id: UUID) {
        viewModelScope.launch {
            when (val access = application.vault()) {
                is VaultAccess.Unavailable -> _state.value = IntakeUiState.VaultUnavailable(access.reason)
                is VaultAccess.Ready -> {
                    val source = access.viewRepository.findSource(id)
                    if (source == null || source.state != SourceState.STAGED) {
                        _state.value = IntakeUiState.Cancelled
                        return@launch
                    }
                    val previewBytes = access.viewRepository.loadStagePreviewBytes(id)
                    _state.value = IntakeUiState.Preview(
                        id, source.mimeType!!, source.width!!, source.height!!, source.byteCount!!, previewBytes
                    )
                }
            }
        }
    }

    fun confirmSave() {
        val id = sourceId ?: return
        viewModelScope.launch {
            _state.value = IntakeUiState.Saving(id)
            when (val access = application.vault()) {
                is VaultAccess.Unavailable -> _state.value = IntakeUiState.VaultUnavailable(access.reason)
                is VaultAccess.Ready -> {
                    _state.value = when (val result = access.importRepository.saveImport(id)) {
                        is SaveResult.Saved -> IntakeUiState.Saved(id)
                        is SaveResult.DuplicateFound -> IntakeUiState.Duplicate(result.existingSourceId)
                        SaveResult.StageNotFound -> IntakeUiState.Cancelled
                        SaveResult.Failed -> IntakeUiState.Failed
                    }
                }
            }
        }
    }

    /** For a rejection that happens before any Prepare call, e.g. invalid intent shape. */
    fun showRejected(message: String) {
        _state.value = IntakeUiState.Rejected(message)
    }

    fun cancel() {
        val id = sourceId
        if (id == null) {
            activeInputStream?.let { closeQuietly(it) }
            activeImportJob?.cancel()
            _state.value = IntakeUiState.Cancelled
            return
        }
        viewModelScope.launch {
            (application.vault() as? VaultAccess.Ready)?.importRepository?.cancelStagedImport(id)
            _state.value = IntakeUiState.Cancelled
        }
    }

    private fun closeQuietly(stream: InputStream) {
        try {
            stream.close()
        } catch (_: Exception) {
            // The cancellation path must still transition the UI even if a
            // provider reports an error while releasing its descriptor.
        }
    }

    companion object {
        fun factory(application: OpenLifeApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { IntakeViewModel(application, createSavedStateHandle()) }
        }
    }
}
