package org.openlife.vault.ocr

import java.util.UUID

sealed interface OcrRunResult {
    data class Completed(val revisionId: UUID, val spans: List<OcrSpan>) : OcrRunResult
    data class Failed(val revisionId: UUID?, val reason: OcrFailureReason) : OcrRunResult
    data class Cancelled(val revisionId: UUID, val reason: OcrFailureReason = OcrFailureReason.CANCELLED) : OcrRunResult
    data class Stale(val revisionId: UUID) : OcrRunResult
}
