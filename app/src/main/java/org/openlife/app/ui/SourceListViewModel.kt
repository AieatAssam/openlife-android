package org.openlife.app.ui

import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.vault.model.Source
import org.openlife.vault.repository.DeleteResult
import java.util.UUID

sealed interface SourceListUiState {
    data object Loading : SourceListUiState
    data class Loaded(val sources: List<Source>) : SourceListUiState
    data class VaultUnavailable(val reason: String) : SourceListUiState
}

/**
 * Backs [MainActivity]'s source list (design §8: "ordered by import time
 * and uses generic labels. Thumbnails are generated in memory on
 * demand."). Thumbnails are decoded on request and cached only in memory
 * for this process's lifetime - never written to disk (design §9: "Do not
 * store plaintext image caches or thumbnails").
 */
class SourceListViewModel(private val application: OpenLifeApp) : ViewModel() {

    private val _state = MutableStateFlow<SourceListUiState>(SourceListUiState.Loading)
    val state: StateFlow<SourceListUiState> = _state

    private val thumbnailCache = SensitiveContentCache<UUID, android.graphics.Bitmap?> { bitmap ->
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }
    private val _sensitiveContentGeneration = MutableStateFlow(thumbnailCache.generation())
    val sensitiveContentGeneration: StateFlow<Long> = _sensitiveContentGeneration.asStateFlow()

    init {
        viewModelScope.launch {
            when (val access = application.vault()) {
                is VaultAccess.Unavailable -> _state.value = SourceListUiState.VaultUnavailable(access.reason)

                is VaultAccess.Ready -> access.viewRepository.observeVisibleSources().collect { sources ->
                    _state.value = SourceListUiState.Loaded(sources)
                }
            }
        }
    }

    suspend fun loadThumbnail(sourceId: UUID): android.graphics.Bitmap? {
        val cached = thumbnailCache.get(sourceId)
        return when {
            cached != null -> cached

            thumbnailCache.contains(sourceId) -> null

            else -> {
                val generation = thumbnailCache.generation()
                val access = application.vault() as? VaultAccess.Ready
                if (access == null) {
                    null
                } else {
                    val bitmap = withContext(Dispatchers.Default) {
                        val bytes = access.viewRepository.loadReadyBytes(sourceId)
                        if (bytes == null) {
                            null
                        } else {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            var sampleSize = 1
                            val targetPixels = THUMBNAIL_TARGET_PIXELS
                            while ((bounds.outWidth / sampleSize).toLong() *
                                (bounds.outHeight / sampleSize) > targetPixels
                            ) {
                                sampleSize *= 2
                            }
                            BitmapFactory.decodeByteArray(
                                bytes,
                                0,
                                bytes.size,
                                BitmapFactory.Options().apply { inSampleSize = sampleSize },
                            )
                        }
                    }
                    if (thumbnailCache.put(sourceId, bitmap, generation)) bitmap else null
                }
            }
        }
    }

    fun delete(sourceId: UUID, onResult: (DeleteResult) -> Unit) {
        viewModelScope.launch {
            val access = application.vault() as? VaultAccess.Ready ?: return@launch
            val result = access.deletionRepository.deleteSource(sourceId)
            if (result is DeleteResult.Deleted) thumbnailCache.remove(sourceId)
            onResult(result)
        }
    }

    /** Drop decoded thumbnails when the app leaves the foreground. */
    fun clearSensitiveContent() {
        thumbnailCache.clear()
        _sensitiveContentGeneration.value = thumbnailCache.generation()
    }

    override fun onCleared() {
        clearSensitiveContent()
        super.onCleared()
    }

    companion object {
        private const val THUMBNAIL_TARGET_PIXELS = 40_000L

        fun factory(application: OpenLifeApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { SourceListViewModel(application) }
        }
    }
}
