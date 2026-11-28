package org.openlife.app.ui.facts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.app.ui.ContentTextField
import org.openlife.app.ui.brand.OpenLifeButton
import org.openlife.app.ui.brand.OpenLifeTextButton
import org.openlife.vault.facts.FactKind
import java.time.LocalDate

/** The kinds the add-fact form offers (P3-05-R2). Money and time are added in their own steps of P3-05. */
private val FORM_KINDS = listOf(FactKind.URL, FactKind.EMAIL, FactKind.PHONE, FactKind.DATE)

/** P3-05-R2: a kind picker, then the input that kind takes. Nothing is saved until the user presses Save. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun AddFactForm(onAdd: (UserFactInput) -> Unit) {
    var kind by remember { mutableStateOf(FactKind.URL) }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FORM_KINDS.forEach { option ->
                FilterChip(
                    selected = kind == option,
                    onClick = { kind = option },
                    label = { Text(stringResource(kindLabel(option))) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        if (kind == FactKind.DATE) DateInput(onAdd) else TypedInput(kind, onAdd)
    }
}

@Composable
private fun TypedInput(kind: FactKind, onAdd: (UserFactInput) -> Unit) {
    var input by remember(kind) { mutableStateOf("") }
    ContentTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.facts_add_input_label)) },
        singleLine = true,
    )
    SaveButton { onAdd(UserFactInput.Typed(kind, input)) }
}

/** P3-05-R2: a day is chosen in the Material date picker only; there is no free text for a date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateInput(onAdd: (UserFactInput) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    // remember, not rememberSaveable: a picked day is a fact value and must not reach saved instance state.
    val pickerState = remember(locale) { DatePickerState(locale = locale) }
    var open by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<LocalDate?>(null) }
    OpenLifeTextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        val shown = chosen?.let { FactDisplay.value(FactKind.DATE, it.toString(), locale) }
        Text(text = shown ?: stringResource(R.string.facts_pick_date), modifier = Modifier.fillMaxWidth())
    }
    if (open) {
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                OpenLifeTextButton(onClick = {
                    chosen = PickedDate.fromMillis(pickerState.selectedDateMillis)
                    open = false
                }) {
                    Text(stringResource(R.string.facts_picker_confirm), modifier = Modifier.fillMaxWidth())
                }
            },
            dismissButton = {
                OpenLifeTextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.facts_picker_dismiss), modifier = Modifier.fillMaxWidth())
                }
            },
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }
    SaveButton { onAdd(UserFactInput.Date(chosen)) }
}

@Composable
private fun SaveButton(onClick: () -> Unit) {
    OpenLifeButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.facts_add_save), modifier = Modifier.fillMaxWidth())
    }
}
