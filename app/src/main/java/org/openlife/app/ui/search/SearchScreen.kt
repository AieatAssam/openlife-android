package org.openlife.app.ui.search

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.app.ui.Announce
import org.openlife.app.ui.Announcement
import org.openlife.app.ui.ContentTextField
import org.openlife.app.ui.StatusHost
import org.openlife.app.ui.asHeading
import org.openlife.app.ui.brand.PerforationDivider
import org.openlife.app.ui.theme.Ink
import org.openlife.app.ui.theme.LocalOpenLifeBrandColors
import org.openlife.app.ui.theme.OpenLifeMono
import org.openlife.vault.search.SearchHit
import java.util.UUID

/**
 * P2-08-R4: local search. The field is the content field (no Copy/Cut, no
 * autocorrect, no keyboard learning; P1-19, A-02). Results are a dense
 * ledger: the extracted or corrected text in the data face with the matched
 * words highlighted (highlighter fill plus underline, never colour alone),
 * whether it is extracted or the user's correction, and the item it is from.
 * Tapping a result opens that item with the span selected. Nothing here is a
 * link; the text is inert (C1-R8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    query: String,
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onOpenResult: (SearchHit) -> Unit,
    onBack: () -> Unit,
    labelFor: @Composable (UUID) -> String? = { null },
) {
    StatusHost {
        val pane = stringResource(R.string.search_title)
        Scaffold(
            modifier = Modifier.semantics { paneTitle = pane },
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            topBar = {
                TopAppBar(
                    title = { Text(pane, modifier = Modifier.asHeading()) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.viewer_back_content_description),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    val label = stringResource(R.string.search_field_label)
                    ContentTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("search_input")
                            .semantics { contentDescription = label },
                        label = { Text(label) },
                        singleLine = true,
                    )
                    SearchResults(state, onOpenResult, labelFor)
                }
            }
        }
    }
}

@Composable
private fun SearchResults(
    state: SearchUiState,
    onOpenResult: (SearchHit) -> Unit,
    labelFor: @Composable (UUID) -> String?,
) {
    when (state) {
        SearchUiState.Idle -> Message(R.string.search_idle)

        SearchUiState.Searching -> Message(R.string.search_searching)

        SearchUiState.NoResults -> Message(R.string.search_no_results)

        is SearchUiState.Results -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            // Keys are positional: the same span can appear as extracted text and as a correction.
            itemsIndexed(state.hits, key = { index, _ -> index }) { index, hit ->
                if (index > 0) PerforationDivider(modifier = Modifier.padding(horizontal = 16.dp))
                ResultRow(hit, labelFor(hit.sourceId), onOpen = { onOpenResult(hit) })
            }
        }
    }
}

@Composable
private fun Message(
    @StringRes textRes: Int,
) {
    Announce(Announcement.statusOf(textRes))
    Text(
        stringResource(textRes),
        modifier = Modifier
            .padding(16.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ResultRow(hit: SearchHit, itemLabel: String?, onOpen: () -> Unit) {
    val openLabel = stringResource(R.string.search_open_action)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("search_result"),
    ) {
        val fact = hit.fact
        if (fact == null) {
            val kind = if (hit.isCorrection) R.string.search_result_corrected else R.string.search_result_extracted
            ResultLabel(stringResource(kind))
        } else {
            // P3-07-R2: kind, status and origin are words, never colour alone.
            ResultLabel(stringResource(factKindName(fact.kind)))
            ResultLabel(stringResource(factStatusName(fact.status)))
            ResultLabel(stringResource(factOriginName(fact.origin)))
        }
        Text(
            highlighted(hit),
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = OpenLifeMono),
            maxLines = SNIPPET_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        itemLabel?.let {
            Text(
                stringResource(R.string.search_result_item, it),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hit.spanId == null) {
            Text(
                stringResource(R.string.search_result_no_location),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The stored fact kind as its label; an unknown value from another build reads as a plain fact. */
private fun factKindName(kind: String): Int = when (kind) {
    "DATE" -> R.string.search_fact_kind_date
    "TIME" -> R.string.search_fact_kind_time
    "DATETIME" -> R.string.search_fact_kind_datetime
    "MONEY" -> R.string.search_fact_kind_money
    "URL" -> R.string.search_fact_kind_url
    "EMAIL" -> R.string.search_fact_kind_email
    "PHONE" -> R.string.search_fact_kind_phone
    else -> R.string.search_fact_unknown
}

private fun factStatusName(status: String): Int = when (status) {
    "UNREVIEWED" -> R.string.search_fact_status_unreviewed
    "CONFIRMED" -> R.string.search_fact_status_confirmed
    "CLARIFICATION_NEEDED" -> R.string.search_fact_status_clarification
    else -> R.string.search_fact_unknown
}

private fun factOriginName(origin: String): Int =
    if (origin == "USER") R.string.search_fact_origin_user else R.string.search_fact_origin_extracted

/** The snippet with each matched range on the evidence highlighter, underlined so it is not colour alone. */
@Composable
private fun highlighted(hit: SearchHit): AnnotatedString {
    // Ink on the highlighter in both themes: light ink on the dark highlighter would not meet contrast.
    val style = SpanStyle(
        background = LocalOpenLifeBrandColors.current.highlighter,
        color = Ink,
        textDecoration = TextDecoration.Underline,
    )
    return buildAnnotatedString {
        append(hit.snippet)
        hit.highlights
            .filter { it.first >= 0 && it.last < hit.snippet.length && !it.isEmpty() }
            .forEach { addStyle(style, it.first, it.last + 1) }
    }
}

private const val SNIPPET_MAX_LINES = 4
