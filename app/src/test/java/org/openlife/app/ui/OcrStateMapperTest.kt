package org.openlife.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.model.Orientation
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRevision
import org.openlife.vault.ocr.OcrRevisionState
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.ocr.OcrSpanView
import org.openlife.vault.ocr.OcrView
import java.util.UUID

/**
 * P1-18-R14 (VS-1): a revision carrying a value this build does not
 * recognise is shown as unreadable with an explanation, never as text and
 * never as a crash.
 */
class OcrStateMapperTest {
    private val sourceId = UUID.randomUUID()

    @Test
    fun unrecognisedRevisionStateIsUnreadable() {
        assertUnreadable(view(state = OcrRevisionState.UNRECOGNISED))
    }

    @Test
    fun readyRevisionWithAnyUnrecognisedPartIsUnreadableNotShownAsText() {
        assertUnreadable(view(review = OcrReviewState.UNRECOGNISED))
        assertUnreadable(view(coordinates = OcrCoordinateSystem.UNRECOGNISED))
        assertUnreadable(view(orientation = null))
    }

    @Test
    fun aFullyRecognisedReadyRevisionIsStillShown() {
        val state = OcrStateMapper.map(sourceId, Persisted.Loaded(view()), running = false, unrecordedFailure = null)
        assertTrue("$state", state is OcrUiState.Ready)
    }

    private fun assertUnreadable(view: OcrView) {
        val state = OcrStateMapper.map(sourceId, Persisted.Loaded(view), running = false, unrecordedFailure = null)
        assertEquals(OcrFailureReason.UNRECOGNISED, (state as? OcrUiState.Failed)?.reason)
    }

    private fun view(
        state: OcrRevisionState = OcrRevisionState.READY,
        review: OcrReviewState = OcrReviewState.UNREVIEWED,
        coordinates: OcrCoordinateSystem = OcrCoordinateSystem.SOURCE_PIXELS,
        orientation: Orientation? = Orientation.NORMAL,
    ): OcrView {
        val revision = OcrRevision(
            id = UUID.randomUUID(),
            sourceId = sourceId,
            state = state,
            engineId = "test-engine",
            modelVersion = "1",
            orientation = orientation,
            sourceDigest = ByteArray(32) { 1 },
            startedAt = 1L,
            extractedAt = 2L,
            reviewState = review,
            failureReason = null,
            charCount = 4,
            spanCount = 1,
        )
        val span = OcrSpan(UUID.randomUUID(), revision.id, 0, "text", null, coordinates, null)
        return OcrView(revision, listOf(OcrSpanView(span, null)), null, emptyList())
    }
}
