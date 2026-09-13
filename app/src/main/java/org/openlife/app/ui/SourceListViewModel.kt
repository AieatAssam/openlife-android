package org.openlife.app.ui

import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.vault.model.Source
import org.openlife.vault.repository.DeleteResult

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

    private val thumbnailCache = mutableMapOf<UUID, android.graphics.Bitmap?>()

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
        thumbnailCache[sourceId]?.let { return it }
        if (thumbnailCache.containsKey(sourceId)) return null // cached negative result
        val access = application.vault() as? VaultAccess.Ready ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            val bytes = access.viewRepository.loadReadyBytes(sourceId) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sampleSize = 1
            val targetPixels = 40_000L // small list thumbnail budget
            while ((bounds.outWidth / sampleSize).toLong() * (bounds.outHeight / sampleSize) > targetPixels) {
                sampleSize *= 2
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        }
        thumbnailCache[sourceId] = bitmap
        return bitmap
    }

    fun delete(sourceId: UUID, onResult: (DeleteResult) -> Unit) {
        viewModelScope.launch {
            val access = application.vault() as? VaultAccess.Ready ?: return@launch
            val result = access.deletionRepository.deleteSource(sourceId)
            if (result is DeleteResult.Deleted) thumbnailCache.remove(sourceId)
            onResult(result)
        }
    }

    companion object {
        fun factory(application: OpenLifeApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { SourceListViewModel(application) }
        }
    }
}
