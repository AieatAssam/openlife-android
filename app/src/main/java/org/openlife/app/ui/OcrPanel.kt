package org.openlife.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.app.ui.brand.OpenLifeButton
import org.openlife.app.ui.brand.OpenLifeTextButton
import org.openlife.app.ui.brand.StampBadge
import org.openlife.app.ui.brand.StampState
import org.openlife.app.ui.evidence.EvidenceSelection
import org.openlife.app.ui.evidence.evidenceSpanPreview
import org.openlife.app.ui.theme.LocalOpenLifeBrandColors
import org.openlife.app.ui.theme.OpenLifeMono
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrSpan

/** The viewer's extracted-text panel (C1, P2-01): persisted OCR state, corrections and history. */
@Composable
@Suppress("LongParameterList")
internal fun OcrSection(
    source: Source,
    state: OcrUiState,
    onExtractText: () -> Unit,
    onCancel: () -> Unit,
    onSelectSpan: (OcrSpan) -> Unit,
    onCorrect: (OcrSpan) -> Unit,
    onReview: (OcrReviewState) -> Unit,
    onRejectSpan: (OcrSpan) -> Unit,
    onUndoRejectSpan: (OcrSpan) -> Unit = {},
) {
    val spanActions = SpanActions(onSelectSpan, onCorrect, onRejectSpan, onUndoRejectSpan)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.viewer_extracted_text_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.asHeading(),
        )
        val runAgain = @Composable {
            OpenLifeTextButton(onClick = onExtractText, enabled = source.state == SourceState.READY) {
                Text(stringResource(R.string.viewer_run_again))
            }
        }
        when (state) {
            // Nothing to offer until the persisted state is known (P2-01).
            OcrUiState.Loading -> Unit

            OcrUiState.Idle -> OpenLifeButton(onClick = onExtractText, enabled = source.state == SourceState.READY) {
                Text(stringResource(R.string.viewer_extract_text_action))
            }

            is OcrUiState.Running -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Announce(Announcement.statusOf(R.string.viewer_extracting))
                    Text(stringResource(R.string.viewer_extracting))
                    Spacer(Modifier.width(8.dp))
                    OpenLifeTextButton(onClick = onCancel) { Text(stringResource(R.string.cancel_action)) }
                }
                OcrHistory(state.history)
            }

            is OcrUiState.Ready -> {
                OcrReadyContent(state, spanActions, onReview)
                runAgain()
                OcrHistory(state.history)
            }

            is OcrUiState.Failed -> OcrOutcome(
                Announcement.statusOf(R.string.viewer_extraction_failed, ocrFailureRes(state.reason)),
                stringResource(R.string.viewer_extraction_failed, stringResource(ocrFailureRes(state.reason))),
                state.history,
                runAgain,
            )

            is OcrUiState.Cancelled ->
                OcrOutcome(
                    Announcement.statusOf(R.string.viewer_extraction_cancelled),
                    stringResource(R.string.viewer_extraction_cancelled),
                    state.history,
                    runAgain,
                )

            is OcrUiState.Stale -> StaleOutcome(state.history, runAgain)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.viewer_untrusted_notice),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** What a span row can do; selection is offered only for evidence (grounded, not rejected). */
private class SpanActions(
    val onSelect: (OcrSpan) -> Unit,
    val onCorrect: (OcrSpan) -> Unit,
    val onReject: (OcrSpan) -> Unit,
    val onUndoReject: (OcrSpan) -> Unit,
)

/**
 * P2-01-R3/R5: the engine's text stays as extracted; a user's correction is
 * shown under it and attributed. Everything is plain, inert Text: no
 * selection, links or clipboard.
 */
