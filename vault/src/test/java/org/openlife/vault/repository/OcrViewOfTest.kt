package org.openlife.vault.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.storage.OcrRevisionEntity
import org.openlife.vault.storage.OcrSpanEntity
import org.openlife.vault.storage.OcrSpanReviewEntity
import java.util.UUID

/** P2-06-R12 (E-05): the viewer's current span review is the highest sequence, not the latest clock. */
class OcrViewOfTest {
    @Test
    fun latestSpanReviewIsChosenBySequenceNotClock() {
        val revisionId = UUID.randomUUID().toString()
        val span = span(revisionId)
        // The clock went backwards between the two appends: the undo has the
        // older createdAt but the higher sequence, so it is current.
        val rejected = review(span.id, "REJECTED", createdAt = 1_000, sequence = 1)
        val undone = review(span.id, "UNREVIEWED", createdAt = 10, sequence = 2)

        listOf(listOf(rejected, undone), listOf(undone, rejected)).forEach { reviews ->
            val view = ocrViewOf(revision(revisionId), emptyList(), listOf(span), emptyList(), reviews)
            assertEquals(OcrReviewState.UNREVIEWED, view.spans.single().review?.reviewState)
            assertEquals(2L, view.spans.single().review?.sequence)
        }
    }

    private fun review(spanId: String, state: String, createdAt: Long, sequence: Long) =
        OcrSpanReviewEntity(UUID.randomUUID().toString(), spanId, state, createdAt, sequence)

    private fun span(revisionId: String) = OcrSpanEntity(
        id = UUID.randomUUID().toString(),
        revisionId = revisionId,
        ordinal = 0,
        text = "text",
        confidence = null,
        coordinateSystem = "SOURCE_PIXELS",
        left = 1,
        top = 2,
        right = 3,
        bottom = 4,
    )

    private fun revision(id: String) = OcrRevisionEntity(
        id = id,
        sourceId = UUID.randomUUID().toString(),
        state = "READY",
        engineId = "engine",
        modelVersion = "1",
        orientation = "NORMAL",
        sourceDigest = ByteArray(32) { 3 },
        startedAt = 1,
        extractedAt = 2,
        reviewState = "UNREVIEWED",
        failureReason = null,
        charCount = 4,
        spanCount = 1,
    )
}
