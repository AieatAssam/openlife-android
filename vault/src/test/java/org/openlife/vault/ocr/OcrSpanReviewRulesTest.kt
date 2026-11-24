package org.openlife.vault.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** P2-06-R12/R13 (E-05, E-06): which review row is current, and what reject/undo may append. */
class OcrSpanReviewRulesTest {
    private val spanId = UUID.randomUUID()

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
        assertEquals(OcrReviewState.REJECTED, OcrSpanReviewRules.next(emptyList(), ready, OcrSpanReviewAction.REJECT))
        val rejected = listOf(review(OcrReviewState.REJECTED, sequence = 1))
        assertNull("a rejected span cannot be rejected twice", OcrSpanReviewRules.next(rejected, ready, OcrSpanReviewAction.REJECT))
        OcrRevisionState.entries.filter { it != ready }.forEach { state ->
            assertNull("$state refuses a reject", OcrSpanReviewRules.next(emptyList(), state, OcrSpanReviewAction.REJECT))
        }
    }

    @Test
    fun undoRestoresTheStateBeforeTheRejectAndIsOnlyOfferedOnARejectedSpan() {
        val ready = OcrRevisionState.READY
        assertNull("nothing to undo", OcrSpanReviewRules.next(emptyList(), ready, OcrSpanReviewAction.UNDO_REJECT))
        assertEquals(
            OcrReviewState.UNREVIEWED,
            OcrSpanReviewRules.next(listOf(review(OcrReviewState.REJECTED, sequence = 1)), ready, OcrSpanReviewAction.UNDO_REJECT),
        )
        val acceptedThenRejected = listOf(
            review(OcrReviewState.REJECTED, sequence = 2),
            review(OcrReviewState.ACCEPTED, sequence = 1),
        )
        assertEquals(
            "undo restores what the span was before, by sequence",
            OcrReviewState.ACCEPTED,
            OcrSpanReviewRules.next(acceptedThenRejected, ready, OcrSpanReviewAction.UNDO_REJECT),
        )
        val undone = listOf(review(OcrReviewState.REJECTED, sequence = 1), review(OcrReviewState.UNREVIEWED, sequence = 2))
        assertNull("an undone span is no longer rejected", OcrSpanReviewRules.next(undone, ready, OcrSpanReviewAction.UNDO_REJECT))
        assertEquals(OcrReviewState.REJECTED, OcrSpanReviewRules.next(undone, ready, OcrSpanReviewAction.REJECT))
        assertNull(
            "undo also needs a READY revision",
            OcrSpanReviewRules.next(listOf(review(OcrReviewState.REJECTED, sequence = 1)), OcrRevisionState.STALE, OcrSpanReviewAction.UNDO_REJECT),
        )
    }

    @Test
    fun anUnrecognisedReviewIsNeverOverwrittenOrRestored() {
        val ready = OcrRevisionState.READY
        val unknown = listOf(review(OcrReviewState.UNRECOGNISED, sequence = 1))
        assertNull(OcrSpanReviewRules.next(unknown, ready, OcrSpanReviewAction.REJECT))
        val unknownThenRejected = unknown + review(OcrReviewState.REJECTED, sequence = 2)
        assertNull(OcrSpanReviewRules.next(unknownThenRejected, ready, OcrSpanReviewAction.UNDO_REJECT))
    }

    private fun review(state: OcrReviewState, createdAt: Long = 1, sequence: Long) =
        OcrSpanReview(UUID.randomUUID(), spanId, state, createdAt, sequence)
}
