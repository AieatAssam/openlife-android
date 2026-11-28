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
        val spanReviews: Map<UUID, OcrReviewState> = emptyMap(),
    ) : OcrUiState

    data class Failed(
        val sourceId: UUID,
        val reason: OcrFailureReason,
        val history: List<OcrHistoryItem> = emptyList(),
    ) : OcrUiState

    data class Cancelled(val sourceId: UUID, val history: List<OcrHistoryItem> = emptyList()) : OcrUiState

    /**
     * P3-05 review U-01: the reading a route names is not a reading of this source (or does not exist). Nothing
     * from any reading is shown; the viewer says the content is unavailable.
     */
    data object Unavailable : OcrUiState
    data class Stale(val sourceId: UUID, val history: List<OcrHistoryItem> = emptyList()) : OcrUiState
}
