package org.openlife.app.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.app.ui.brand.OpenLifeOutlinedButton
import org.openlife.app.ui.brand.PerforationDivider

// P1-08: About's diagnostics counts and its bundled documents (privacy policy, licence report).

@Composable
internal fun DiagnosticsSection(diagnostics: DiagnosticsCounts?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.about_diagnostics_explanation), style = MaterialTheme.typography.bodyMedium)
        if (diagnostics == null) {
            Text(stringResource(R.string.about_document_loading))
            return@Column
        }
        val rows = diagnostics.rows().groupBy { it.group }
        DiagnosticGroup.entries.forEach { group ->
            Text(
                stringResource(groupTitle(group)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
            val groupRows = rows[group].orEmpty()
            when {
                groupRows.isNotEmpty() -> groupRows.forEach { CountRow(it) }
                group == DiagnosticGroup.VAULT_OPEN_FAILURES -> NoneRecorded()
                else -> Text(stringResource(R.string.about_diagnostics_vault_closed))
            }
        }
    }
}

@Composable
private fun CountRow(row: DiagnosticRow) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(diagnosticLabel(row.item)), modifier = Modifier.weight(1f))
        Text(row.count.toString(), style = dataStyle(), modifier = Modifier.padding(start = 16.dp))
    }
}

internal fun LazyListScope.documentSection(
    document: BundledDocument,
    documents: Map<BundledDocument, DocumentState>,
    onToggle: (BundledDocument) -> Unit,
) {
    val open = documents[document]
    val licences = document == BundledDocument.THIRD_PARTY_LICENCES
    item { PerforationDivider() }
    item { SectionHeading(if (licences) R.string.about_section_licences else R.string.about_section_privacy) }
    if (licences) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.about_licences_intro), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.about_font_credits), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    item { Text(stringResource(R.string.about_links_note), style = MaterialTheme.typography.bodyMedium) }
    item {
        OpenLifeOutlinedButton(onClick = { onToggle(document) }) {
            Text(stringResource(toggleLabel(document, open != null)))
        }
    }
    when (open) {
        null -> Unit

        DocumentState.Loading -> item { Text(stringResource(R.string.about_document_loading)) }

        DocumentState.Failed -> item {
            Text(stringResource(R.string.about_document_failed), color = MaterialTheme.colorScheme.error)
        }

        is DocumentState.Shown -> items(open.lines) { line -> DocumentLineText(line) }
    }
}

@Composable
private fun DocumentLineText(line: DocumentLine) {
    if (line.heading) {
        Text(
            line.text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
    } else {
        Text(line.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NoneRecorded() {
    Text(stringResource(R.string.about_diagnostics_no_failures))
}

@StringRes
private fun toggleLabel(document: BundledDocument, open: Boolean): Int = when (document) {
    BundledDocument.PRIVACY_POLICY -> if (open) R.string.about_hide_privacy else R.string.about_show_privacy
    BundledDocument.THIRD_PARTY_LICENCES -> if (open) R.string.about_hide_licences else R.string.about_show_licences
}

@StringRes
private fun groupTitle(group: DiagnosticGroup): Int = when (group) {
    DiagnosticGroup.SOURCES_BY_STATE -> R.string.about_diagnostics_sources
    DiagnosticGroup.LAST_RECOVERY -> R.string.about_diagnostics_recovery
    DiagnosticGroup.VAULT_OPEN_FAILURES -> R.string.about_diagnostics_failures
}
