package org.openlife.app.ui.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.openlife.vault.search.SearchHit
import org.openlife.vault.search.SearchQuery

/** What search needs from the vault; the app wires it to SearchRepository behind the app lock. */
fun interface SearchBackend {
    fun search(query: String): Flow<List<SearchHit>>
}

sealed interface SearchUiState {
    /** Nothing typed: no query runs. */
    data object Idle : SearchUiState

    data object Searching : SearchUiState

    data object NoResults : SearchUiState

    data class Results(val hits: List<SearchHit>) : SearchUiState
}

/**
 * P2-08-R4/R7: the query and its results live only here, in memory: never in
 * saved instance state and never in a log. [clear] (called on onStop, trim
 * and reset) drops both.
 */
class SearchPresenter(
    private val backend: SearchBackend,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) {
    private val mutableQuery = MutableStateFlow("")
    private val mutableState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)

    val query: StateFlow<String> = mutableQuery.asStateFlow()
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

    private var search: Job? = null

    /** Searches as the user types (debounced); only the latest query's results are shown. */
    @Synchronized
    fun onQueryChange(text: String) {
        val bounded = text.take(MAX_QUERY_LENGTH)
        mutableQuery.value = bounded
        search?.cancel()
        search = null
        if (SearchQuery.tokens(bounded).isEmpty()) {
            mutableState.value = SearchUiState.Idle
            return
        }
        mutableState.value = SearchUiState.Searching
        search = scope.launch {
            if (debounceMillis > 0) delay(debounceMillis)
            backend.search(bounded)
                .catch { error ->
                    // Shown as no results; never logged (the message can echo the query).
                    if (error is CancellationException) throw error
                    emit(emptyList())
                }
                .collect { hits ->
                    mutableState.value = if (hits.isEmpty()) SearchUiState.NoResults else SearchUiState.Results(hits)
                }
        }
    }

    /** P2-08-R7: drops the query and every result; a running search stops. */
    @Synchronized
    fun clear() {
        search?.cancel()
        search = null
        mutableQuery.value = ""
        mutableState.value = SearchUiState.Idle
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 200L
        const val MAX_QUERY_LENGTH = 200
    }
}
