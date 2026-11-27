package org.openlife.app.ui

import androidx.compose.ui.test.assert
import android.graphics.Bitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.center
import androidx.compose.ui.test.centerY
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.top
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.test.StrictModeRule
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.repository.ReadyReadResult

/**
 * P2-06-R3 / F-27: span and region selection meet, and the highlight sits on
 * the orientation-corrected image rather than the outer box.
 */
@RunWith(AndroidJUnit4::class)
class EvidenceNavigationTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tappingInsideARegionSelectsItsSpanAndAnnounces() {
        val sourceId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val wide = span(revisionId, 0, "wide context", OcrEvidenceRegion(0, 0, 100, 100))
        val centerSpan = span(revisionId, 1, "center token", OcrEvidenceRegion(40, 40, 60, 60))
        composeRule.setContent {
            ViewerScreen(
                source = readySource(sourceId, width = 100, height = 100, orientation = Orientation.NORMAL),
                loadContent = { ReadyReadResult.Loaded(jpeg(32, 32)) },
                onBack = {},
                onDeleteRequested = {},
                ocrState = OcrUiState.Ready(sourceId, revisionId, listOf(wide, centerSpan)),
            )
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("viewer_evidence_image"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        val region = composeRule.onNodeWithContentDescription("center token").fetchSemanticsNode()
        assertEquals(Role.Button, region.config.getOrNull(SemanticsProperties.Role))
        composeRule.onNodeWithTag("viewer_evidence_canvas").performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Selected: center token").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingASpanHighlightsItsRegionWithinTheImageBounds() {
        val sourceId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val span = span(revisionId, 0, "rotated line", OcrEvidenceRegion(0, 0, 100, 50))
        composeRule.setContent {
            ViewerScreen(
                source = readySource(sourceId, width = 100, height = 200, orientation = Orientation.ROTATE_90),
                loadContent = { ReadyReadResult.Loaded(jpeg(16, 32)) },
                onBack = {},
                onDeleteRequested = {},
                ocrState = OcrUiState.Ready(sourceId, revisionId, listOf(span)),
            )
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("viewer_evidence_image"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithText("rotated line").performScrollTo().performClick()
        composeRule.onNodeWithText("Selected: rotated line").performScrollTo().assertIsDisplayed()
        val image = composeRule.onNodeWithTag("viewer_evidence_image", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val overlay = composeRule.onNodeWithTag("viewer_evidence_overlay", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue("overlay $overlay must sit inside image $image", contains(image, overlay))
        assertTrue(
            "rotated highlight should sit on the right of the image, overlay=$overlay image=$image",
            overlay.left > image.left + image.width * 0.5f,
        )
    }

    // --- Plan 1.3.0: docs/reviews/2026-11-24-P2-06-pr23-review.md E-01, E-02, E-06 ---

    /** E-01 / P2-06-R8: at 3x the image is measured at three times its fit, not at canvas size. */
    @Test
    fun doubleTapZoomsImageToThreeTimesItsFitWidth() {
        val revisionId = UUID.randomUUID()
        val centerSpan = span(revisionId, 0, "center token", OcrEvidenceRegion(40, 40, 60, 60))
        showSquareSource(listOf(centerSpan))
        composeRule.waitForIdle()
        // SemanticsNode.size is read live, so the fit size is captured before the zoom.
        val fit = imageNode()
        val fitWidth = fit.size.width
        val fitHeight = fit.size.height

        composeRule.onNodeWithTag("viewer_evidence_canvas").performTouchInput { doubleClick(center) }
        composeRule.waitForIdle()

        val zoomed = imageNode()
        assertEquals("zoomed width", fitWidth * 3f, zoomed.size.width.toFloat(), 3f)
        assertEquals("zoomed height", fitHeight * 3f, zoomed.size.height.toFloat(), 3f)
        val region = composeRule.onNodeWithContentDescription("center token", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(
            "region ${unclipped(region)} must stay inside the zoomed image ${unclipped(zoomed)}",
            contains(unclipped(zoomed), unclipped(region)),
        )
    }

    /** E-01 / P2-06-R8: hit-testing at 3x uses the drawn transform. */
    @Test
    fun tapAtThreeTimesSelectsTheSpanUnderTheFinger() {
        val revisionId = UUID.randomUUID()
        // At 3x with no pan the canvas shows source 33.3..66.7: "upper left"
        // covers canvas 0.02..0.5 of the width, but at 1x only 0.34..0.5.
        val upperLeft = span(revisionId, 0, "upper left", OcrEvidenceRegion(34, 34, 50, 50))
        val lowerRight = span(revisionId, 1, "lower right", OcrEvidenceRegion(50, 50, 66, 66))
        showSquareSource(listOf(upperLeft, lowerRight))
        val canvas = composeRule.onNodeWithTag("viewer_evidence_canvas")
        canvas.performTouchInput { doubleClick(percentOffset(0.05f, 0.05f)) } // no region at 1x
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("viewer_span_announcement")
            .assert(androidx.compose.ui.test.SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription))
        composeRule.mainClock.advanceTimeBy(DOUBLE_TAP_GAP_MS)

        canvas.performTouchInput { click(percentOffset(0.25f, 0.25f)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Selected: upper left").performScrollTo().assertIsDisplayed()
    }

    /** E-02 / P2-06-R9: a scroll that starts on the image selects nothing and is not pulled back. */
    @Test
    fun verticalScrollStartingOnTheImageSelectsNothing() {
        val revisionId = UUID.randomUUID()
        val page = span(revisionId, 0, "whole page", OcrEvidenceRegion(0, 0, 100, 100))
        val filler = (1..40).map { ordinal -> ungrounded(revisionId, ordinal, "line $ordinal") }
        showSquareSource(listOf(page) + filler)
        val before = imageNode().positionInRoot.y

        composeRule.onNodeWithTag("viewer_evidence_canvas").performTouchInput {
            swipeUp(startY = centerY, endY = top + 1f, durationMillis = 300)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("viewer_span_announcement")
            .assert(androidx.compose.ui.test.SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription))
        val after = imageNode().positionInRoot.y
        assertTrue("the column scrolled ($before -> $after) and stayed scrolled", after < before - 10f)
    }

    /** E-02 / P2-06-R9: two fingers down then lifted, without moving, is not a tap. */
    @Test
    fun pinchReleaseDoesNotSelect() {
        val revisionId = UUID.randomUUID()
        val page = span(revisionId, 0, "whole page", OcrEvidenceRegion(0, 0, 100, 100))
        showSquareSource(listOf(page))

        composeRule.onNodeWithTag("viewer_evidence_canvas").performTouchInput {
            down(0, center)
            down(1, center + androidx.compose.ui.geometry.Offset(40f, 40f))
            up(0)
            up(1)
        }
        composeRule.onNodeWithTag("viewer_evidence_canvas").performTouchInput {
            pinch(
                start0 = center - androidx.compose.ui.geometry.Offset(20f, 0f),
                end0 = center - androidx.compose.ui.geometry.Offset(80f, 0f),
                start1 = center + androidx.compose.ui.geometry.Offset(20f, 0f),
                end1 = center + androidx.compose.ui.geometry.Offset(80f, 0f),
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("viewer_span_announcement")
            .assert(androidx.compose.ui.test.SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription))
    }

    /** E-02 / P2-06-R9: a pan caused by the first tap does not restart the detector. */
    @Test
    fun doubleTapAtThreeTimesZoomsOutEvenWhenTheFirstTapPans() {
        val revisionId = UUID.randomUUID()
        // At 3x with no pan this region draws at canvas 0.8..1.7: partly off-screen.
        val corner = span(revisionId, 0, "corner", OcrEvidenceRegion(60, 60, 90, 90))
        showSquareSource(listOf(corner))
        val fitWidth = imageNode().size.width
        val canvas = composeRule.onNodeWithTag("viewer_evidence_canvas")
        canvas.performTouchInput { doubleClick(percentOffset(0.05f, 0.05f)) }
        composeRule.waitForIdle()
        assertEquals(fitWidth * 3f, imageNode().size.width.toFloat(), 3f)
        composeRule.mainClock.advanceTimeBy(DOUBLE_TAP_GAP_MS)

        composeRule.mainClock.autoAdvance = false
        canvas.performTouchInput { click(percentOffset(0.9f, 0.9f)) } // selects "corner" and pans to it
        composeRule.mainClock.advanceTimeBy(SECOND_TAP_AFTER_MS) // recompose: the pan changes
        // A double tap lands on one spot: the pan moved the content, not the finger. Taps
        // 0.4 of the canvas apart are outside DOUBLE_TAP_SLOP (S-04) and are two single taps.
        canvas.performTouchInput { click(percentOffset(0.9f, 0.9f)) }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals("the second tap zoomed out", fitWidth.toFloat(), imageNode().size.width.toFloat(), 3f)
    }

    /** E-06 / P2-06-R13: a rejected span's region is drawn but is not evidence. */
    @Test
    fun rejectedSpanRegionCannotBeSelectedAsEvidence() {
        val sourceId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val rejected = span(revisionId, 0, "rejected token", OcrEvidenceRegion(30, 30, 70, 70))
        composeRule.setContent {
            ViewerScreen(
                source = readySource(sourceId, width = 100, height = 100, orientation = Orientation.NORMAL),
                loadContent = { ReadyReadResult.Loaded(jpeg(32, 32)) },
                onBack = {},
                onDeleteRequested = {},
                ocrState = OcrUiState.Ready(
                    sourceId,
                    revisionId,
                    listOf(rejected),
                    spanReviews = mapOf(rejected.id to org.openlife.vault.ocr.OcrReviewState.REJECTED),
                ),
            )
        }
        awaitImage()

        composeRule.onNodeWithTag("viewer_evidence_canvas").performTouchInput { click(center) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("viewer_span_announcement")
            .assert(androidx.compose.ui.test.SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription))
        // Exact label: the per-span Correct and Undo buttons name the span too, and are not evidence.
        composeRule.onAllNodes(hasClickAction() and hasContentDescription("rejected token"))
            .assertCountEquals(0)
        composeRule.onAllNodesWithText("Show region").assertCountEquals(0)
        composeRule.onNodeWithText("rejected token").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("viewer_span_announcement")
            .assert(androidx.compose.ui.test.SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription))
    }

    private fun showSquareSource(spans: List<OcrSpan>) {
        val sourceId = UUID.randomUUID()
        val revisionId = spans.first().revisionId
        composeRule.setContent {
            ViewerScreen(
                source = readySource(sourceId, width = 100, height = 100, orientation = Orientation.NORMAL),
                loadContent = { ReadyReadResult.Loaded(jpeg(32, 32)) },
                onBack = {},
                onDeleteRequested = {},
                ocrState = OcrUiState.Ready(sourceId, revisionId, spans),
            )
        }
        awaitImage()
    }

    private fun awaitImage() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("viewer_evidence_image"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun imageNode(): SemanticsNode =
        composeRule.onNodeWithTag("viewer_evidence_image", useUnmergedTree = true).fetchSemanticsNode()

    /** Position and size of the node itself, not clipped by the canvas. */
    private fun unclipped(node: SemanticsNode) = androidx.compose.ui.geometry.Rect(
        node.positionInRoot,
        androidx.compose.ui.geometry.Size(node.size.width.toFloat(), node.size.height.toFloat()),
    )

    private fun ungrounded(revisionId: UUID, ordinal: Int, text: String) = OcrSpan(
        id = UUID.randomUUID(),
        revisionId = revisionId,
        ordinal = ordinal,
        text = text,
        confidence = null,
        coordinateSystem = OcrCoordinateSystem.SOURCE_PIXELS,
        evidenceRegion = null,
    )

    private fun contains(outer: androidx.compose.ui.geometry.Rect, inner: androidx.compose.ui.geometry.Rect): Boolean =
        inner.left >= outer.left - 1f &&
            inner.top >= outer.top - 1f &&
            inner.right <= outer.right + 1f &&
            inner.bottom <= outer.bottom + 1f

    private fun span(revisionId: UUID, ordinal: Int, text: String, region: OcrEvidenceRegion) = OcrSpan(
        id = UUID.randomUUID(),
        revisionId = revisionId,
        ordinal = ordinal,
        text = text,
        confidence = null,
        coordinateSystem = OcrCoordinateSystem.SOURCE_PIXELS,
        evidenceRegion = region,
    )

    private fun readySource(
        id: UUID,
        width: Int,
        height: Int,
        orientation: Orientation,
    ) = Source(
        id = id,
        state = SourceState.READY,
        importedAt = 1,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = width,
        height = height,
        orientation = orientation,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )

    private companion object {
        /** Longer than the platform double-tap timeout (300 ms), so the next tap starts a new sequence. */
        const val DOUBLE_TAP_GAP_MS = 1_000L

        /** Well inside the double-tap timeout, long enough for the selection's pan to recompose. */
        const val SECOND_TAP_AFTER_MS = 120L
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
