package org.openlife.app.ui.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openlife.vault.search.SearchHit

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

    fun onQueryChange(text: String): Unit = TODO("P2-08 RED")

    fun clear(): Unit = TODO("P2-08 RED")

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 200L
        const val MAX_QUERY_LENGTH = 200
    }
}
