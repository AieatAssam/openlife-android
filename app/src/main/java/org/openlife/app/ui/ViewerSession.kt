package org.openlife.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.repository.ReadyReadResult
import java.util.UUID

/** A source looked up by id, independent of the list (P1-20-R2). */
sealed interface SourceLookup {
    data object Loading : SourceLookup

    data class Found(val source: Source) : SourceLookup

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
    val ocrGeneration: StateFlow<Int>

    fun observeSource(sourceId: UUID): Flow<SourceLookup>

    suspend fun readReadyContent(sourceId: UUID): ReadyReadResult

    suspend fun decode(bytes: ByteArray, orientation: Orientation): T?

    fun release(value: T)

    fun ocrState(sourceId: UUID): StateFlow<OcrUiState>

    fun delete(sourceId: UUID, onDeleted: (Boolean) -> Unit)
}

/** The non-content values a viewer keeps across process death; backed by SavedStateHandle in the app. */
interface SavedValues {
    operator fun get(key: String): Any?

    operator fun set(key: String, value: Any?)
}

/**
 * P1-20-R2/R4/R9: one viewer entry's state, loaded by source id. Decoded
 * content and OCR text are held only while the entry is visible and are
 * dropped synchronously by [scrub]; the selection, zoom and pan are saved as
 * a UUID string and floats.
 */
@Suppress("UnusedPrivateProperty")
class ViewerSession<T : Any>(
    val sourceId: UUID,
    initialSpanId: UUID?,
    private val ports: ViewerPorts<T>,
    private val saved: SavedValues,
    private val scope: CoroutineScope,
) {
    val source: StateFlow<SourceLookup> get() = TODO()
    val content: StateFlow<DecodedContent<T>> get() = TODO()
    val ocr: StateFlow<OcrUiState> get() = TODO()
    val selectedSpanId: StateFlow<UUID?> get() = TODO()
    val exitRequested: StateFlow<Boolean> get() = TODO()
    val zoom: Float get() = TODO()
    val panX: Float get() = TODO()
    val panY: Float get() = TODO()

    fun onVisible(): Unit = TODO()

    fun onHidden(): Unit = TODO()

    fun scrub(): Unit = TODO()

    fun retry(): Unit = TODO()

    fun selectSpan(spanId: UUID?): Unit = TODO()

    fun setViewport(zoom: Float, panX: Float, panY: Float): Unit = TODO()

    fun delete(): Unit = TODO()

    fun consumeExit(): Boolean = TODO()

    fun close(): Unit = TODO()

    companion object {
        const val KEY_SELECTED_SPAN = "selectedSpanId"
        const val KEY_ZOOM = "zoom"
        const val KEY_PAN_X = "panX"
        const val KEY_PAN_Y = "panY"
    }
}
