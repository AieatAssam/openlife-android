package org.openlife.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.SourceState
import org.openlife.vault.repository.ImageRejectionReason
import org.openlife.vault.repository.ImportLimits
import org.openlife.vault.repository.PrepareResult
import org.openlife.vault.repository.SaveResult
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private fun describeImageRejection(reason: ImageRejectionReason): IntakeRejectionMessage = when (reason) {
    ImageRejectionReason.EXCEEDS_BYTE_LIMIT -> IntakeRejectionMessage.FILE_TOO_LARGE
    ImageRejectionReason.UNSUPPORTED_FORMAT -> IntakeRejectionMessage.UNSUPPORTED_FORMAT
    ImageRejectionReason.DECLARED_FORMAT_MISMATCH -> IntakeRejectionMessage.DECLARED_FORMAT_MISMATCH
    ImageRejectionReason.CORRUPT_CONTENT -> IntakeRejectionMessage.CORRUPT_CONTENT
    ImageRejectionReason.ANIMATED_NOT_SUPPORTED -> IntakeRejectionMessage.ANIMATED_NOT_SUPPORTED
    ImageRejectionReason.EXCEEDS_DIMENSION_LIMIT -> IntakeRejectionMessage.IMAGE_TOO_LARGE
}

private const val KEY_SOURCE_ID = "org.openlife.app.ui.IntakeViewModel.sourceId"

private const val PROVIDER_READ_GRACE_MILLIS = 5_000L
private const val PROVIDER_READ_DEADLINE_MILLIS = ImportLimits.PROVIDER_READ_DEADLINE_SECONDS * 1_000L
private const val PROVIDER_READ_DEADLINE_NANOS = ImportLimits.PROVIDER_READ_DEADLINE_SECONDS * 1_000_000_000L

/** One daemon thread preserves provider descriptor open/read/close affinity. */
private val PROVIDER_READ_DISPATCHER: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "OpenLife-provider-read").apply { isDaemon = true }
}.asCoroutineDispatcher()

/** Provider open failures are mapped to the same retryable intake outcome. */
private class ProviderOpenException(cause: Throwable? = null) : IOException(cause)

/**
 * Backs the intake preview screen hosted by `IntakeActivity`. Design §8:
 * rotation may retain the active preview only through a ViewModel and
 * Source UUID kept in [SavedStateHandle] — never plaintext image bytes in
 * saved instance state. On process recreation, [restorePreviewIfNeeded]
 * re-authenticates the stage from disk rather than trusting anything held
 * in memory across the recreation.
 */
