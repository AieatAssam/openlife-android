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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.app.ui.brand.OpenLifeTextButton
import org.openlife.vault.facts.ExtractionCheck
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactOrigin
import org.openlife.vault.facts.FactStatus
import org.openlife.vault.facts.ReviewDecision
import java.util.UUID

/**
 * P3-05-R1: the facts of one source. Every badge is text with an icon (never colour alone); a value is plain text,
 * with no link, selection or copy (R3); "Show evidence" reports the fact's evidence to [FactActions.onOpenEvidence].
 */
@Composable
fun FactsPanel(state: FactsUiState, actions: FactActions, modifier: Modifier = Modifier) {
    when (state) {
        FactsUiState.Loading -> Unit
        is FactsUiState.Ready -> FactsList(state, actions, modifier)
    }
}

@Composable
private fun FactsList(state: FactsUiState.Ready, actions: FactActions, modifier: Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.items.any { it.isStale }) {
            item {
                OpenLifeTextButton(onClick = actions.onFindAgain, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(text = stringResource(R.string.facts_find_again), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        val note = checkNote(state.check)
        if (note != null) item { Text(text = stringResource(note)) }
        if (state.items.isEmpty() && state.check == ExtractionCheck.Completed) {
            item { Text(text = stringResource(R.string.facts_none), modifier = Modifier.fillMaxWidth()) }
        }
        items(state.items, key = { it.revisionId.toString() }) { item ->
            FactCard(item, state.currentReadingId, actions)
        }
    }
}

private val INERT_KINDS = setOf(FactKind.URL, FactKind.EMAIL, FactKind.PHONE)

private fun checkNote(check: ExtractionCheck): Int? = when (check) {
    ExtractionCheck.NotChecked -> R.string.facts_not_checked
    ExtractionCheck.Completed -> null
    is ExtractionCheck.Failed -> R.string.facts_check_failed
}

@Composable
private fun FactCard(item: FactItem, currentReadingId: UUID?, actions: FactActions) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(kindLabel(item.kind)), style = MaterialTheme.typography.labelLarge)
        // Plain text on purpose: no link, selection or copy action on a fact value (C2 inert values, R3).
        val locale = LocalConfiguration.current.locales[0]
        Text(
            text = FactDisplay.value(item.kind, item.value.canonicalText, locale),
            style = MaterialTheme.typography.bodyLarge,
            color = if (item.isStale) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (item.isStale) {
            // P3-05-R4: a stale fact is greyed and says why, in words as well as in colour.
            Text(
                text = stringResource(FactDisplay.staleSentence(item.staleReason)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.kind in INERT_KINDS) {
            // P3-05-R3: the note says what the value does not do; the value itself stays plain text.
            Text(text = stringResource(R.string.facts_inert_note), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Decorative: the origin label beside it names the origin.
            Icon(
                imageVector = originIcon(item.origin),
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp),
            )
            Text(text = stringResource(originLabel(item.origin)), style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Decorative: the status label beside it names the status.
            Icon(
                imageVector = statusIcon(item.status),
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp),
            )
            Text(text = stringResource(statusLabel(item.status)), style = MaterialTheme.typography.bodyMedium)
        }
        item.evidence.firstOrNull()?.let { evidence ->
            // P3-05-R1: only evidence on the reading the source shows can be opened; an earlier reading says so.
            if (evidence.ocrRevisionId == currentReadingId?.toString()) {
                OpenLifeTextButton(
                    onClick = { actions.onOpenEvidence(evidence) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(text = stringResource(R.string.facts_show_evidence), modifier = Modifier.fillMaxWidth())
                }
            } else {
                Text(text = stringResource(R.string.facts_earlier_reading), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (item.needsClarification) {
            Text(text = stringResource(R.string.facts_choose_heading), style = MaterialTheme.typography.bodyMedium)
            // Each candidate is its own button with its own stored text; none is shown as the definite value (C2-R36).
            item.candidateTexts.forEachIndexed { index, text ->
                OpenLifeTextButton(
                    onClick = { actions.onChoose(item, index) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(text = FactDisplay.value(item.kind, text, locale), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        FactReviewActions(item, actions)
    }
}

@Composable
private fun FactReviewActions(item: FactItem, actions: FactActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (item.status) {
            FactStatus.UNREVIEWED -> {
                FactActionButton(R.string.facts_confirm) { actions.onReview(item, ReviewDecision.CONFIRM) }
                FactActionButton(R.string.facts_reject) { actions.onReview(item, ReviewDecision.REJECT) }
            }

            FactStatus.CONFIRMED, FactStatus.CLARIFICATION_NEEDED ->
                FactActionButton(R.string.facts_reject) { actions.onReview(item, ReviewDecision.REJECT) }

            FactStatus.REJECTED ->
                FactActionButton(R.string.facts_reopen) { actions.onReview(item, ReviewDecision.REOPEN) }

            // A stale fact is re-extracted, not reviewed; an unrecognised status is never offered an action.
            FactStatus.STALE, FactStatus.UNRECOGNISED -> Unit
        }
    }
}

@Composable
private fun FactActionButton(label: Int, onClick: () -> Unit) {
    OpenLifeTextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text = stringResource(label), modifier = Modifier.fillMaxWidth())
    }
}

internal fun kindLabel(kind: FactKind): Int = when (kind) {
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
