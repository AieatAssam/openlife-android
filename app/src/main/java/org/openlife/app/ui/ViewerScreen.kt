package org.openlife.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.app.ui.brand.FoldedCornerCard
import org.openlife.app.ui.brand.OpenLifeTextButton
import org.openlife.app.ui.brand.PerforationDivider
import org.openlife.app.ui.brand.StampBadge
import org.openlife.app.ui.brand.StampState
import org.openlife.app.ui.evidence.EvidenceSelection
import org.openlife.app.ui.evidence.EvidenceViewport
import org.openlife.app.ui.evidence.evidenceSpanPreview
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.model.UserContentKind
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
    onRejectSpan: (OcrSpan) -> Unit = {},
    onUndoRejectSpan: (OcrSpan) -> Unit = {},
    onSetUserContentKind: (UserContentKind?) -> Unit = {},
    // P2-08-R4: a search result opens the viewer with its span selected. Selection
    // rules still apply: a span that is ungrounded or rejected is not selected.
    initialSelectedSpanId: java.util.UUID? = null,
    // P3-05-R3 (C2-R39): an earlier reading is read-only; no mutating action is offered.
    readOnly: Boolean = false,
) {
    var loadAttempt by remember(source.id) { mutableIntStateOf(0) }
    val content = rememberViewerContent(source, loadContent, loadAttempt)
    var selectedSpanId by remember(source.id) { mutableStateOf(initialSelectedSpanId) }
    ViewerBody(
        source = source,
        content = content,
        ocrState = ocrState,
        selectedSpanId = selectedSpanId,
        onSelectSpan = { selectedSpanId = it },
        onRetryLoad = { loadAttempt++ },
        readOnly = readOnly,
        actions = ViewerActions(
            onBack = onBack,
            onDeleteRequested = onDeleteRequested,
            onExtractText = onExtractText,
            onCancelOcr = onCancelOcr,
            onCorrect = onCorrect,
            onReview = onReview,
            onRejectSpan = onRejectSpan,
            onUndoRejectSpan = onUndoRejectSpan,
            onSetUserContentKind = onSetUserContentKind,
        ),
    )
}

/** What the viewer asks of its owner; callbacks only, no state (P1-20-R9). */
internal class ViewerActions(
    val onBack: () -> Unit,
    val onDeleteRequested: () -> Unit,
    val onExtractText: () -> Unit = {},
    val onCancelOcr: () -> Unit = {},
    val onCorrect: (OcrSpan, String) -> Unit = { _, _ -> },
    val onReview: (OcrReviewState) -> Unit = {},
    val onRejectSpan: (OcrSpan) -> Unit = {},
    val onUndoRejectSpan: (OcrSpan) -> Unit = {},
    val onSetUserContentKind: (UserContentKind?) -> Unit = {},
    val onOpenFacts: () -> Unit = {},
)

