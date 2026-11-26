package org.openlife.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.StateFlow
import org.openlife.app.AppContainer

/**
 * P2-08: a thin layer over [SearchPresenter], scoped to the Search back-stack
 * entry (P1-20-R1). The query and results are held in memory only (no
 * SavedStateHandle, no route argument) and are dropped by every content scrub
 * (hidden, memory trim), before a vault reset and when the entry is popped.
 */
class SearchViewModel(container: AppContainer) : ViewModel() {
    private val presenter = SearchPresenter(container.searchBackend, viewModelScope)
    private val unregisterScrub = container.scrubber.register { presenter.clear() }
    private val unregisterResetCallback = container.registerPreReset(presenter::clear)

    val query: StateFlow<String> = presenter.query
    val state: StateFlow<SearchUiState> = presenter.state

    fun onQueryChange(text: String) = presenter.onQueryChange(text)

    fun clear() = presenter.clear()

    override fun onCleared() {
        unregisterScrub()
        unregisterResetCallback()
        presenter.clear()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(container) }
        }
    }
}
