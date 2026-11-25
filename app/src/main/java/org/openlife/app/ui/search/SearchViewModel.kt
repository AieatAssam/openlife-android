package org.openlife.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.vault.search.SearchHit

/**
 * P2-08: a thin layer over [SearchPresenter]. The query and results are held
 * in memory only (no SavedStateHandle, no saved instance state) and are
 * dropped when the activity stops (MainActivity.onStop), on memory trim and
 * before a vault reset.
 */
class SearchViewModel(application: OpenLifeApp) : ViewModel() {
    private val presenter = SearchPresenter(AppSearchBackend(application), viewModelScope)
    private val unregisterTrimCallback = application.registerSensitiveContentClearer(presenter::clear)
    private val unregisterResetCallback = application.registerPreResetCallback(presenter::clear)

    val query: StateFlow<String> = presenter.query
    val state: StateFlow<SearchUiState> = presenter.state

    fun onQueryChange(text: String) = presenter.onQueryChange(text)

    /** P2-08-R7: called from onStop. */
    fun clear() = presenter.clear()

    override fun onCleared() {
        unregisterTrimCallback()
        unregisterResetCallback()
        presenter.clear()
    }

    companion object {
        fun factory(application: OpenLifeApp): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(application) }
        }
    }
}

/** The vault behind the app lock: search reads content, so it waits for unlock (ADR-0004). */
private class AppSearchBackend(private val application: OpenLifeApp) : SearchBackend {
    override fun search(query: String): Flow<List<SearchHit>> = flow {
        application.appLock.awaitContentAccess()
        when (val access = application.vault()) {
            is VaultAccess.Ready -> emitAll(access.searchRepository.search(query))
            is VaultAccess.Unavailable -> emit(emptyList())
        }
    }
}