@Composable
private fun OcrReadyContent(state: OcrUiState.Ready, actions: SpanActions, onReview: (OcrReviewState) -> Unit) {
    val reviewWord = stringResource(
        when (state.reviewState) {
            OcrReviewState.UNREVIEWED -> R.string.viewer_provenance_unreviewed
            OcrReviewState.ACCEPTED -> R.string.viewer_review_accepted
            OcrReviewState.REJECTED -> R.string.viewer_review_rejected
            OcrReviewState.UNRECOGNISED -> R.string.viewer_review_unrecognised
        },
    )
    val provenance = stringResource(
        R.string.viewer_ocr_provenance,
        state.engineId,
        state.modelVersion,
        reviewWord,
    )
    Text(withDataSpan(provenance, provenance), style = MaterialTheme.typography.bodySmall)
    state.extractedAt?.let { at ->
        val time = ocrTime(at)
        Text(
            withDataSpan(stringResource(R.string.viewer_ocr_extracted_at, time), time),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        stringResource(R.string.viewer_review_state, stringResource(reviewLabel(state.reviewState))),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    if (state.spans.isEmpty()) Text(stringResource(R.string.viewer_no_latin_text))
    val (grounded, ungrounded) = state.spans.partition { it.evidenceRegion != null }
    grounded.forEach { span -> SpanRow(span, state, actions) }
    if (ungrounded.isNotEmpty()) {
        Text(
            stringResource(R.string.viewer_text_without_location),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp).asHeading(),
        )
        Text(
            stringResource(R.string.viewer_text_without_location_explanation),
            style = MaterialTheme.typography.bodySmall,
        )
        ungrounded.forEach { span -> SpanRow(span, state, actions) }
    }
    state.revisionCorrection?.let { CorrectionLine(it) }
    Row {
        OpenLifeTextButton(onClick = { onReview(OcrReviewState.ACCEPTED) }) {
            Text(stringResource(R.string.viewer_accept_text))
        }
        OpenLifeTextButton(onClick = { onReview(OcrReviewState.REJECTED) }) {
            Text(stringResource(R.string.viewer_reject_text))
        }
    }
}

/**
 * One span: its text uses the full width, the actions wrap on the line below
 * (P2-06-R16), so a narrow screen or a large font never squeezes the text. A
 * rejected span offers Undo instead of Reject and is not evidence, so it
 * cannot be selected (P2-06-R13).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SpanRow(span: OcrSpan, state: OcrUiState.Ready, actions: SpanActions) {
    val rejected = EvidenceSelection.isRejected(span, state.spanReviews)
    val selectable = span.evidenceRegion != null && !rejected
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        val selectLabel = stringResource(R.string.viewer_select_text_action)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(
                    if (selectable) {
                        Modifier.clickable(onClickLabel = selectLabel, role = Role.Button) { actions.onSelect(span) }
                    } else {
                        Modifier
                    },
                ),
            verticalArrangement = Arrangement.Center,
        ) {
            val value = if (rejected) {
                buildAnnotatedString {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(span.text) }
                }
            } else {
                androidx.compose.ui.text.AnnotatedString(span.text)
            }
            Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = OpenLifeMono))
            if (rejected) {
                Text(stringResource(R.string.viewer_span_rejected), style = MaterialTheme.typography.labelSmall)
            }
            state.corrections[span.id]?.let { CorrectionLine(it) }
        }
        // P1-19-R11 (A-08): each action names its span, so a list of
        // "Correct" buttons is distinguishable without seeing the row.
        val preview = evidenceSpanPreview(span.text)
        FlowRow(modifier = Modifier.fillMaxWidth()) {
            if (selectable) {
                SpanAction(stringResource(R.string.viewer_show_region_for, preview), { actions.onSelect(span) }) {
                    Text(stringResource(R.string.viewer_show_region))
                }
            }
            SpanAction(stringResource(R.string.viewer_correct_for, preview), { actions.onCorrect(span) }) {
                Text(stringResource(R.string.viewer_correct))
            }
            if (rejected) {
                SpanAction(stringResource(R.string.viewer_undo_reject_for, preview), { actions.onUndoReject(span) }) {
                    Text(stringResource(R.string.viewer_undo_reject))
                }
            } else {
                SpanAction(stringResource(R.string.viewer_reject_span_for, preview), { actions.onReject(span) }) {
                    Text(stringResource(R.string.viewer_reject_span))
                }
            }
        }
    }
}

@Composable
private fun CorrectionLine(correctedText: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, top = 2.dp)) {
        Text(
            stringResource(R.string.viewer_user_badge),
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            stringResource(R.string.viewer_corrected_by_you, correctedText),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun StaleOutcome(history: List<OcrHistoryItem>, runAgain: @Composable () -> Unit) {
    val muted = LocalOpenLifeBrandColors.current.mutedInk
    StampBadge(StampState.Stale)
    Announce(Announcement.statusOf(R.string.viewer_extraction_stale))
    Text(
        stringResource(R.string.viewer_extraction_stale),
        color = muted,
        style = MaterialTheme.typography.bodyMedium,
    )
    runAgain()
    OcrHistory(history)
}

@Composable
private fun OcrOutcome(
    status: Announcement,
    explanation: String,
    history: List<OcrHistoryItem>,
    runAgain: @Composable () -> Unit,
) {
    Announce(status)
    Text(explanation)
    runAgain()
    OcrHistory(history)
}

/** Earlier revisions stay listed; only their metadata is shown and held (P2-01-R3). */
@Composable
private fun OcrHistory(history: List<OcrHistoryItem>) {
    if (history.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    // The label names the action, as the About screen's Show/Hide toggles do, so the state reaches TalkBack.
    val label = if (expanded) {
        stringResource(R.string.viewer_ocr_history_hide, history.size)
    } else {
        stringResource(R.string.viewer_ocr_history_show, history.size)
    }
    OpenLifeTextButton(onClick = { expanded = !expanded }) {
        Text(label)
    }
    if (expanded) {
        history.forEach { item ->
            val line = stringResource(
                R.string.viewer_ocr_history_item,
                stringResource(revisionStateLabel(item.state)),
                ocrTime(item.at),
                item.engineId,
                item.modelVersion,
            )
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )
        }
    }
}

private fun ocrTime(at: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(at))

private fun reviewLabel(state: OcrReviewState): Int = when (state) {
    OcrReviewState.UNREVIEWED -> R.string.viewer_review_unreviewed
    OcrReviewState.ACCEPTED -> R.string.viewer_review_accepted
    OcrReviewState.REJECTED -> R.string.viewer_review_rejected
    OcrReviewState.UNRECOGNISED -> R.string.viewer_review_unrecognised
}

private fun revisionStateLabel(state: org.openlife.vault.ocr.OcrRevisionState): Int = when (state) {
    org.openlife.vault.ocr.OcrRevisionState.NOT_STARTED -> R.string.viewer_revision_not_started
    org.openlife.vault.ocr.OcrRevisionState.RUNNING -> R.string.viewer_revision_running
    org.openlife.vault.ocr.OcrRevisionState.READY -> R.string.viewer_revision_ready
    org.openlife.vault.ocr.OcrRevisionState.FAILED -> R.string.viewer_revision_failed
    org.openlife.vault.ocr.OcrRevisionState.CANCELLED -> R.string.viewer_revision_cancelled
    org.openlife.vault.ocr.OcrRevisionState.STALE -> R.string.viewer_revision_stale
    org.openlife.vault.ocr.OcrRevisionState.UNRECOGNISED -> R.string.viewer_revision_unrecognised
}

@Composable
@StringRes
private fun ocrFailureRes(reason: OcrFailureReason): Int = when (reason) {
    OcrFailureReason.SOURCE_NOT_READY -> R.string.ocr_failure_source_not_ready
    OcrFailureReason.SOURCE_CORRUPT -> R.string.ocr_failure_source_corrupt
    OcrFailureReason.SOURCE_DELETED -> R.string.ocr_failure_source_deleted
    OcrFailureReason.LIMIT_EXCEEDED -> R.string.ocr_failure_limit_exceeded
    OcrFailureReason.UNSUPPORTED_SCRIPT -> R.string.ocr_failure_unsupported_script
    OcrFailureReason.ENGINE_FAILURE -> R.string.ocr_failure_engine
    OcrFailureReason.CANCELLED -> R.string.ocr_failure_cancelled
    OcrFailureReason.PROCESS_RESTART -> R.string.ocr_failure_process_restart
    OcrFailureReason.TIMEOUT -> R.string.ocr_failure_timeout
    OcrFailureReason.STORAGE_FAILURE -> R.string.ocr_failure_storage
    OcrFailureReason.SOURCE_TEMPORARILY_UNAVAILABLE -> R.string.ocr_failure_source_unavailable
    OcrFailureReason.UNRECOGNISED -> R.string.ocr_failure_unrecognised
}