/**
 * The viewer drawn from state its owner holds (P1-20-R9): the decoded
 * [content], the OCR state, the selected span and, optionally, the zoom and
 * pan. Only the open dialogs are kept here; they hold no saved content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
internal fun ViewerBody(
    source: Source,
    content: ViewerContentState,
    ocrState: OcrUiState,
    selectedSpanId: java.util.UUID?,
    onSelectSpan: (java.util.UUID?) -> Unit,
    onRetryLoad: () -> Unit,
    actions: ViewerActions,
    viewport: EvidenceViewport? = null,
    readOnly: Boolean = false,
) {
    var correctingSpan by remember(source.id) { mutableStateOf<OcrSpan?>(null) }
    var correctionText by remember(source.id) { mutableStateOf("") }
    var confirmingDelete by remember(source.id) { mutableStateOf(false) }

    val pane = stringResource(R.string.viewer_pane_title)
    Scaffold(
        modifier = Modifier.semantics { paneTitle = pane },
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = { ViewerTopBar(source, actions.onBack, onDelete = { confirmingDelete = true }, readOnly = readOnly) },
    ) { padding ->
        ViewerContent(
            modifier = Modifier.fillMaxSize().padding(padding),
            source = source,
            content = content,
            selectedSpanId = selectedSpanId,
            onRetryLoad = onRetryLoad,
            onDeleteRequested = { confirmingDelete = true },
            ocrState = ocrState,
            onExtractText = actions.onExtractText,
            onCancelOcr = actions.onCancelOcr,
            onSelectSpan = { onSelectSpan(it.id) },
            onCorrect = { span ->
                correctingSpan = span
                correctionText = span.text
            },
            onReview = actions.onReview,
            onRejectSpan = { span ->
                // S-02: a rejected span stops being the selection here, so Undo
                // does not re-reveal it (scroll jump) or re-announce it.
                if (span.id == selectedSpanId) onSelectSpan(null)
                actions.onRejectSpan(span)
            },
            onUndoRejectSpan = actions.onUndoRejectSpan,
            onSetUserContentKind = actions.onSetUserContentKind,
            onOpenFacts = actions.onOpenFacts,
            viewport = viewport,
            readOnly = readOnly,
        )
    }

    ViewerCorrectionDialog(
        span = correctingSpan,
        correctionText = correctionText,
        onCorrectionTextChange = { correctionText = it },
        onDismiss = { correctingSpan = null },
        onSave = actions.onCorrect,
    )

    // Design §8 step 5: delete names the item and is confirmed from every
    // entry point, including this one.
    if (confirmingDelete) {
        DeleteConfirmationDialog(
            itemLabel = sourceLabel(source),
            onConfirm = {
                confirmingDelete = false
                actions.onDeleteRequested()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerTopBar(source: Source, onBack: () -> Unit, onDelete: () -> Unit, readOnly: Boolean) {
    TopAppBar(
        title = { Text(sourceLabel(source), modifier = Modifier.asHeading()) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.viewer_back_content_description),
                )
            }
        },
        actions = {
            if (!readOnly) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(
                            R.string.viewer_delete_content_description,
                            sourceLabel(source),
                        ),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

@Composable
@Suppress("LongParameterList")
private fun ViewerContent(
    modifier: Modifier,
    source: Source,
    content: ViewerContentState,
    selectedSpanId: java.util.UUID?,
    onRetryLoad: () -> Unit,
    onDeleteRequested: () -> Unit,
    ocrState: OcrUiState,
    onExtractText: () -> Unit,
    onCancelOcr: () -> Unit,
    onSelectSpan: (OcrSpan) -> Unit,
    onCorrect: (OcrSpan) -> Unit,
    onReview: (OcrReviewState) -> Unit,
    onRejectSpan: (OcrSpan) -> Unit,
    onUndoRejectSpan: (OcrSpan) -> Unit,
    onSetUserContentKind: (UserContentKind?) -> Unit,
    onOpenFacts: () -> Unit,
    viewport: EvidenceViewport?,
    readOnly: Boolean,
) {
    val ready = ocrState as? OcrUiState.Ready
    val spans = ready?.spans.orEmpty()
    val spanReviews = ready?.spanReviews.orEmpty()
    // P2-06-R13: a span rejected after it was selected stops being the selection.
    val selectedSpan = EvidenceSelection.selectableSpans(spans, spanReviews).find { it.id == selectedSpanId }
    StatusHost {
        Surface(modifier = modifier) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (readOnly) {
                    Text(
                        stringResource(R.string.viewer_earlier_reading),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                ViewerImage(
                    source,
                    content,
                    spans,
                    spanReviews,
                    selectedSpan?.id,
                    onSelectSpan = onSelectSpan,
                    onRetry = onRetryLoad,
                    onDelete = onDeleteRequested,
                    deletable = !readOnly,
                    viewport = viewport,
                )
                // Visible text only: status announcements never carry document content (span text).
                selectedSpan?.let {
                    Text(
                        stringResource(R.string.viewer_span_selected, evidenceSpanPreview(it.text)),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // What the user came for (the text and its evidence) comes before
                // the bookkeeping about the saved copy. Facts are offered once the text is read (P3-05-R1).
                if (ocrState is OcrUiState.Ready && !readOnly) {
                    OpenLifeTextButton(
                        onClick = onOpenFacts,
                        modifier = Modifier.padding(horizontal = 16.dp).testTag("viewer_open_facts"),
                    ) {
                        Text(stringResource(R.string.viewer_open_facts))
                    }
                }
                OcrSection(
                    source = source,
                    state = ocrState,
                    onExtractText = onExtractText,
                    onCancel = onCancelOcr,
                    onSelectSpan = onSelectSpan,
                    onCorrect = onCorrect,
                    onReview = onReview,
                    onRejectSpan = onRejectSpan,
                    onUndoRejectSpan = onUndoRejectSpan,
                    readOnly = readOnly,
                )
                PerforationDivider(modifier = Modifier.padding(horizontal = 16.dp))
                DetailsSection(
                    source = source,
                    verified = content is ViewerContentState.Shown,
                    onSetUserContentKind = onSetUserContentKind,
                    readOnly = readOnly,
                )
            }
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
            title = { Text(stringResource(R.string.viewer_correct_title), modifier = Modifier.asHeading()) },
            text = {
                val correctionLabel = stringResource(R.string.viewer_correction_label)
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ContentTextField(
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
                OpenLifeTextButton(
                    onClick = {
                        if (correctionText.isNotEmpty()) onSave(currentSpan, correctionText)
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.viewer_save_correction)) }
            },
            dismissButton = {
                OpenLifeTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_action)) }
            },
        )
    }
}

@Composable
private fun DetailsSection(
    source: Source,
    verified: Boolean,
    onSetUserContentKind: (UserContentKind?) -> Unit,
    readOnly: Boolean,
) {
    FoldedCornerCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.viewer_details_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.asHeading(),
            )
            DetailRow(stringResource(R.string.viewer_imported_label), importedTime(source), valueIsData = true)
            DetailRow(
                stringResource(R.string.viewer_route_label),
                stringResource(
                    if (source.intakeKind == org.openlife.vault.model.IntakeKind.SHARE) {
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
            // P2-07: communication safety context; no assessment runs in this version.
            PerforationDivider(modifier = Modifier.padding(top = 12.dp))
            AssessmentNotice(source)
            if (!readOnly && ContentKindLabel.showQuestion(source)) ContentKindQuestion(source, onSetUserContentKind)
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
