package org.openlife.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
fun IntakeScreen(
    state: IntakeUiState,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onOpenExisting: (UUID) -> Unit = {},
) {
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
                DuplicateContent(state.existingSourceId, onOpenExisting, onDone)
            }

            is IntakeUiState.Rejected -> {
                TerminalMessage("Not imported: ${state.message}", onDone)
            }

            IntakeUiState.Busy -> {
                TerminalMessage("Another import is already in progress. Finish or cancel it first.", onDone)
            }

            IntakeUiState.Failed -> {
                TerminalMessage("Import failed. Please try again.", onDone)
            }

            is IntakeUiState.VaultUnavailable -> {
                TerminalMessage("The vault is unavailable right now.", onDone)
            }

            IntakeUiState.Cancelled -> onDone()
        }
    }
}

@Composable
private fun DuplicateContent(existingSourceId: UUID, onOpenExisting: (UUID) -> Unit, onCancel: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("This matches something already saved. Not imported again.")
            Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
                Button(onClick = { onOpenExisting(existingSourceId) }) {
                    Text("Open existing")
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun PreviewContent(state: IntakeUiState.Preview, onSave: () -> Unit, onCancel: () -> Unit) {
    Column(
        // Intake details and actions must remain reachable at large font
        // scales; scrolling is preferable to clipping the confirmation row.
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        val bitmap = remember(state.previewBytes) { state.previewBytes?.let(SampledBitmapDecoder::decode) }
        DisposableEffect(bitmap) {
            onDispose {
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
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
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text)
        }
    }
}

@Composable
private fun TerminalMessage(text: String, onDone: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text)
            Button(onClick = onDone, modifier = Modifier.padding(top = 16.dp)) {
                Text("Done")
            }
        }
    }
}

/** Successful terminal states auto-advance after a moment; errors stay visible until acknowledged. */
@Composable
private fun DoneAfterAcknowledging(onDone: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Keep terminal feedback visible long enough for a slow first-run
        // device/provider boundary to render it before auto-returning.
        kotlinx.coroutines.delay(SUCCESS_MESSAGE_DURATION_MILLIS)
        onDone()
    }
}

private const val SUCCESS_MESSAGE_DURATION_MILLIS = 5_000L
private const val BYTES_PER_KIBIBYTE = 1024L
private const val BYTES_PER_MEBIBYTE = BYTES_PER_KIBIBYTE * BYTES_PER_KIBIBYTE

private fun formatBytes(bytes: Long): String = when {
    bytes >= BYTES_PER_MEBIBYTE -> "%.1f MB".format(bytes / BYTES_PER_MEBIBYTE.toDouble())
    bytes >= BYTES_PER_KIBIBYTE -> "%.1f KB".format(bytes / BYTES_PER_KIBIBYTE.toDouble())
    else -> "$bytes B"
}
