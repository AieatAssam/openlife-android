package org.openlife.app.ui.search

import androidx.compose.runtime.Composable
import org.openlife.vault.search.SearchHit
import java.util.UUID

/** P2-08-R4: the search route. */
@Composable
fun SearchScreen(
    query: String,
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onOpenResult: (SearchHit) -> Unit,
    onBack: () -> Unit,
    labelFor: (UUID) -> String? = { null },
) {
    TODO("P2-08 RED")
}
