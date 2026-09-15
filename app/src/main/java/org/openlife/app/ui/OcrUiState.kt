package org.openlife.app.ui

import java.util.UUID
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrSpan

sealed interface OcrUiState {
    data object Idle : OcrUiState
    data class Running(val sourceId: UUID) : OcrUiState
    data class Ready(val sourceId: UUID, val revisionId: UUID, val spans: List<OcrSpan>) : OcrUiState
    data class Failed(val sourceId: UUID, val reason: OcrFailureReason) : OcrUiState
    data class Cancelled(val sourceId: UUID) : OcrUiState
    data class Stale(val sourceId: UUID) : OcrUiState
}
