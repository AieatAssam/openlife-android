package org.openlife.app.ui.facts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openlife.app.R
import org.openlife.app.ui.Announce
import org.openlife.app.ui.Announcement
import org.openlife.app.ui.ContentTextField
import org.openlife.app.ui.StatusHost
import org.openlife.app.ui.asHeading
import org.openlife.app.ui.brand.OpenLifeButton
import org.openlife.app.ui.brand.OpenLifeTextButton
import org.openlife.vault.facts.FactKind

/**
 * P3-05-R1..R2: the facts of one source with its review actions and the add-fact form. The form takes the three
 * text kinds for now; dates, times and money are not offered here yet (recorded as a gap in the step notes).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactsScreen(
    state: FactsUiState,
    message: FactMessage?,
    actions: FactActions,
    onAddText: (FactKind, String) -> Unit,
    onBack: () -> Unit,
) {
    val pane = stringResource(R.string.facts_title)
    var adding by remember { mutableStateOf(false) }
    StatusHost(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            topBar = {
                TopAppBar(
                    title = { FactsTitle() },
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
            Column(modifier = Modifier.fillMaxWidth().padding(padding).semantics { paneTitle = pane }) {
                FactsPanel(state, actions, Modifier.weight(1f))
                message?.let {
                    // Status only: the text names the outcome, never the value.
                    Announce(Announcement.statusOf(messageLabel(it)))
                    Text(text = stringResource(messageLabel(it)), modifier = Modifier.padding(horizontal = 16.dp))
                }
                if (adding) {
                    AddTextFact(onAddText)
                } else {
                    OpenLifeTextButton(
                        onClick = { adding = true },
                        modifier = Modifier.heightIn(min = 48.dp).padding(8.dp),
                    ) {
                        Text(stringResource(R.string.facts_add), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTextFact(onAddText: (FactKind, String) -> Unit) {
    var kind by remember { mutableStateOf(FactKind.URL) }
    var input by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row {
            listOf(FactKind.URL, FactKind.EMAIL, FactKind.PHONE).forEach { option ->
                FilterChip(
                    selected = kind == option,
                    onClick = { kind = option },
                    label = { Text(stringResource(kindLabel(option))) },
                    modifier = Modifier.heightIn(min = 48.dp).padding(end = 8.dp),
                )
            }
        }
        ContentTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.facts_add_input_label)) },
            singleLine = true,
        )
        OpenLifeButton(onClick = { onAddText(kind, input) }) {
            Text(stringResource(R.string.facts_add_save), modifier = Modifier.fillMaxWidth())
        }
    }
}

private val TITLE_MIN_FONT_SIZE = 16.sp

/** One line that scales down to the width the navigation icon leaves at 200 % font (same rule as the list title). */
@Composable
private fun FactsTitle() {
    val style = MaterialTheme.typography.titleLarge
    BasicText(
        stringResource(R.string.facts_title),
        modifier = Modifier.asHeading(),
        style = style.copy(color = LocalContentColor.current),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = TITLE_MIN_FONT_SIZE, maxFontSize = style.fontSize),
    )
}

private fun messageLabel(message: FactMessage): Int = when (message) {
    FactMessage.SAVED -> R.string.facts_message_saved
    FactMessage.REFUSED_BLANK -> R.string.facts_refused_blank
    FactMessage.REFUSED_TOO_LONG -> R.string.facts_refused_too_long
    FactMessage.REFUSED_CONTROL_CHARACTER -> R.string.facts_refused_control
    FactMessage.REFUSED_NOT_ONE_VALUE -> R.string.facts_refused_not_one
    FactMessage.REFUSED_NOT_CHOSEN -> R.string.facts_refused_not_chosen
    FactMessage.NOT_SAVED -> R.string.facts_not_saved
}
