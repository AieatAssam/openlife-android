package org.openlife.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.lazy.LazyListState
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
import org.openlife.app.AppContainer
import org.openlife.vault.model.Source
import org.openlife.vault.model.UserContentKind
import org.openlife.vault.repository.DeleteResult
import org.openlife.vault.storage.VaultUnavailableCause
import java.util.UUID

sealed interface SourceListUiState {
    data object Loading : SourceListUiState
    data class Loaded(val sources: List<Source>) : SourceListUiState
    data class VaultUnavailable(val cause: VaultUnavailableCause) : SourceListUiState
}

/**
 * Backs the list destination (design §8: "ordered by import time and uses
 * generic labels. Thumbnails are generated in memory on demand."). Scoped to
 * the list's back-stack entry (P1-20-R1) and built from the [AppContainer].
 * Thumbnails are decoded on request and cached only in memory - never written
 * to disk (design §9) - and dropped by every content scrub (P1-20-R4).
 */
class SourceListViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<SourceListUiState>(SourceListUiState.Loading)
    val state: StateFlow<SourceListUiState> = _state

    // Evicted thumbnails are dropped, never recycled: a row that is still
    // composed may draw the same instance again (F-28, P1-13-R6). From API 26
    // on, bitmap pixel memory is released when the bitmap is collected.
    private val thumbnailCache = SensitiveContentCache<UUID, Bitmap?>(
        onEvict = {},
        sizeOf = { bitmap -> bitmap?.allocationByteCount?.toLong() ?: 0L },
    )
    private val unregisterScrub = container.scrubber.register { clearSensitiveContent() }
    private val _sensitiveContentGeneration = MutableStateFlow(thumbnailCache.generation())
    val sensitiveContentGeneration: StateFlow<Long> = _sensitiveContentGeneration.asStateFlow()
    private var vaultObservation: Job? = null

    /** The list's scroll position (indices only, no content); shared by the list pane in every entry. */
    val listPosition = LazyListState()

    init {
        observeVault()
    }

    fun retryVault() {
        viewModelScope.launch {
            container.sources.retryVault()
            observeVault()
        }
    }

    fun finishVaultReset() {
        viewModelScope.launch {
            if (container.sources.finishVaultReset()) observeVault()
        }
    }

    private fun observeVault() {
        vaultObservation?.cancel()
        vaultObservation = viewModelScope.launch {
            container.sources.observeList().collect { _state.value = it }
        }
    }

    suspend fun loadThumbnail(sourceId: UUID): Bitmap? {
        val cached = thumbnailCache.get(sourceId)
        return when {
            cached != null -> cached

            thumbnailCache.contains(sourceId) -> null

            else -> {
                val generation = thumbnailCache.generation()
                val bitmap = container.sources.loadThumbnail(sourceId, THUMBNAIL_TARGET_PIXELS)
                if (thumbnailCache.put(sourceId, bitmap, generation)) bitmap else null
            }
        }
    }

    /** Runs in the container's application scope, so it completes after this entry is gone. */
    fun delete(sourceId: UUID, onResult: (DeleteResult) -> Unit = {}) {
        container.sources.delete(sourceId) { result ->
            if (result is DeleteResult.Deleted) thumbnailCache.remove(sourceId)
            result?.let(onResult)
        }
    }

    /**
     * P2-07-R3: stores the user's answer; the source flow carries the stored
     * value back to the viewer. Not a safety judgement.
     */
    fun setUserContentKind(sourceId: UUID, kind: UserContentKind?) = container.sources.setUserContentKind(sourceId, kind)

    /** Drop decoded thumbnails (hidden, memory trim, vault reset). */
    fun clearSensitiveContent() {
        thumbnailCache.clear()
        _sensitiveContentGeneration.value = thumbnailCache.generation()
    }

    override fun onCleared() {
        unregisterScrub()
        clearSensitiveContent()
    }

    companion object {
        private const val THUMBNAIL_TARGET_PIXELS = 40_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SourceListViewModel(container) }
        }
    }
}
