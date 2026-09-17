package org.openlife.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

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
        title = { Text("Delete $itemLabel?") },
        text = {
            Text(
                "This removes OpenLife's copy of this item from this device. " +
                    "There is no undo, and no backup to restore it from.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
