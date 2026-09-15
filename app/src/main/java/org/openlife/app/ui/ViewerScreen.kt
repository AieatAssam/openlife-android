package org.openlife.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrSpan

/**
 * Design §8: "Opening an entry verifies and displays the saved artefact.
 * The details view can show import time, route and integrity status
 * without claiming authenticity." [loadBytes] only ever returns bytes that
 * have already authenticated (design §9); a null result here means
 * verification failed or the content is unreadable, never a shortcut past
 * authentication.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    source: Source,
    loadBytes: suspend () -> ByteArray?,
    onBack: () -> Unit,
    onDeleteRequested: () -> Unit,
    ocrState: OcrUiState = OcrUiState.Idle,
    onExtractText: () -> Unit = {},
    onCancelOcr: () -> Unit = {},
    onCorrect: (OcrSpan, String) -> Unit = { _, _ -> },
    onReview: (OcrReviewState) -> Unit = {},
) {
    val bytes by produceState<ByteArray?>(initialValue = null, source.id) { value = loadBytes() }
    val verified = bytes != null
    var selectedRegion by remember(source.id) { mutableStateOf<org.openlife.vault.ocr.OcrEvidenceRegion?>(null) }
    var correctingSpan by remember(source.id) { mutableStateOf<OcrSpan?>(null) }
    var correctionText by remember(source.id) { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sourceLabel(source)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onDeleteRequested) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
            )
        }
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(modifier = Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    val decoded = remember(bytes) { bytes?.let(SampledBitmapDecoder::decode) }
                    DisposableEffect(decoded) {
                        onDispose {
                            if (decoded != null && !decoded.isRecycled) decoded.recycle()
                        }
                    }
                    when {
                        source.state == SourceState.CORRUPT -> Text("This item's saved content is unreadable.")
                        bytes == null -> Text("Verifying…")
                        decoded == null -> Text("This item's saved content is unreadable.")
                        else -> Image(decoded.asImageBitmap(), contentDescription = null)
                    }
                    if (decoded != null && selectedRegion != null && source.width != null && source.height != null) {
                        val sourceWidth = source.width!!
                        val sourceHeight = source.height!!
                        androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                            val region = selectedRegion!!
                            val left = region.left.toFloat() / sourceWidth * size.width
                            val top = region.top.toFloat() / sourceHeight * size.height
                            val right = region.right.toFloat() / sourceWidth * size.width
                            val bottom = region.bottom.toFloat() / sourceHeight * size.height
                            drawRect(
                                color = Color.Yellow,
                                topLeft = Offset(left, top),
                                size = Size(right - left, bottom - top),
                                style = Stroke(width = 4f),
                            )
                        }
                    }
                }
                DetailsSection(source = source, verified = verified)
                OcrSection(
                    source = source,
                    state = ocrState,
                    onExtractText = onExtractText,
                    onCancel = onCancelOcr,
                    onSelectRegion = { selectedRegion = it },
                    onCorrect = { span ->
                        correctingSpan = span
                        correctionText = span.text
                    },
                    onReview = onReview,
                )
            }
        }
    }

    correctingSpan?.let { span ->
        AlertDialog(
            onDismissRequest = { correctingSpan = null },
            title = { Text("Correct extracted text") },
            text = {
                OutlinedTextField(
                    value = correctionText,
                    onValueChange = { correctionText = it },
                    label = { Text("Your correction") },
                    singleLine = false,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (correctionText.isNotEmpty()) onCorrect(span, correctionText)
                        correctingSpan = null
                    },
                ) { Text("Save correction") }
            },
            dismissButton = { TextButton(onClick = { correctingSpan = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OcrSection(
    source: Source,
    state: OcrUiState,
    onExtractText: () -> Unit,
    onCancel: () -> Unit,
    onSelectRegion: (org.openlife.vault.ocr.OcrEvidenceRegion) -> Unit,
    onCorrect: (OcrSpan) -> Unit,
    onReview: (OcrReviewState) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Extracted text", style = MaterialTheme.typography.titleMedium)
        when (state) {
            OcrUiState.Idle -> Button(onClick = onExtractText, enabled = source.state == SourceState.READY) {
                Text("Extract text on this device")
            }
            is OcrUiState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Extracting locally…")
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            is OcrUiState.Ready -> {
                if (state.spans.isEmpty()) Text("No Latin text was found.")
                state.spans.forEach { span ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(span.text, modifier = Modifier.weight(1f))
                        span.evidenceRegion?.let { region ->
                            TextButton(onClick = { onSelectRegion(region) }) { Text("Show region") }
                        } ?: Text("Ungrounded")
                        TextButton(onClick = { onCorrect(span) }) { Text("Correct") }
                    }
                }
                Row {
                    TextButton(onClick = { onReview(OcrReviewState.ACCEPTED) }) { Text("Accept text") }
                    TextButton(onClick = { onReview(OcrReviewState.REJECTED) }) { Text("Reject") }
                }
            }
            is OcrUiState.Failed -> Text("Text extraction was not accepted: ${ocrFailureMessage(state.reason)}")
            is OcrUiState.Cancelled -> Text("Text extraction cancelled.")
            is OcrUiState.Stale -> Text("Text extraction is stale because the source changed or was deleted.")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Extracted text is untrusted and remains separate from the saved image.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun ocrFailureMessage(reason: OcrFailureReason): String = when (reason) {
    OcrFailureReason.SOURCE_NOT_READY -> "the saved image is not ready"
    OcrFailureReason.SOURCE_CORRUPT -> "the saved image could not be verified"
    OcrFailureReason.SOURCE_DELETED -> "the saved image was deleted"
    OcrFailureReason.LIMIT_EXCEEDED -> "the bounded output limit was exceeded"
    OcrFailureReason.UNSUPPORTED_SCRIPT -> "the image contains unsupported or mixed script text"
    OcrFailureReason.ENGINE_FAILURE -> "the local engine failed"
    OcrFailureReason.CANCELLED -> "the operation was cancelled"
    OcrFailureReason.PROCESS_RESTART -> "the operation was interrupted by a process restart"
}

@Composable
private fun DetailsSection(source: Source, verified: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Details", style = MaterialTheme.typography.titleMedium)
        DetailRow("Imported", sourceLabel(source))
        DetailRow("Route", if (source.intakeKind.name == "SHARE") "Shared to OpenLife" else "Selected from photos")
        DetailRow(
            "Integrity",
            when {
                source.state == SourceState.CORRUPT -> "Unreadable — not verified"
                verified -> "Verified against the saved copy"
                else -> "Not yet verified"
            }
        )
        Text(
            "Verification confirms this is the exact copy OpenLife saved. It does not confirm " +
                "who sent it or that its contents are true.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Text("$label: $value", modifier = Modifier.padding(top = 4.dp))
}
