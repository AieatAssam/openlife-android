package org.openlife.vault.facts

import org.openlife.vault.ocr.OcrScriptPolicy
import org.openlife.vault.ocr.OcrScriptStatus

/**
 * What C2 §1 needs to know about one span of the current reading to decide
 * whether extraction may read it. Every field is read from stored rows.
 */
data class SpanEligibilityInput(
    val readingState: String,
    val readingReviewState: String,
    val readingHasWholeCorrection: Boolean,
    val hasEvidenceRegion: Boolean,
    val latestSpanReview: String?,
    val storedScriptStatus: String,
    val currentCorrectionText: String?,
)

/**
 * C2 §1 "eligible span" (P3-02-R7, VS-3): accepting a reading never makes an
 * ungrounded or out-of-script span eligible, because eligibility reads the
 * persisted `hasEvidenceRegion` and the script of the text actually read.
 */
object SpanEligibility {
    fun isEligible(input: SpanEligibilityInput): Boolean = readingAllows(input) && spanAllows(input) && isLatin(input)

    private fun readingAllows(input: SpanEligibilityInput): Boolean =
        input.readingState == READY && input.readingReviewState != REJECTED && !input.readingHasWholeCorrection

    private fun spanAllows(input: SpanEligibilityInput): Boolean =
        input.hasEvidenceRegion && input.latestSpanReview != REJECTED

    /** Z-16: the text actually read is classified; the stored status stands only for an uncorrected span. */
    private fun isLatin(input: SpanEligibilityInput): Boolean = when (val corrected = input.currentCorrectionText) {
        null -> input.storedScriptStatus == OcrScriptStatus.LATIN.name
        else -> OcrScriptPolicy.classify(corrected) == OcrScriptStatus.LATIN
    }

    private const val READY = "READY"
    private const val REJECTED = "REJECTED"
}
