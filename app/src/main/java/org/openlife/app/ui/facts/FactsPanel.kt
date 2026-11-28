package org.openlife.app.ui.facts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.vault.facts.ExtractionCheck
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactOrigin
import org.openlife.vault.facts.FactStatus
import org.openlife.vault.facts.FactEvidence

/**
 * P3-05-R1: the facts of one source. Every badge is text with an icon (never colour alone); a value is plain text,
 * with no link, selection or copy (R3); "Show in the text" reports the fact's evidence to [onOpenEvidence].
 */
@Composable
fun FactsPanel(
    state: FactsUiState,
    onOpenEvidence: (FactEvidence) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        FactsUiState.Loading -> Unit
        is FactsUiState.Ready -> FactsList(state, onOpenEvidence, modifier)
    }
}

@Composable
private fun FactsList(
    state: FactsUiState.Ready,
    onOpenEvidence: (FactEvidence) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.facts_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        val note = checkNote(state.check)
        if (note != null) item { Text(text = stringResource(note)) }
        if (state.items.isEmpty() && state.check == ExtractionCheck.Completed) {
            item { Text(text = stringResource(R.string.facts_none)) }
        }
        items(state.items, key = { it.revisionId.toString() }) { item ->
            FactCard(item, onOpenEvidence)
        }
    }
}

private fun checkNote(check: ExtractionCheck): Int? = when (check) {
    ExtractionCheck.NotChecked -> R.string.facts_not_checked
    ExtractionCheck.Completed -> null
    is ExtractionCheck.Failed -> R.string.facts_check_failed
}

@Composable
private fun FactCard(item: FactItem, onOpenEvidence: (FactEvidence) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(kindLabel(item.kind)), style = MaterialTheme.typography.labelLarge)
        // Plain text on purpose: no link, selection or copy action on a fact value (C2 inert values, R3).
        Text(text = item.value.canonicalText, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = originIcon(item.origin), contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text(text = stringResource(originLabel(item.origin)), style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = statusIcon(item.status), contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text(text = stringResource(statusLabel(item.status)), style = MaterialTheme.typography.bodyMedium)
        }
        item.evidence.firstOrNull()?.let { evidence ->
            TextButton(
                onClick = { onOpenEvidence(evidence) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.facts_show_evidence))
            }
        }
    }
}

private fun kindLabel(kind: FactKind): Int = when (kind) {
    FactKind.DATE -> R.string.facts_kind_date
    FactKind.TIME -> R.string.facts_kind_time
    FactKind.DATETIME -> R.string.facts_kind_datetime
    FactKind.MONEY -> R.string.facts_kind_money
    FactKind.URL -> R.string.facts_kind_url
    FactKind.EMAIL -> R.string.facts_kind_email
    FactKind.PHONE -> R.string.facts_kind_phone
    FactKind.UNRECOGNISED -> R.string.facts_kind_unrecognised
}

private fun originLabel(origin: FactOrigin): Int = when (origin) {
    FactOrigin.USER -> R.string.facts_origin_user
    else -> R.string.facts_origin_extracted
}

private fun originIcon(origin: FactOrigin) = when (origin) {
    FactOrigin.USER -> Icons.Filled.Person
    else -> Icons.Filled.Info
}

private fun statusLabel(status: FactStatus): Int = when (status) {
    FactStatus.CONFIRMED -> R.string.facts_status_confirmed
    FactStatus.REJECTED -> R.string.facts_status_rejected
    FactStatus.CLARIFICATION_NEEDED -> R.string.facts_status_clarification
    FactStatus.STALE -> R.string.facts_status_stale
    else -> R.string.facts_status_unreviewed
}

private fun statusIcon(status: FactStatus) = when (status) {
    FactStatus.CONFIRMED -> Icons.Filled.Check
    FactStatus.REJECTED -> Icons.Filled.Close
    FactStatus.CLARIFICATION_NEEDED, FactStatus.STALE -> Icons.Filled.Warning
    else -> Icons.Filled.Info
}
