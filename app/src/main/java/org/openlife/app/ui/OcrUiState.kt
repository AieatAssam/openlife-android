package org.openlife.app.ui

import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRevisionState
import org.openlife.vault.ocr.OcrSpan
import java.util.UUID

/** Metadata of an earlier revision; its text is never held for the history list. */
data class OcrHistoryItem(
    val revisionId: UUID,
    val state: OcrRevisionState,
    val at: Long,
    val engineId: String,
    val modelVersion: String,
)

sealed interface OcrUiState {
    /** The persisted state has not been read yet: no action and no text (never offer Extract here). */
    data object Loading : OcrUiState

    /** No revision exists for this source. */
    data object Idle : OcrUiState
    data class Running(val sourceId: UUID, val history: List<OcrHistoryItem> = emptyList()) : OcrUiState
    data class Ready(
        val sourceId: UUID,
        val revisionId: UUID,
        val spans: List<OcrSpan>,
        val corrections: Map<UUID, String> = emptyMap(),
        val revisionCorrection: String? = null,
        val reviewState: OcrReviewState = OcrReviewState.UNREVIEWED,
        val engineId: String = "",
        val modelVersion: String = "",
        val extractedAt: Long? = null,
        val history: List<OcrHistoryItem> = emptyList(),
    ) : OcrUiState

    data class Failed(
        val sourceId: UUID,
        val reason: OcrFailureReason,
        val history: List<OcrHistoryItem> = emptyList(),
    ) : OcrUiState

    data class Cancelled(val sourceId: UUID, val history: List<OcrHistoryItem> = emptyList()) : OcrUiState
    data class Stale(val sourceId: UUID, val history: List<OcrHistoryItem> = emptyList()) : OcrUiState
}

/**
 * P1-16-R3: under memory pressure or when hidden, extracted text leaves
 * memory; run state (running, failed, cancelled) is kept so the panel stays
 * truthful and a running extraction is not silently lost.
 */
internal object OcrTrim {
    fun dropExtractedText(states: Map<UUID, OcrUiState>): Map<UUID, OcrUiState> =
        states.filterValues { it !is OcrUiState.Ready }
}