@Suppress("TooManyFunctions")
class IntakeViewModel(
    private val application: OpenLifeApp,
    private val savedStateHandle: SavedStateHandle,
    private val providerDispatcher: CoroutineDispatcher = PROVIDER_READ_DISPATCHER,
) : ViewModel() {

    private val _state = MutableStateFlow<IntakeUiState>(IntakeUiState.Preparing)
    val state: StateFlow<IntakeUiState> = _state

    /** The active provider read. Its owner coroutine performs normal closure. */
    private var activeImportJob: Job? = null
    private var backgrounded = false

    private var sourceId: UUID?
        get() = savedStateHandle.get<String>(KEY_SOURCE_ID)?.let(UUID::fromString)
        set(value) {
            savedStateHandle[KEY_SOURCE_ID] = value?.toString()
        }

    init {
        sourceId?.let { restorePreviewIfNeeded(it) }
    }

    fun startImport(stream: InputStream, declaredMimeType: String, intakeKind: IntakeKind) {
        // Compatibility for callers that already opened a stream. The
        // production intake boundary uses the opener overload below, so it
        // owns open/read/close on PROVIDER_READ_DISPATCHER. If cancellation
        // wins before this pre-opened stream can be claimed, close it from
        // the completion callback rather than leaking it.
        val claimed = AtomicBoolean(false)
        startImportInternal(
            openStream = {
                if (!claimed.compareAndSet(false, true)) throw CancellationException()
                stream
            },
            declaredMimeType = declaredMimeType,
            intakeKind = intakeKind,
            preOpenedStream = stream,
            preOpenedClaim = claimed,
        )
    }

    /**
     * Starts an import with an opener retained at the app intake boundary.
     * Opening and reading happen on the same single-permit dispatcher, so the
     * coroutine that opens the provider descriptor also owns normal closure.
     */
    fun startImport(openStream: () -> InputStream, declaredMimeType: String, intakeKind: IntakeKind) {
        startImportInternal(openStream, declaredMimeType, intakeKind)
    }

    private fun startImportInternal(
        openStream: () -> InputStream,
        declaredMimeType: String,
        intakeKind: IntakeKind,
        preOpenedStream: InputStream? = null,
        preOpenedClaim: AtomicBoolean? = null,
    ) {
        activeImportJob?.cancel()
        val importJob = viewModelScope.launch {
            try {
                withContext(providerDispatcher) {
                    when (val access = application.vault()) {
                        is VaultAccess.Unavailable -> _state.value = IntakeUiState.VaultUnavailable(access.reason)

                        is VaultAccess.Ready -> importFromProvider(
                            openStream,
                            declaredMimeType,
                            intakeKind,
                            access,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ProviderOpenException) {
                _state.value = IntakeUiState.Rejected(IntakeRejectionMessage.ACCESS_RETRY)
            }
        }
        activeImportJob = importJob
        if (preOpenedStream != null && preOpenedClaim != null) {
            importJob.invokeOnCompletion {
                if (preOpenedClaim.compareAndSet(false, true)) closeQuietly(preOpenedStream)
            }
        }
    }

    private suspend fun importFromProvider(
        openStream: () -> InputStream,
        declaredMimeType: String,
        intakeKind: IntakeKind,
        access: VaultAccess.Ready,
    ) {
        val input = try {
            openStream()
        } catch (error: SecurityException) {
            throw ProviderOpenException(error)
        } catch (error: IOException) {
            throw ProviderOpenException(error)
        }

        val streamReference = AtomicReference(input)
        val ownerClosed = AtomicBoolean(false)
        val watchdog = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            delay(
                PROVIDER_READ_DEADLINE_MILLIS + PROVIDER_READ_GRACE_MILLIS,
            )
            val stuckStream = streamReference.get()
            // This is deliberately the last resort. Normal closure is done by
            // the owner coroutine after the bounded reader returns. A provider
            // that ignores the cooperative deadline may require close() from
            // this watchdog thread; see docs/THREAT_MODEL.md.
            if (stuckStream != null && ownerClosed.compareAndSet(false, true)) {
                closeQuietly(stuckStream)
            }
        }

        val deadlineNanos = System.nanoTime() + PROVIDER_READ_DEADLINE_NANOS
        var result: PrepareResult? = null
        try {
            result = try {
                withTimeout(PROVIDER_READ_DEADLINE_MILLIS) {
                    access.importRepository.prepareImport(
                        input,
                        declaredMimeType,
                        intakeKind,
                        deadline = { System.nanoTime() >= deadlineNanos },
                    )
                }
            } catch (_: TimeoutCancellationException) {
                PrepareResult.Failed
            }
        } finally {
            withContext(NonCancellable) {
                // The normal owner-thread close is in this finally block.
                // The watchdog can only win after the deadline plus grace
                // if the provider keeps the owner blocked in read().
                closeOwnedStream(input, ownerClosed)
                streamReference.set(null)
                watchdog.cancelAndJoin()
            }
        }
        // The terminal state is published only after the descriptor is
        // closed by the same coroutine that opened it.
        applyPrepareResult(result, access)
    }

    private fun closeOwnedStream(stream: InputStream, ownerClosed: AtomicBoolean) {
        if (ownerClosed.compareAndSet(false, true)) closeQuietly(stream)
    }

    private suspend fun applyPrepareResult(result: PrepareResult, access: VaultAccess.Ready) {
        _state.value = when (result) {
            is PrepareResult.Prepared -> {
                sourceId = result.sourceId
                if (backgrounded) {
                    // Keep only the UUID while stopped. The authenticated
                    // stage can be reloaded after the activity returns.
                    IntakeUiState.Preparing
                } else {
                    val previewBytes = access.viewRepository.loadStagePreviewBytes(result.sourceId)
                    IntakeUiState.Preview(
                        result.sourceId,
                        result.format,
                        result.width,
                        result.height,
                        result.byteCount,
                        previewBytes,
                        result.orientation,
                    )
                }
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
                        id,
                        source.mimeType!!,
                        source.width!!,
                        source.height!!,
                        source.byteCount!!,
                        previewBytes,
                        source.orientation ?: org.openlife.vault.model.Orientation.NORMAL,
                    )
                }
            }
        }
    }

    fun confirmSave() {
        val preview = _state.value as? IntakeUiState.Preview ?: return
        // A staged row can outlive a failed authentication/read on process
        // recreation. Keep Save unavailable in that case; the UI gate is
        // backed by the same invariant at the ViewModel boundary.
        if (preview.previewBytes == null) return
        val id = preview.sourceId
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
    fun showRejected(message: IntakeRejectionMessage) {
        _state.value = IntakeUiState.Rejected(message)
    }

    fun cancel() {
        val id = sourceId
        if (id == null) {
            val importJob = activeImportJob
            importJob?.cancel()
            viewModelScope.launch {
                importJob?.join()
                _state.value = IntakeUiState.Cancelled
            }
            return
        }
        viewModelScope.launch {
            activeImportJob?.cancelAndJoin()
            (application.vault() as? VaultAccess.Ready)?.importRepository?.cancelStagedImport(id)
            _state.value = IntakeUiState.Cancelled
        }
    }

    /** Clear authenticated preview bytes before the activity becomes hidden. */
    fun clearSensitiveContentForBackground() {
        backgrounded = true
        if (_state.value is IntakeUiState.Preview) {
            _state.value = IntakeUiState.Preparing
        }
    }

    /** Re-authenticate the retained staged UUID after returning to the foreground. */
    fun restoreSensitiveContentAfterForeground() {
        backgrounded = false
        if (_state.value == IntakeUiState.Preparing) {
            sourceId?.let { restorePreviewIfNeeded(it) }
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

    override fun onCleared() {
        // The owner coroutine closes normally. If a provider ignores both
        // cancellation and the cooperative deadline, the independent
        // deadline+grace watchdog performs the documented cross-thread
        // last-resort close; do not close an unknown descriptor here.
        activeImportJob?.cancel()
    }

    companion object {
        fun factory(application: OpenLifeApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { IntakeViewModel(application, createSavedStateHandle()) }
        }
    }
}
