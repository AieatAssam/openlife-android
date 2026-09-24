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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openlife.app.R
import org.openlife.app.ui.brand.FoldedCornerCard
import org.openlife.app.ui.brand.StampBadge
import org.openlife.app.ui.brand.StampState
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
            IntakeUiState.Preparing -> PreparingContent(onCancel)

            is IntakeUiState.Preview -> PreviewContent(state, onSave, onCancel)

            is IntakeUiState.Saving -> CenteredMessage(stringResource(R.string.intake_saving))

            is IntakeUiState.Saved -> {
                CenteredMessage(
                    stringResource(R.string.intake_saved),
                    stampState = StampState.Saved,
                    onDone = onDone,
                )
                DoneAfterAcknowledging(onDone)
            }

            is IntakeUiState.Duplicate -> {
                DuplicateContent(state.existingSourceId, onOpenExisting, onDone)
            }

            is IntakeUiState.Rejected -> {
                TerminalMessage(
                    stringResource(R.string.intake_not_imported, stringResource(state.message.stringRes)),
                    onDone,
                )
            }

            IntakeUiState.Busy -> {
                TerminalMessage(stringResource(R.string.intake_busy), onDone)
            }

            IntakeUiState.Failed -> {
                TerminalMessage(stringResource(R.string.intake_failed), onDone)
            }

            IntakeUiState.StorageUnavailable -> {
                TerminalMessage(stringResource(R.string.intake_storage_unavailable), onDone)
            }

            is IntakeUiState.VaultUnavailable -> {
                TerminalMessage(stringResource(R.string.intake_vault_unavailable), onDone)
            }

            // Leaving is a side effect; run it once per Cancelled state rather
            // than on every recomposition (F-43).
            IntakeUiState.Cancelled -> LaunchedEffect(state) { onDone() }
        }
    }
}

/** Preparing can be cancelled: the read stops, the stream closes and no stage is kept (P1-13-R8). */
@Composable
private fun PreparingContent(onCancel: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.intake_preparing))
            OutlinedButton(onClick = onCancel, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.cancel_action))
            }
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
    // The details scroll; Cancel and Save stay pinned below them, so the
    // decision is always on screen at any window size or font scale
    // (VISUAL_IDENTITY §7: primary action at the bottom).
    Column(modifier = Modifier.fillMaxSize()) {
        // P1-13-R2: decode off the main thread. The preview owns this bitmap,
        // so it is recycled when replaced or when the preview leaves.
        val preview by produceState<PreviewImage>(PreviewImage.Loading, state.previewBytes, state.orientation) {
            val bytes = state.previewBytes
            value = if (bytes == null) {
                PreviewImage.Unavailable
            } else {
                withContext(Dispatchers.Default) { SampledBitmapDecoder.decode(bytes, state.orientation) }
                    ?.let(PreviewImage::Shown) ?: PreviewImage.Unavailable
            }
        }
        val current = preview
        DisposableEffect(current) {
            onDispose { (current as? PreviewImage.Shown)?.bitmap?.let { if (!it.isRecycled) it.recycle() } }
        }
        val bitmap = (current as? PreviewImage.Shown)?.bitmap
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, top = 24.dp, end = 24.dp),
        ) {
            FoldedCornerCard {
                Box(modifier = Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    when {
                        bitmap != null -> Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.intake_selected_image_content_description),
                        )

                        current == PreviewImage.Loading -> Text(stringResource(R.string.intake_preparing))

                        else -> Text(stringResource(R.string.intake_preview_unavailable))
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
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(16.dp),
                )
                Text(
                    stringResource(R.string.intake_copy_notice),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                // Design §3 R3 / F-47: the picker may serve items from a cloud
                // media provider; say so, and that OpenLife itself never uploads.
                if (state.intakeKind == org.openlife.vault.model.IntakeKind.PHOTO_PICKER) {
                    Text(
                        stringResource(R.string.intake_cloud_provider_notice),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.End) {
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
private fun CenteredMessage(text: String, stampState: StampState? = null, onDone: (() -> Unit)? = null) {
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            stampState?.let { StampBadge(it, modifier = Modifier.padding(bottom = 16.dp)) }
            Text(text)
            onDone?.let {
                Button(onClick = it, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.intake_done))
                }
            }
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

private sealed interface PreviewImage {
    data object Loading : PreviewImage
    class Shown(val bitmap: android.graphics.Bitmap) : PreviewImage
    data object Unavailable : PreviewImage
}
