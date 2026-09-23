package org.openlife.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.app.ui.brand.FoldedCornerCard
import org.openlife.app.ui.brand.PerforationDivider
import org.openlife.app.ui.brand.StampBadge
import org.openlife.app.ui.brand.StampState
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.repository.ReadyReadResult

/**
 * Design §8: "Opening an entry verifies and displays the saved artefact.
 * The details view can show import time, route and integrity status
 * without claiming authenticity." [loadContent] only ever yields bytes that
 * have already authenticated (design §9); every other result is explained,
 * never left on "Verifying…" (P1-13-R4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    source: Source,
    loadContent: suspend () -> ReadyReadResult,
    onBack: () -> Unit,
    onDeleteRequested: () -> Unit,
    ocrState: OcrUiState = OcrUiState.Idle,
    onExtractText: () -> Unit = {},
    onCancelOcr: () -> Unit = {},
    onCorrect: (OcrSpan, String) -> Unit = { _, _ -> },
    onReview: (OcrReviewState) -> Unit = {},
) {
    var loadAttempt by remember(source.id) { mutableIntStateOf(0) }
    val content = rememberViewerContent(source, loadContent, loadAttempt)
    var selectedRegion by remember(source.id) { mutableStateOf<org.openlife.vault.ocr.OcrEvidenceRegion?>(null) }
    var correctingSpan by remember(source.id) { mutableStateOf<OcrSpan?>(null) }
    var correctionText by remember(source.id) { mutableStateOf("") }
    var confirmingDelete by remember(source.id) { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = { ViewerTopBar(source, onBack, onDelete = { confirmingDelete = true }) },
    ) { padding ->
        ViewerContent(
            modifier = Modifier.fillMaxSize().padding(padding),
            source = source,
            content = content,
            selectedRegion = selectedRegion,
            onRetryLoad = { loadAttempt++ },
            onDeleteRequested = { confirmingDelete = true },
            ocrState = ocrState,
            onExtractText = onExtractText,
            onCancelOcr = onCancelOcr,
            onSelectRegion = { selectedRegion = it },
            onCorrect = { span ->
                correctingSpan = span
                correctionText = span.text
            },
            onReview = onReview,
        )
    }

    ViewerCorrectionDialog(
        span = correctingSpan,
        correctionText = correctionText,
        onCorrectionTextChange = { correctionText = it },
        onDismiss = { correctingSpan = null },
        onSave = onCorrect,
    )

    // Design §8 step 5: delete names the item and is confirmed from every
    // entry point, including this one.
    if (confirmingDelete) {
        DeleteConfirmationDialog(
            itemLabel = sourceLabel(source),
            onConfirm = {
                confirmingDelete = false
                onDeleteRequested()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerTopBar(source: Source, onBack: () -> Unit, onDelete: () -> Unit) {
    TopAppBar(
        title = { Text(sourceLabel(source)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.viewer_back_content_description),
                )
            }
        },
        actions = {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.viewer_delete_content_description),
                )
            }
        },
    )
}

@Composable
private fun ViewerContent(
    modifier: Modifier,
    source: Source,
    content: ViewerContentState,
    selectedRegion: org.openlife.vault.ocr.OcrEvidenceRegion?,
    onRetryLoad: () -> Unit,
    onDeleteRequested: () -> Unit,
    ocrState: OcrUiState,
    onExtractText: () -> Unit,
    onCancelOcr: () -> Unit,
    onSelectRegion: (org.openlife.vault.ocr.OcrEvidenceRegion) -> Unit,
    onCorrect: (OcrSpan) -> Unit,
    onReview: (OcrReviewState) -> Unit,
) {
    Surface(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ViewerImage(source, content, selectedRegion, onRetry = onRetryLoad, onDelete = onDeleteRequested)
            if (selectedRegion != null) {
                // TalkBack does not receive visual Canvas changes by itself.
                // Announce the evidence action as a polite live region while
                // keeping the untrusted image content inert.
                Text(
                    stringResource(R.string.viewer_evidence_region_highlighted),
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // What the user came for (the text and its evidence) comes before
            // the bookkeeping about the saved copy.
            OcrSection(
                source = source,
                state = ocrState,
                onExtractText = onExtractText,
                onCancel = onCancelOcr,
                onSelectRegion = onSelectRegion,
                onCorrect = onCorrect,
                onReview = onReview,
            )
            PerforationDivider(modifier = Modifier.padding(horizontal = 16.dp))
            DetailsSection(source = source, verified = content is ViewerContentState.Shown)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerCorrectionDialog(
    span: OcrSpan?,
    correctionText: String,
    onCorrectionTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (OcrSpan, String) -> Unit,
) {
    span?.let { currentSpan ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.viewer_correct_title)) },
            text = {
                val correctionLabel = stringResource(R.string.viewer_correction_label)
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = correctionText,
                        onValueChange = onCorrectionTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("viewer_correction_input")
                            .semantics { contentDescription = correctionLabel },
                        label = { Text(correctionLabel) },
                        minLines = 1,
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (correctionText.isNotEmpty()) onSave(currentSpan, correctionText)
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.viewer_save_correction)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_action)) }
            },
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
        Text(stringResource(R.string.viewer_extracted_text_title), style = MaterialTheme.typography.titleMedium)
        when (state) {
            OcrUiState.Idle -> Button(onClick = onExtractText, enabled = source.state == SourceState.READY) {
                Text(stringResource(R.string.viewer_extract_text_action))
            }

            is OcrUiState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.viewer_extracting))
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel_action)) }
            }

            is OcrUiState.Ready -> {
                if (state.spans.isEmpty()) Text(stringResource(R.string.viewer_no_latin_text))
                state.spans.forEach { span ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(span.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        span.evidenceRegion?.let { region ->
                            TextButton(onClick = { onSelectRegion(region) }) {
                                Text(stringResource(R.string.viewer_show_region))
                            }
                        } ?: Text(stringResource(R.string.viewer_ungrounded))
                        TextButton(onClick = { onCorrect(span) }) { Text(stringResource(R.string.viewer_correct)) }
                    }
                }
                Row {
                    TextButton(onClick = { onReview(OcrReviewState.ACCEPTED) }) {
                        Text(stringResource(R.string.viewer_accept_text))
                    }
                    TextButton(onClick = { onReview(OcrReviewState.REJECTED) }) {
                        Text(stringResource(R.string.viewer_reject_text))
                    }
                }
            }

            is OcrUiState.Failed -> Text(
                stringResource(R.string.viewer_extraction_failed, ocrFailureMessage(state.reason)),
            )

            is OcrUiState.Cancelled -> Text(stringResource(R.string.viewer_extraction_cancelled))

            is OcrUiState.Stale -> Text(stringResource(R.string.viewer_extraction_stale))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.viewer_untrusted_notice),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ocrFailureMessage(reason: OcrFailureReason): String = when (reason) {
    OcrFailureReason.SOURCE_NOT_READY -> stringResource(R.string.ocr_failure_source_not_ready)
    OcrFailureReason.SOURCE_CORRUPT -> stringResource(R.string.ocr_failure_source_corrupt)
    OcrFailureReason.SOURCE_DELETED -> stringResource(R.string.ocr_failure_source_deleted)
    OcrFailureReason.LIMIT_EXCEEDED -> stringResource(R.string.ocr_failure_limit_exceeded)
    OcrFailureReason.UNSUPPORTED_SCRIPT -> stringResource(R.string.ocr_failure_unsupported_script)
    OcrFailureReason.ENGINE_FAILURE -> stringResource(R.string.ocr_failure_engine)
    OcrFailureReason.CANCELLED -> stringResource(R.string.ocr_failure_cancelled)
    OcrFailureReason.PROCESS_RESTART -> stringResource(R.string.ocr_failure_process_restart)
    OcrFailureReason.TIMEOUT -> stringResource(R.string.ocr_failure_timeout)
    OcrFailureReason.STORAGE_FAILURE -> stringResource(R.string.ocr_failure_storage)
}

@Composable
private fun DetailsSection(source: Source, verified: Boolean) {
    FoldedCornerCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.viewer_details_title), style = MaterialTheme.typography.titleMedium)
            DetailRow(stringResource(R.string.viewer_imported_label), importedTime(source), valueIsData = true)
            DetailRow(
                stringResource(R.string.viewer_route_label),
                stringResource(
                    if (source.intakeKind.name == "SHARE") {
                        R.string.viewer_route_share
                    } else {
                        R.string.viewer_route_photos
                    },
                ),
            )
            DetailRow(
                stringResource(R.string.viewer_integrity_label),
                when {
                    source.state == SourceState.CORRUPT -> stringResource(R.string.viewer_integrity_unreadable)
                    verified -> stringResource(R.string.viewer_integrity_verified)
                    else -> stringResource(R.string.viewer_integrity_unverified)
                },
            )
            if (verified) StampBadge(StampState.Verified, modifier = Modifier.padding(top = 8.dp))
            DisplayOrientationNotice(source.orientation)
            Text(
                stringResource(R.string.viewer_integrity_notice),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DisplayOrientationNotice(orientation: Orientation?) {
    when (orientation) {
        Orientation.ROTATE_90 -> Text(stringResource(R.string.viewer_display_rotation, DISPLAY_ROTATION_90))
        Orientation.ROTATE_180 -> Text(stringResource(R.string.viewer_display_rotation, DISPLAY_ROTATION_180))
        Orientation.ROTATE_270 -> Text(stringResource(R.string.viewer_display_rotation, DISPLAY_ROTATION_270))
        Orientation.FLIP_HORIZONTAL -> Text(stringResource(R.string.viewer_display_flip_horizontal))
        Orientation.FLIP_VERTICAL -> Text(stringResource(R.string.viewer_display_flip_vertical))
        Orientation.TRANSPOSE -> Text(stringResource(R.string.viewer_display_transpose))
        Orientation.TRANSVERSE -> Text(stringResource(R.string.viewer_display_transverse))
        null, Orientation.NORMAL -> return
    }
    Text(
        stringResource(R.string.viewer_saved_bytes_unchanged),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun DetailRow(label: String, value: String, valueIsData: Boolean = false) {
    val row = stringResource(R.string.viewer_detail_row, label, value)
    Text(
        if (valueIsData) withDataSpan(row, value) else androidx.compose.ui.text.AnnotatedString(row),
        modifier = Modifier.padding(top = 4.dp),
    )
}

private const val DISPLAY_ROTATION_90 = 90
private const val DISPLAY_ROTATION_180 = 180
private const val DISPLAY_ROTATION_270 = 270
