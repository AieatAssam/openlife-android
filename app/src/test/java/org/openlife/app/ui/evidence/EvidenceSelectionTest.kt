package org.openlife.app.ui.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrSpan
import java.util.UUID

/** P2-06-R4/R13 (E-06): ungrounded and rejected spans cannot be selected as evidence. */
class EvidenceSelectionTest {
    private val revisionId = UUID.randomUUID()

    @Test
    fun onlyGroundedSpansThatAreNotRejectedAreSelectable() {
        val plain = span(0, OcrEvidenceRegion(0, 0, 10, 10))
        val rejected = span(1, OcrEvidenceRegion(0, 0, 20, 20))
        val undone = span(2, OcrEvidenceRegion(0, 0, 30, 30))
        val accepted = span(3, OcrEvidenceRegion(0, 0, 40, 40))
        val ungrounded = span(4, null)
        val reviews = mapOf(
            rejected.id to OcrReviewState.REJECTED,
            undone.id to OcrReviewState.UNREVIEWED,
            accepted.id to OcrReviewState.ACCEPTED,
        )

        assertEquals(
            listOf(plain, undone, accepted),
            EvidenceSelection.selectableSpans(listOf(plain, rejected, undone, accepted, ungrounded), reviews),
        )
        assertTrue(EvidenceSelection.isRejected(rejected, reviews))
        assertFalse(EvidenceSelection.isRejected(undone, reviews))
        assertFalse(EvidenceSelection.isRejected(plain, reviews))
    }

    private fun span(ordinal: Int, region: OcrEvidenceRegion?) = OcrSpan(
        UUID.randomUUID(),
        revisionId,
        ordinal,
        "span $ordinal",
        null,
        OcrCoordinateSystem.SOURCE_PIXELS,
        region,
    )
}
