package org.openlife.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.repository.ReadyReadResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A source looked up by id, independent of the list (P1-20-R2). */
sealed interface SourceLookup {
    data object Loading : SourceLookup

    data class Found(val source: Source) : SourceLookup

    /** Not in the vault (deleted, never saved, or the vault is unavailable). */
    data object Missing : SourceLookup
}

/** The decoded image a viewer holds; [T] is a Bitmap in the app and a fake on the JVM. */
sealed interface DecodedContent<out T> {
    data object Loading : DecodedContent<Nothing>

    data class Shown<T>(val value: T) : DecodedContent<T>

    data object Transient : DecodedContent<Nothing>

    data object Unreadable : DecodedContent<Nothing>
}

/** What a viewer needs from the container. */
interface ViewerPorts<T : Any> {
    /** Changes when OCR text was dropped; the viewer asks for [ocrState] again. */
    val ocrGeneration: StateFlow<Int>

    fun observeSource(sourceId: UUID): Flow<SourceLookup>

    /** Awaits the app lock; only authenticated bytes are returned (design §9). */
    suspend fun readReadyContent(sourceId: UUID): ReadyReadResult

    suspend fun decode(bytes: ByteArray, orientation: Orientation): T?

    fun release(value: T)

    fun ocrState(sourceId: UUID): StateFlow<OcrUiState>

    /** Runs beyond the screen (application scope); [onDeleted] reports whether the source is gone. */
    fun delete(sourceId: UUID, onDeleted: (Boolean) -> Unit)
}

/** The non-content values a viewer keeps across process death; backed by SavedStateHandle in the app. */
interface SavedValues {
    operator fun contains(key: String): Boolean

    operator fun get(key: String): Any?

    operator fun set(key: String, value: Any?)
}

/**
 * P1-20-R2/R4/R9: one viewer entry's state, loaded by source id from the
 * repository, never from the list. Decoded content and OCR text are held
 * only while the entry is visible and are dropped synchronously by [scrub]
 * and [onHidden] (A-06: not by recomposition); the source id stays, so the
 * destination survives and reloads on [onVisible]. The selection, zoom and
 * pan are saved as a UUID string and floats; nothing else is saved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerSession<T : Any>(
    val sourceId: UUID,
    initialSpanId: UUID?,
    private val ports: ViewerPorts<T>,
    private val saved: SavedValues,
    private val scope: CoroutineScope,
) {
    private val mutableSource = MutableStateFlow<SourceLookup>(SourceLookup.Loading)
    private val slot = ContentSlot<T>(ports::release)
    private val mutableOcr = MutableStateFlow<OcrUiState>(OcrUiState.Loading)
    private val mutableSelected = MutableStateFlow(saved.restoredSelection(initialSpanId))
    private val mutableExit = MutableStateFlow(false)
    private val exitConsumed = AtomicBoolean(false)
    private val attempt = MutableStateFlow(0)
    private var visible = false
    private var contentJob: Job? = null
    private var ocrJob: Job? = null

    val source: StateFlow<SourceLookup> = mutableSource.asStateFlow()
    val content: StateFlow<DecodedContent<T>> = slot.state
    val ocr: StateFlow<OcrUiState> = mutableOcr.asStateFlow()
    val selectedSpanId: StateFlow<UUID?> = mutableSelected.asStateFlow()

    /** The source is gone (deleted here or elsewhere): the destination returns to the list once. */
    val exitRequested: StateFlow<Boolean> = mutableExit.asStateFlow()

    val zoom: Float get() = saved[KEY_ZOOM] as? Float ?: DEFAULT_ZOOM
    val panX: Float get() = saved[KEY_PAN_X] as? Float ?: 0f
    val panY: Float get() = saved[KEY_PAN_Y] as? Float ?: 0f

    // Metadata only (no bytes, no text); it decides "no longer available".
    private val sourceJob = scope.launch {
        ports.observeSource(sourceId).collect { lookup ->
            mutableSource.value = lookup
            if (lookup == SourceLookup.Missing) mutableExit.value = true
        }
    }

    @Synchronized
    fun onVisible() {
        if (visible) return
        visible = true
        startLoading()
    }

    @Synchronized
    fun onHidden() {
        visible = false
        drop()
    }

    /** Drops held content now; a visible entry reads it again (memory trim). */
    @Synchronized
    fun scrub() {
        drop()
        if (visible) startLoading()
    }

    fun retry() {
        attempt.value += 1
    }

    fun selectSpan(spanId: UUID?) {
        mutableSelected.value = spanId
        saved[KEY_SELECTED_SPAN] = spanId?.toString()
    }

    fun setViewport(zoom: Float, panX: Float, panY: Float) {
        saved[KEY_ZOOM] = zoom
        saved[KEY_PAN_X] = panX
        saved[KEY_PAN_Y] = panY
    }

    fun delete() {
        ports.delete(sourceId) { deleted -> if (deleted) mutableExit.value = true }
    }

    /** True once, after an exit was requested (A-10: the flow and the delete callback pop once). */
    fun consumeExit(): Boolean = mutableExit.value && exitConsumed.compareAndSet(false, true)

    @Synchronized
    fun close() {
        sourceJob.cancel()
        visible = false
        drop()
    }

    private fun startLoading() {
        val loadEpoch = slot.epoch
        contentJob = scope.launch {
            val found = mutableSource.filterIsInstance<SourceLookup.Found>()
                .map { it.source.state to (it.source.orientation ?: Orientation.NORMAL) }
                .distinctUntilChanged()
            combine(found, attempt) { current, _ -> current }.collectLatest { (state, orientation) ->
                slot.install(DecodedContent.Loading, loadEpoch)
                slot.install(ports.loadContent(sourceId, state, orientation), loadEpoch)
            }
        }
        ocrJob = scope.launch {
            ports.ocrGeneration.flatMapLatest { ports.ocrState(sourceId) }.collect { mutableOcr.value = it }
        }
    }

    private fun drop() {
        contentJob?.cancel()
        ocrJob?.cancel()
        contentJob = null
        ocrJob = null
        slot.clear()
        mutableOcr.value = OcrUiState.Loading
    }

    companion object {
        const val KEY_SELECTED_SPAN = "selectedSpanId"
        const val KEY_ZOOM = "zoom"
        const val KEY_PAN_X = "panX"
        const val KEY_PAN_Y = "panY"
        private const val DEFAULT_ZOOM = 1f
    }
}

