package org.openlife.vault.ocr

import org.openlife.vault.model.Orientation
import java.util.UUID

data class OcrRevision(
    val id: UUID,
    val sourceId: UUID,
    val state: OcrRevisionState,
    val engineId: String,
    val modelVersion: String,
    val orientation: Orientation,
    val sourceDigest: ByteArray,
    val startedAt: Long,
    val extractedAt: Long?,
    val reviewState: OcrReviewState,
    val failureReason: OcrFailureReason?,
    val charCount: Int,
    val spanCount: Int,
) {
    init {
        require(engineId.isNotBlank()) { "OCR engine ID is required" }
        require(modelVersion.isNotBlank()) { "OCR model version is required" }
        require(sourceDigest.isNotEmpty()) { "OCR source digest is required" }
        require(startedAt >= 0) { "OCR start time cannot be negative" }
        require(charCount >= 0 && charCount <= OcrLimits.MAX_TEXT_CHARS)
        require(spanCount >= 0 && spanCount <= OcrLimits.MAX_SPANS)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OcrRevision) return false
        return id == other.id &&
            sourceId == other.sourceId &&
            state == other.state &&
            engineId == other.engineId &&
            modelVersion == other.modelVersion &&
            orientation == other.orientation &&
            sourceDigest.contentEquals(other.sourceDigest) &&
            startedAt == other.startedAt &&
            extractedAt == other.extractedAt &&
            reviewState == other.reviewState &&
            failureReason == other.failureReason &&
            charCount == other.charCount &&
            spanCount == other.spanCount
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sourceId.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + engineId.hashCode()
        result = 31 * result + modelVersion.hashCode()
        result = 31 * result + orientation.hashCode()
        result = 31 * result + sourceDigest.contentHashCode()
        result = 31 * result + startedAt.hashCode()
        result = 31 * result + (extractedAt?.hashCode() ?: 0)
        result = 31 * result + reviewState.hashCode()
        result = 31 * result + (failureReason?.hashCode() ?: 0)
        result = 31 * result + charCount
        result = 31 * result + spanCount
        return result
    }
}

data class OcrSpan(
    val id: UUID,
    val revisionId: UUID,
    val ordinal: Int,
    val text: String,
    val confidence: Float?,
    val coordinateSystem: OcrCoordinateSystem,
    val evidenceRegion: OcrEvidenceRegion?,
) {
    init {
        require(ordinal >= 0) { "OCR span ordinal cannot be negative" }
        require(text.isNotEmpty()) { "OCR spans cannot be empty" }
        if (confidence != null) {
            require(confidence.isFinite() && confidence in 0f..1f)
        }
    }
}

data class OcrUserRevision(
    val id: UUID,
    val revisionId: UUID,
    val spanId: UUID?,
    val correctedText: String,
    val createdAt: Long,
    val actor: String = "USER",
) {
    init {
        require(correctedText.isNotEmpty()) { "A correction cannot be empty" }
        require(createdAt >= 0)
        require(actor == "USER") { "Only attributed user corrections are supported" }
    }
}
