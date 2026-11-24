package org.openlife.app.ui.evidence

import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrSpan
import java.util.UUID

/**
 * Which spans may be selected as evidence (P2-06-R4, R13): only a span with a
 * region, and never a rejected one (ethos P3: a rejected value is not shown
 * as established evidence).
 */
internal object EvidenceSelection {
    fun isRejected(span: OcrSpan, spanReviews: Map<UUID, OcrReviewState>): Boolean =
        spanReviews[span.id] == OcrReviewState.REJECTED

    fun selectableSpans(spans: List<OcrSpan>, spanReviews: Map<UUID, OcrReviewState>): List<OcrSpan> =
        spans.filter { it.evidenceRegion != null && !isRejected(it, spanReviews) }
}
