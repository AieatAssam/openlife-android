package org.openlife.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

@Composable
fun IntakeScreen(state: IntakeUiState, onSave: () -> Unit, onCancel: () -> Unit, onDone: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        when (state) {
            IntakeUiState.Preparing -> CenteredMessage("Preparing…")

            is IntakeUiState.Preview -> PreviewContent(state, onSave, onCancel)

            is IntakeUiState.Saving -> CenteredMessage("Saving…")

            is IntakeUiState.Saved -> {
                CenteredMessage("Saved on this device")
                DoneAfterAcknowledging(onDone)
            }

            is IntakeUiState.Duplicate -> {
                CenteredMessage("This matches something already saved. Not imported again.")
                DoneAfterAcknowledging(onDone)
            }

            is IntakeUiState.Rejected -> {
                CenteredMessage("Not imported: ${state.message}")
                DoneAfterAcknowledging(onDone)
            }

            IntakeUiState.Busy -> {
                CenteredMessage("Another import is already in progress. Finish or cancel it first.")
                DoneAfterAcknowledging(onDone)
            }

            IntakeUiState.Failed -> {
                CenteredMessage("Import failed. Please try again.")
                DoneAfterAcknowledging(onDone)
            }

            is IntakeUiState.VaultUnavailable -> {
                CenteredMessage("The vault is unavailable right now.")
                DoneAfterAcknowledging(onDone)
            }

            IntakeUiState.Cancelled -> onDone()
        }
    }
}

@Composable
private fun PreviewContent(state: IntakeUiState.Preview, onSave: () -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        val bitmap = remember(state.previewBytes) { state.previewBytes?.let(SampledBitmapDecoder::decode) }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Selected image preview")
            } else {
                Text("Preview unavailable")
            }
        }
        Text("${state.format} · ${state.width}×${state.height} · ${formatBytes(state.byteCount)}")
        Text(
            "OpenLife stores its own copy of this image. The original stays with the app you shared it from.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.padding(end = 8.dp)) { Text("Cancel") }
            Button(onClick = onSave, enabled = bitmap != null) { Text("Save") }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text)
        }
    }
}

/** Terminal states auto-advance after a moment rather than requiring a tap on a screen the user didn't ask to keep open. */
@Composable
private fun DoneAfterAcknowledging(onDone: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1500)
        onDone()
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
