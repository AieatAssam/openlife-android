package org.openlife.vault.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** P2-06-R12/R13 (E-05, E-06): which review row is current, and what reject/undo may append. */
class OcrSpanReviewRulesTest {
    private val spanId = UUID.randomUUID()
    private val reject = OcrSpanReviewAction.REJECT
    private val undo = OcrSpanReviewAction.UNDO_REJECT

    @Test
    fun latestIsTheHighestSequenceEvenWhenTheClockWentBackwards() {
        val rejected = review(OcrReviewState.REJECTED, createdAt = 100, sequence = 1)
        val restored = review(OcrReviewState.UNREVIEWED, createdAt = 40, sequence = 2)

        assertEquals(restored, OcrSpanReviewRules.latest(listOf(rejected, restored)))
        assertEquals("input order does not matter", restored, OcrSpanReviewRules.latest(listOf(restored, rejected)))
        assertNull(OcrSpanReviewRules.latest(emptyList()))
    }

    @Test
    fun rejectIsAllowedOnceAndOnlyOnAReadyRevision() {
        val ready = OcrRevisionState.READY
        assertEquals(OcrReviewState.REJECTED, next(emptyList(), ready, reject))
        val rejected = listOf(review(OcrReviewState.REJECTED, sequence = 1))
        assertNull("a rejected span cannot be rejected twice", next(rejected, ready, reject))
        OcrRevisionState.entries.filter { it != ready }.forEach { state ->
            assertNull("$state refuses a reject", next(emptyList(), state, reject))
        }
    }

    @Test
    fun undoRestoresTheStateBeforeTheRejectAndIsOnlyOfferedOnARejectedSpan() {
        val ready = OcrRevisionState.READY
        assertNull("nothing to undo", next(emptyList(), ready, undo))
        assertEquals(
            OcrReviewState.UNREVIEWED,
            next(listOf(review(OcrReviewState.REJECTED, sequence = 1)), ready, undo),
        )
        val acceptedThenRejected = listOf(
            review(OcrReviewState.REJECTED, sequence = 2),
            review(OcrReviewState.ACCEPTED, sequence = 1),
        )
        assertEquals(
            "undo restores what the span was before, by sequence",
            OcrReviewState.ACCEPTED,
            next(acceptedThenRejected, ready, undo),
        )
        val undone = listOf(
            review(OcrReviewState.REJECTED, sequence = 1),
            review(OcrReviewState.UNREVIEWED, sequence = 2),
        )
        assertNull("an undone span is no longer rejected", next(undone, ready, undo))
        assertEquals(OcrReviewState.REJECTED, next(undone, ready, reject))
        assertNull(
            "undo also needs a READY revision",
            next(listOf(review(OcrReviewState.REJECTED, sequence = 1)), OcrRevisionState.STALE, undo),
        )
    }

    @Test
    fun anUnrecognisedReviewIsNeverOverwrittenOrRestored() {
        val ready = OcrRevisionState.READY
        val unknown = listOf(review(OcrReviewState.UNRECOGNISED, sequence = 1))
        assertNull(next(unknown, ready, reject))
        val unknownThenRejected = unknown + review(OcrReviewState.REJECTED, sequence = 2)
        assertNull(next(unknownThenRejected, ready, undo))
    }

    private fun next(reviews: List<OcrSpanReview>, state: OcrRevisionState, action: OcrSpanReviewAction) =
        OcrSpanReviewRules.next(reviews, state, action)

    private fun review(state: OcrReviewState, createdAt: Long = 1, sequence: Long) =
        OcrSpanReview(UUID.randomUUID(), spanId, state, createdAt, sequence)
}