/**
 * Holds the decoded content. A replaced or dropped value is released at once;
 * a result older than the last [clear] is never shown.
 */
private class ContentSlot<T : Any>(private val release: (T) -> Unit) {
    private val mutable = MutableStateFlow<DecodedContent<T>>(DecodedContent.Loading)
    val state: StateFlow<DecodedContent<T>> = mutable.asStateFlow()

    @Volatile
    var epoch = 0L
        private set

    @Synchronized
    fun install(next: DecodedContent<T>, loadEpoch: Long) {
        if (loadEpoch != epoch) {
            (next as? DecodedContent.Shown)?.let { release(it.value) }
            return
        }
        val previous = mutable.value
        mutable.value = next
        if (previous is DecodedContent.Shown && previous != next) release(previous.value)
    }

    @Synchronized
    fun clear() {
        epoch++
        val previous = mutable.value
        mutable.value = DecodedContent.Loading
        (previous as? DecodedContent.Shown)?.let { release(it.value) }
    }
}

private suspend fun <T : Any> ViewerPorts<T>.loadContent(
    sourceId: UUID,
    state: SourceState,
    orientation: Orientation,
): DecodedContent<T> {
    if (state == SourceState.CORRUPT) return DecodedContent.Unreadable
    return when (val result = readReadyContent(sourceId)) {
        is ReadyReadResult.Loaded -> {
            val decoded = try {
                decode(result.bytes, orientation)
            } finally {
                result.bytes.fill(0)
            }
            decoded?.let { DecodedContent.Shown(it) } ?: DecodedContent.Unreadable
        }

        ReadyReadResult.Transient -> DecodedContent.Transient

        ReadyReadResult.Corrupt, ReadyReadResult.Missing, ReadyReadResult.Unavailable -> DecodedContent.Unreadable
    }
}

private fun SavedValues.restoredSelection(initialSpanId: UUID?): UUID? = if (ViewerSession.KEY_SELECTED_SPAN in this) {
    (this[ViewerSession.KEY_SELECTED_SPAN] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
} else {
    initialSpanId
}
