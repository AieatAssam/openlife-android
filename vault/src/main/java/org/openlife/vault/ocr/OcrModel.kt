package org.openlife.vault.ocr

import org.openlife.vault.model.Orientation
import org.openlife.vault.model.OrientationTransform

/** Limits for one local OCR operation and its persisted derived output. */
object OcrLimits {
    const val MAX_SOURCE_BYTES: Long = 16L * 1024 * 1024
    const val MAX_SOURCE_PIXELS: Long = 40_000_000L
    const val MAX_DECODE_PIXELS: Long = 4_000_000L
    const val DEADLINE_MILLIS: Long = 15_000L
    const val MAX_TEXT_CHARS: Int = 200_000
    const val MAX_SPANS: Int = 2_000
}

/** A rectangle in a declared image coordinate system. Right/bottom are exclusive. */
data class OcrEvidenceRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left >= 0 && top >= 0) { "Evidence coordinates cannot be negative" }
        require(right >= left && bottom >= top) { "Evidence rectangle must be ordered" }
    }
}

/** Engine output before it receives a revision/span identity. */
data class OcrSpanDraft(val text: String, val confidence: Float?, val region: OcrEvidenceRegion?) {
    init {
        require(text.isNotEmpty()) { "OCR spans cannot be empty" }
        if (confidence != null) {
            require(confidence.isFinite() && confidence in 0f..1f) {
                "OCR confidence must be finite and between zero and one"
            }
        }
    }
}

/** Name used by engine adapters; drafts remain independent of persistence IDs. */
typealias OcrEngineSpan = OcrSpanDraft

class OcrLimitExceededException(message: String) : IllegalArgumentException(message)

object OcrOutputValidator {
    fun validate(spans: List<OcrSpanDraft>): List<OcrSpanDraft> {
        if (spans.size > OcrLimits.MAX_SPANS) {
            throw OcrLimitExceededException("OCR span limit exceeded")
        }
        val characterCount = spans.sumOf { it.text.length.toLong() }
        if (characterCount > OcrLimits.MAX_TEXT_CHARS) {
            throw OcrLimitExceededException("OCR character limit exceeded")
        }
        return spans
    }
}

/**
 * Maps rectangles reported in the displayed/oriented image back to the
 * unmodified Source pixel coordinate system. No region is invented when the
 * engine did not supply one; callers invoke this only for a supplied box.
 */
object OcrCoordinateMapper {
    fun toSourcePixels(
        displayRegion: OcrEvidenceRegion,
        sourceWidth: Int,
        sourceHeight: Int,
        orientation: Orientation,
    ): OcrEvidenceRegion {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        val displayWidth = OrientationTransform.displayWidth(sourceWidth, sourceHeight, orientation)
        val displayHeight = OrientationTransform.displayHeight(sourceWidth, sourceHeight, orientation)
        require(displayRegion.right <= displayWidth && displayRegion.bottom <= displayHeight) {
            "Evidence region lies outside the displayed image"
        }

        val corners = listOf(
            Point(displayRegion.left, displayRegion.top),
            Point(displayRegion.right, displayRegion.top),
            Point(displayRegion.left, displayRegion.bottom),
            Point(displayRegion.right, displayRegion.bottom),
        ).map {
            OrientationTransform.sourcePointForDisplay(it.x, it.y, sourceWidth, sourceHeight, orientation)
        }
        return OcrEvidenceRegion(
            left = corners.minOf { it.x },
            top = corners.minOf { it.y },
            right = corners.maxOf { it.x },
            bottom = corners.maxOf { it.y },
        )
    }

    private data class Point(val x: Int, val y: Int)
}
