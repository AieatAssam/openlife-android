package org.openlife.vault.ocr

/** What the user asked to do with one span's review (P2-06-R13). */
enum class OcrSpanReviewAction { REJECT, UNDO_REJECT }

/**
 * Pure rules for per-span review rows (P2-06-R12/R13). Rows are append-only;
 * the current one is the highest sequence, never the latest wall-clock time.
 */
object OcrSpanReviewRules {
    /** The current row for one span, by sequence. */
    fun latest(reviews: List<OcrSpanReview>): OcrSpanReview? = reviews.maxByOrNull { it.sequence }

    /**
     * The review state to append for [action], or null when the action is
     * refused: the revision is not READY, a reject of a rejected span, an undo
     * of a span that is not rejected, or a state this build does not know.
     */
    fun next(
        reviews: List<OcrSpanReview>,
        revisionState: OcrRevisionState,
        action: OcrSpanReviewAction,
    ): OcrReviewState? {
        if (revisionState != OcrRevisionState.READY) return null
        val ordered = reviews.sortedBy { it.sequence }
        val current = ordered.lastOrNull()?.reviewState ?: OcrReviewState.UNREVIEWED
        val next = when (action) {
            OcrSpanReviewAction.REJECT -> OcrReviewState.REJECTED.takeIf { current != OcrReviewState.REJECTED }

            OcrSpanReviewAction.UNDO_REJECT -> if (current == OcrReviewState.REJECTED) {
                ordered.getOrNull(ordered.size - 2)?.reviewState ?: OcrReviewState.UNREVIEWED
            } else {
                null
            }
        }
        // Never write over, or restore, a state this build does not know.
        return next.takeIf { current != OcrReviewState.UNRECOGNISED && it != OcrReviewState.UNRECOGNISED }
    }
}
