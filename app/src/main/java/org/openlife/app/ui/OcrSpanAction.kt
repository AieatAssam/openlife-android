package org.openlife.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.openlife.app.ui.brand.OpenLifeTextButton

/**
 * A per-span OCR action whose spoken name says which span it acts on
 * (P1-19-R11, A-08): a row of identical "Correct" buttons is otherwise
 * indistinguishable to TalkBack.
 */
@Composable
internal fun SpanAction(spokenName: String, onClick: () -> Unit, label: @Composable () -> Unit) {
    OpenLifeTextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = spokenName }) {
        label()
    }
}
