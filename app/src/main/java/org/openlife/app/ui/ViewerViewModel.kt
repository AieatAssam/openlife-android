package org.openlife.app.ui

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.openlife.app.AppContainer
import org.openlife.app.navigation.Routes
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.UserContentKind
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.repository.DeleteResult
import org.openlife.vault.repository.ReadyReadResult
import java.util.UUID

/**
 * P1-20-R1/R2/R9: the viewer for one [Routes.ListDetail] entry. Its source id
 * and first span come from the route (SavedStateHandle, typed); the source is
 * observed by id, so the list's state never matters. Content lives in
 * [session] and is dropped by every scrub; the selected span, zoom and pan are
 * the only saved values.
 */
class ViewerViewModel(private val container: AppContainer, handle: SavedStateHandle) : ViewModel() {
    private val route = handle.toRoute<Routes.ListDetail>()

    val session = ViewerSession(
        sourceId = route.sourceId.toUuidOrNull() ?: UNKNOWN_SOURCE,
        initialSpanId = route.spanId?.toUuidOrNull(),
        ports = BitmapViewerPorts(container),
        saved = HandleSavedValues(handle),
        scope = viewModelScope,
    )

    // Hidden: drop now and stay dropped until visible again; trim: drop and reload if visible.
    private val unregisterScrub = container.scrubber.register { hidden ->
        if (hidden) session.onHidden() else session.scrub()
    }

    val sourceId: UUID get() = session.sourceId

    fun extractText() = container.ocr.run(sourceId)

    fun cancelOcr() = container.ocr.cancel(sourceId)

    fun correct(revisionId: UUID, spanId: UUID, correctedText: String) =
        container.ocr.correct(revisionId, spanId, correctedText)

    fun review(revisionId: UUID, reviewState: OcrReviewState) = container.ocr.review(revisionId, reviewState)

    fun rejectSpan(spanId: UUID) = container.ocr.rejectSpan(spanId)

    fun undoRejectSpan(spanId: UUID) = container.ocr.undoRejectSpan(spanId)

    fun setUserContentKind(kind: UserContentKind?) = container.sources.setUserContentKind(sourceId, kind)

    override fun onCleared() {
        unregisterScrub()
        session.close()
    }

    companion object {
        /** A route id that is not a UUID resolves to nothing, so the entry shows "unavailable" and leaves. */
        private val UNKNOWN_SOURCE = UUID(0L, 0L)

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ViewerViewModel(container, createSavedStateHandle()) }
        }
    }
}

/** The app's viewer ports: the container's store and OCR, and a sampled, probed bitmap decode. */
private class BitmapViewerPorts(private val container: AppContainer) : ViewerPorts<Bitmap> {
    override val ocrGeneration: StateFlow<Int> get() = container.ocr.generation

    override fun observeSource(sourceId: UUID): Flow<SourceLookup> = container.sources.observeSource(sourceId)

    override suspend fun readReadyContent(sourceId: UUID): ReadyReadResult =
        container.sources.readReadyContent(sourceId)

    override suspend fun decode(bytes: ByteArray, orientation: Orientation): Bitmap? =
        withContext(Dispatchers.Default) { SampledBitmapDecoder.decode(bytes, orientation)?.also(DecodedBitmapProbe::track) }

    // The session has already replaced the state that showed it, so no new frame draws this bitmap.
    override fun release(value: Bitmap) {
        if (!value.isRecycled) value.recycle()
    }

    override fun ocrState(sourceId: UUID): StateFlow<OcrUiState> = container.ocr.state(sourceId)

    override fun delete(sourceId: UUID, onDeleted: (Boolean) -> Unit) {
        container.sources.delete(sourceId) { result -> onDeleted(result is DeleteResult.Deleted) }
    }
}

private class HandleSavedValues(private val handle: SavedStateHandle) : SavedValues {
    override fun contains(key: String): Boolean = handle.contains(key)

    override fun get(key: String): Any? = handle.get<Any?>(key)

    override fun set(key: String, value: Any?) {
        handle[key] = value
    }
}

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
