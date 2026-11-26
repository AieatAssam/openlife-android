package org.openlife.vault.facts

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
    fun isEligible(input: SpanEligibilityInput): Boolean = TODO("P3-02 GREEN")
}
