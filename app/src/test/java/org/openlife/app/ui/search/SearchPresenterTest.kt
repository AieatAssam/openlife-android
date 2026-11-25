package org.openlife.app.ui.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.search.SearchHit
import java.util.UUID

/** P2-08-R4/R7: query to results, nothing held after clear, bounded input, failures shown as no results. */
class SearchPresenterTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val results = MutableStateFlow<List<SearchHit>>(emptyList())
    private val queries = mutableListOf<String>()
    private val presenter = SearchPresenter(
        backend = { query ->
            queries += query
            results
        },
        scope = scope,
        debounceMillis = 0,
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun typingShowsTheBackendsResults() {
        results.value = listOf(hit("dentist"))
        presenter.onQueryChange("dentist")
        assertEquals("dentist", presenter.query.value)
        assertEquals(SearchUiState.Results(listOf(hit("dentist"))), presenter.state.value)

        results.value = listOf(hit("dentist"), hit("dentist bill"))
        assertEquals("a later database change updates the list", 2, (presenter.state.value as SearchUiState.Results).hits.size)
    }

    @Test
    fun aBlankQueryIsIdleAndSearchesNothing() {
        presenter.onQueryChange("   ")
        assertEquals(SearchUiState.Idle, presenter.state.value)
        assertEquals(emptyList<String>(), queries)
    }

    @Test
    fun noHitsIsNoResults() {
        presenter.onQueryChange("walrus")
        assertEquals(SearchUiState.NoResults, presenter.state.value)
    }

    @Test
    fun clearDropsTheQueryAndResultsAndStopsListening() {
        results.value = listOf(hit("ferry"))
        presenter.onQueryChange("ferry")
        presenter.clear()
        assertEquals("", presenter.query.value)
        assertEquals(SearchUiState.Idle, presenter.state.value)

        results.value = listOf(hit("ferry"), hit("ferry two"))
        assertEquals("no result arrives after clear", SearchUiState.Idle, presenter.state.value)
    }

    @Test
    fun theQueryIsBounded() {
        presenter.onQueryChange("x".repeat(1_000))
        assertEquals(SearchPresenter.MAX_QUERY_LENGTH, presenter.query.value.length)
    }

    @Test
    fun aFailingBackendShowsNoResults() {
        val failing = SearchPresenter(
            backend = { _ -> flow<List<SearchHit>> { throw IllegalStateException("synthetic") } },
            scope = scope,
            debounceMillis = 0,
        )
        failing.onQueryChange("anything")
        assertEquals(SearchUiState.NoResults, failing.state.value)
    }

    @Test
    fun onlyTheLatestQueryIsShown() {
        val slow = MutableStateFlow<List<SearchHit>>(emptyList())
        val perQuery = mutableMapOf<String, Flow<List<SearchHit>>>("old" to slow, "new" to results)
        val presenter = SearchPresenter({ perQuery.getValue(it) }, scope, debounceMillis = 0)
        presenter.onQueryChange("old")
        results.value = listOf(hit("new"))
        presenter.onQueryChange("new")
        slow.value = listOf(hit("old"))
        assertTrue(presenter.state.value is SearchUiState.Results)
        assertEquals("new", (presenter.state.value as SearchUiState.Results).hits.single().snippet)
    }

    private fun hit(snippet: String) = SearchHit(
        sourceId = UUID(1, 1),
        revisionId = UUID(2, 2),
        spanId = UUID(3, snippet.length.toLong()),
        snippet = snippet,
        isCorrection = false,
    )
}
