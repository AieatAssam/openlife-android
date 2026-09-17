package org.openlife.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.openlife.app.R

/**
 * Design §8: "Delete names the selected item visually and states that it
 * removes the OpenLife copy only, with no undo." [itemLabel] is the same
 * generic label shown in the list row, so the user can visually confirm
 * which item this is before committing.
 */
@Composable
fun DeleteConfirmationDialog(itemLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title, itemLabel)) },
        text = {
            Text(stringResource(R.string.delete_explanation))
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_action)) } },
    )
}
