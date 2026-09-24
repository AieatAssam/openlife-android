package org.openlife.app.ui

import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrSpan
import java.util.UUID

sealed interface OcrUiState {
    data object Idle : OcrUiState
    data class Running(val sourceId: UUID) : OcrUiState
    data class Ready(val sourceId: UUID, val revisionId: UUID, val spans: List<OcrSpan>) : OcrUiState
    data class Failed(val sourceId: UUID, val reason: OcrFailureReason) : OcrUiState
    data class Cancelled(val sourceId: UUID) : OcrUiState
    data class Stale(val sourceId: UUID) : OcrUiState
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
