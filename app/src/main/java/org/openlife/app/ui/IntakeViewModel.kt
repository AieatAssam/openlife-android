package org.openlife.app.ui

import androidx.core.net.toUri
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.app.intake.IntakeIntentValidator
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.SourceState
import org.openlife.vault.repository.ImageRejectionReason
import org.openlife.vault.repository.ImportLimits
import org.openlife.vault.repository.PrepareResult
import org.openlife.vault.repository.SaveResult
import java.io.FileNotFoundException
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
    ImageRejectionReason.STORAGE_UNAVAILABLE -> IntakeRejectionMessage.STORAGE_UNAVAILABLE
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
    private var uriImportRequested = false
    private var attached = false
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        val importJob = viewModelScope.launch { runImport(openStream, declaredMimeType, intakeKind) }
        activeImportJob = importJob
        if (preOpenedStream != null && preOpenedClaim != null) {
            importJob.invokeOnCompletion {
                if (preOpenedClaim.compareAndSet(false, true)) closeQuietly(preOpenedStream)
            }
        }
    }

    private suspend fun runImport(openStream: () -> InputStream, declaredMimeType: String, intakeKind: IntakeKind) {
        // Refuse a second import at once. Without this, the request would
        // queue behind the first import's read on the single provider thread
        // and only learn it is Busy when that read ends. prepareImport keeps
        // the authoritative check.
        val early = application.vault()
        if (early is VaultAccess.Ready && early.importRepository.importSlot.isOccupied) {
            _state.value = IntakeUiState.Busy
            return
        }
        try {
            withContext(providerDispatcher) {
                when (val access = application.vault()) {
                    is VaultAccess.Unavailable -> _state.value = IntakeUiState.VaultUnavailable(access.cause)
                    is VaultAccess.Ready -> importFromProvider(openStream, declaredMimeType, intakeKind, access)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ProviderOpenException) {
            _state.value = IntakeUiState.Rejected(IntakeRejectionMessage.ACCESS_RETRY)
        }
    }

    /**
     * Starts an import from a validated `content://` URI (P1-13-R8, F-37).
     *
     * The provider type lookup and the open run in [viewModelScope] on the
     * provider-owned thread, so recreating the activity during Preparing
     * cannot cancel them. The URI string lives only in this object's memory:
     * it is never written to [SavedStateHandle], so a new process never
     * reopens an unconfirmed item from a URI (design §8). Repeated calls
     * after the first, for example from a recreated activity, are ignored.
     */
    fun startImportFromUri(uriString: String, intentMimeType: String?, intakeKind: IntakeKind) {
        if (uriImportRequested) return
        uriImportRequested = true
        val resolver = application.contentResolver
        val uri = uriString.toUri()
        activeImportJob?.cancel()
        activeImportJob = viewModelScope.launch {
            // getType is not part of the descriptor open/read/close sequence
            // that must stay on the provider thread (P1-09). It runs in its own
            // IO scope and is awaited cancellably: a provider stalling here
            // blocks neither other imports nor Cancel, and an abandoned lookup
            // finishes on its own and is discarded.
            val lookup = lookupScope.async { providerTypeOf(resolver, uri) }.await()
            val providerType = (lookup as? ProviderTypeLookup.Known)?.mimeType
            when {
                lookup == ProviderTypeLookup.Refused ->
                    _state.value = IntakeUiState.Rejected(IntakeRejectionMessage.ACCESS_RETRY)

                providerType == null || !IntakeIntentValidator.mimeTypesMatch(intentMimeType, providerType) ->
                    _state.value = IntakeUiState.Rejected(IntakeRejectionMessage.TYPE_MISMATCH)

                else -> runImport(
                    openStream = { resolver.openInputStream(uri) ?: throw FileNotFoundException() },
                    declaredMimeType = providerType,
                    intakeKind = intakeKind,
                )
            }
        }
    }

    /**
     * Records that an activity is using this instance. Returns true when one
     * already was: the activity is being recreated for a configuration
     * change and this ViewModel, with its import, survived.
     */
    fun attach(): Boolean {
        val wasAttached = attached
        attached = true
        return wasAttached
    }

    /**
     * The activity was restored from saved state into a new process. With
     * neither an import nor a stage there is nothing to continue: ask the
     * user to select the item again rather than leaving Preparing up forever
     * (design §8: a new process must not restore from a URI).
     */
    fun onRestoredAfterProcessDeath() {
        val ownsWork = activeImportJob?.isActive == true || uriImportRequested || sourceId != null
        if (!ownsWork && _state.value == IntakeUiState.Preparing) {
            _state.value = IntakeUiState.Rejected(IntakeRejectionMessage.ACCESS_RETRY)
        }
    }

    private fun providerTypeOf(resolver: android.content.ContentResolver, uri: android.net.Uri): ProviderTypeLookup =
        try {
            ProviderTypeLookup.Known(resolver.getType(uri)?.lowercase())
        } catch (_: SecurityException) {
            ProviderTypeLookup.Refused
        } catch (_: FileNotFoundException) {
            ProviderTypeLookup.Refused
        }

    /** A provider that refuses access differs from one that reports no type. */
    private sealed interface ProviderTypeLookup {
        data object Refused : ProviderTypeLookup
        data class Known(val mimeType: String?) : ProviderTypeLookup
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

            is PrepareResult.Rejected -> if (result.reason == ImageRejectionReason.STORAGE_UNAVAILABLE) {
                IntakeUiState.StorageUnavailable
            } else {
                IntakeUiState.Rejected(describeImageRejection(result.reason))
            }

            PrepareResult.Busy -> IntakeUiState.Busy

            PrepareResult.StorageUnavailable -> IntakeUiState.StorageUnavailable

            PrepareResult.Failed -> IntakeUiState.Failed
        }
    }

    private fun restorePreviewIfNeeded(id: UUID) {
        viewModelScope.launch {
            when (val access = application.vault()) {
                is VaultAccess.Unavailable -> _state.value = IntakeUiState.VaultUnavailable(access.cause)

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
                is VaultAccess.Unavailable -> _state.value = IntakeUiState.VaultUnavailable(access.cause)

                is VaultAccess.Ready -> {
                    _state.value = when (val result = access.importRepository.saveImport(id)) {
                        is SaveResult.Saved -> IntakeUiState.Saved(id)
                        is SaveResult.DuplicateFound -> IntakeUiState.Duplicate(result.existingSourceId)
                        SaveResult.StageNotFound -> IntakeUiState.Cancelled
                        SaveResult.StorageUnavailable -> IntakeUiState.StorageUnavailable
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
        lookupScope.cancel()
        sourceId?.let(application::releaseImportSlot)
    }

    companion object {
        fun factory(application: OpenLifeApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { IntakeViewModel(application, createSavedStateHandle()) }
        }
    }
}
