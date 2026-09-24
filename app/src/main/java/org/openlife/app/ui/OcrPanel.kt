package org.openlife.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrSpan

/** The viewer's extracted-text panel (C1, P2-01): persisted OCR state, corrections and history. */
@Composable
internal fun OcrSection(
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
        val runAgain = @Composable {
            TextButton(onClick = onExtractText, enabled = source.state == SourceState.READY) {
                Text(stringResource(R.string.viewer_run_again))
            }
        }
        when (state) {
            // Nothing to offer until the persisted state is known (P2-01).
            OcrUiState.Loading -> Unit

            OcrUiState.Idle -> Button(onClick = onExtractText, enabled = source.state == SourceState.READY) {
                Text(stringResource(R.string.viewer_extract_text_action))
            }

            is OcrUiState.Running -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.viewer_extracting))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel_action)) }
                }
                OcrHistory(state.history)
            }

            is OcrUiState.Ready -> {
                OcrReadyContent(state, onSelectRegion, onCorrect, onReview)
                runAgain()
                OcrHistory(state.history)
            }

            is OcrUiState.Failed -> OcrOutcome(
                stringResource(R.string.viewer_extraction_failed, ocrFailureMessage(state.reason)),
                state.history,
                runAgain,
            )

            is OcrUiState.Cancelled ->
                OcrOutcome(stringResource(R.string.viewer_extraction_cancelled), state.history, runAgain)

            is OcrUiState.Stale -> OcrOutcome(stringResource(R.string.viewer_extraction_stale), state.history, runAgain)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.viewer_untrusted_notice),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * P2-01-R3/R5: the engine's text stays as extracted; a user's correction is
 * shown under it and attributed. Everything is plain, inert Text: no
 * selection, links or clipboard.
 */
@Composable
private fun OcrReadyContent(
    state: OcrUiState.Ready,
    onSelectRegion: (org.openlife.vault.ocr.OcrEvidenceRegion) -> Unit,
    onCorrect: (OcrSpan) -> Unit,
    onReview: (OcrReviewState) -> Unit,
) {
    val provenance = stringResource(R.string.viewer_ocr_engine, state.engineId, state.modelVersion)
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
    state.spans.forEach { span ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(span.text, style = MaterialTheme.typography.labelMedium)
                state.corrections[span.id]?.let { CorrectionLine(it) }
            }
            span.evidenceRegion?.let { region ->
                TextButton(onClick = { onSelectRegion(region) }) {
                    Text(stringResource(R.string.viewer_show_region))
                }
            } ?: Text(stringResource(R.string.viewer_ungrounded))
            TextButton(onClick = { onCorrect(span) }) { Text(stringResource(R.string.viewer_correct)) }
        }
    }
    state.revisionCorrection?.let { CorrectionLine(it) }
    Row {
        TextButton(onClick = { onReview(OcrReviewState.ACCEPTED) }) {
            Text(stringResource(R.string.viewer_accept_text))
        }
        TextButton(onClick = { onReview(OcrReviewState.REJECTED) }) {
            Text(stringResource(R.string.viewer_reject_text))
        }
    }
}

@Composable
private fun CorrectionLine(correctedText: String) {
    Text(
        stringResource(R.string.viewer_corrected_by_you, correctedText),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
    )
}

@Composable
private fun OcrOutcome(explanation: String, history: List<OcrHistoryItem>, runAgain: @Composable () -> Unit) {
    Text(explanation)
    runAgain()
    OcrHistory(history)
}

/** Earlier revisions stay listed; only their metadata is shown and held (P2-01-R3). */
@Composable
private fun OcrHistory(history: List<OcrHistoryItem>) {
    if (history.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(R.string.viewer_ocr_history, history.size))
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
}

private fun revisionStateLabel(state: org.openlife.vault.ocr.OcrRevisionState): Int = when (state) {
    org.openlife.vault.ocr.OcrRevisionState.NOT_STARTED -> R.string.viewer_revision_not_started
    org.openlife.vault.ocr.OcrRevisionState.RUNNING -> R.string.viewer_revision_running
    org.openlife.vault.ocr.OcrRevisionState.READY -> R.string.viewer_revision_ready
    org.openlife.vault.ocr.OcrRevisionState.FAILED -> R.string.viewer_revision_failed
    org.openlife.vault.ocr.OcrRevisionState.CANCELLED -> R.string.viewer_revision_cancelled
    org.openlife.vault.ocr.OcrRevisionState.STALE -> R.string.viewer_revision_stale
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
