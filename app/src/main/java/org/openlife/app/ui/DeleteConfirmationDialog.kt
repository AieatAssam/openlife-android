package org.openlife.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.app.ui.brand.FoldedCornerCard
import org.openlife.app.ui.brand.PerforationDivider

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
            FoldedCornerCard {
                Text(
                    stringResource(R.string.delete_explanation),
                    modifier = androidx.compose.ui.Modifier.padding(16.dp),
                )
                PerforationDivider(modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp))
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = onConfirm,
                modifier = androidx.compose.ui.Modifier.testTag("delete_confirm"),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.delete_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_action)) } },
    )
}
