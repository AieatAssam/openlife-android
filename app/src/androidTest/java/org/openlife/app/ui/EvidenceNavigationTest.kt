package org.openlife.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
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
        composeRule.onNodeWithTag("viewer_evidence_image").performTouchInput { click() }
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

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
