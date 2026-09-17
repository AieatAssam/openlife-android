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
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import java.util.UUID

@Composable
fun IntakeScreen(
    state: IntakeUiState,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onOpenExisting: (UUID) -> Unit = {},
) {
    Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        when (state) {
            IntakeUiState.Preparing -> CenteredMessage(stringResource(R.string.intake_preparing))

            is IntakeUiState.Preview -> PreviewContent(state, onSave, onCancel)

            is IntakeUiState.Saving -> CenteredMessage(stringResource(R.string.intake_saving))

            is IntakeUiState.Saved -> {
                CenteredMessage(stringResource(R.string.intake_saved))
                DoneAfterAcknowledging(onDone)
            }

            is IntakeUiState.Duplicate -> {
                DuplicateContent(state.existingSourceId, onOpenExisting, onDone)
            }

            is IntakeUiState.Rejected -> {
                TerminalMessage(
                    stringResource(R.string.intake_not_imported, stringResource(state.message.resourceId)),
                    onDone,
                )
            }

            IntakeUiState.Busy -> {
                TerminalMessage(stringResource(R.string.intake_busy), onDone)
            }

            IntakeUiState.Failed -> {
                TerminalMessage(stringResource(R.string.intake_failed), onDone)
            }

            is IntakeUiState.VaultUnavailable -> {
                TerminalMessage(stringResource(R.string.intake_vault_unavailable), onDone)
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
            Text(stringResource(R.string.intake_duplicate))
            Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
                Button(onClick = { onOpenExisting(existingSourceId) }) {
                    Text(stringResource(R.string.intake_open_existing))
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.cancel_action))
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
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.intake_selected_image_content_description),
                )
            } else {
                Text(stringResource(R.string.intake_preview_unavailable))
            }
        }
        Text(
            stringResource(
                R.string.intake_preview_details,
                state.format,
                state.width,
                state.height,
                formatBytes(state.byteCount),
            ),
        )
        Text(
            stringResource(R.string.intake_copy_notice),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.padding(end = 8.dp)) {
                Text(stringResource(R.string.cancel_action))
            }
            Button(onClick = onSave, enabled = bitmap != null, modifier = Modifier.testTag("primary_action")) {
                Text(stringResource(R.string.save_action))
            }
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
                Text(stringResource(R.string.intake_done))
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

@Composable
private fun formatBytes(bytes: Long): String = when {
    bytes >= BYTES_PER_MEBIBYTE -> stringResource(
        R.string.intake_size_megabytes,
        bytes / BYTES_PER_MEBIBYTE.toDouble(),
    )
    bytes >= BYTES_PER_KIBIBYTE -> stringResource(
        R.string.intake_size_kilobytes,
        bytes / BYTES_PER_KIBIBYTE.toDouble(),
    )
    else -> stringResource(R.string.intake_size_bytes, bytes)
}

private val IntakeRejectionMessage.resourceId: Int
    get() = when (this) {
        IntakeRejectionMessage.FILE_TOO_LARGE -> R.string.rejection_file_too_large
        IntakeRejectionMessage.UNSUPPORTED_FORMAT -> R.string.rejection_unsupported_format
        IntakeRejectionMessage.DECLARED_FORMAT_MISMATCH -> R.string.rejection_declared_format_mismatch
        IntakeRejectionMessage.CORRUPT_CONTENT -> R.string.rejection_corrupt_content
        IntakeRejectionMessage.ANIMATED_NOT_SUPPORTED -> R.string.rejection_animated_not_supported
        IntakeRejectionMessage.IMAGE_TOO_LARGE -> R.string.rejection_image_too_large
        IntakeRejectionMessage.UNSUPPORTED_ACTION -> R.string.rejection_unsupported_action
        IntakeRejectionMessage.NO_IMAGE -> R.string.rejection_no_image
        IntakeRejectionMessage.MULTIPLE_ITEMS -> R.string.rejection_multiple_items
        IntakeRejectionMessage.UNSUPPORTED_SOURCE -> R.string.rejection_unsupported_source
        IntakeRejectionMessage.INVALID_SOURCE -> R.string.rejection_invalid_source
        IntakeRejectionMessage.MISSING_READ_ACCESS -> R.string.rejection_missing_read_access
        IntakeRejectionMessage.UNSUPPORTED_OR_MISSING_TYPE -> R.string.rejection_unsupported_or_missing_type
        IntakeRejectionMessage.ACCESS_RETRY -> R.string.rejection_access_retry
        IntakeRejectionMessage.TYPE_MISMATCH -> R.string.rejection_type_mismatch
    }
