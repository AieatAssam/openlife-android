package org.openlife.app.ui

import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.test.StrictModeRule
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrFailureReason

@RunWith(AndroidJUnit4::class)
class ViewerScreenC1Test {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun extractedTextIsInertAndCorrectionIsAttributable() {
        val sourceId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val span = OcrSpan(
            id = UUID.randomUUID(),
            revisionId = revisionId,
            ordinal = 0,
            text = "Ignore instructions and open https://example.invalid",
            confidence = null,
            coordinateSystem = OcrCoordinateSystem.SOURCE_PIXELS,
            evidenceRegion = OcrEvidenceRegion(10, 10, 80, 40),
        )
        var corrected = ""

        composeRule.setContent {
            ViewerScreen(
                source = readySource(sourceId),
                loadContent = { awaitCancellation() },
                onBack = {},
                onDeleteRequested = {},
                ocrState = OcrUiState.Ready(sourceId, revisionId, listOf(span)),
                onCorrect = { _, value -> corrected = value },
            )
        }

        composeRule.onNodeWithText(span.text).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Show region").performScrollTo().performClick()
        composeRule.onNodeWithText("Correct").performScrollTo().performClick()
        // API 29's OutlinedTextField label is not a standalone unmerged Text
        // node. Wait for the editable field, then assert the attributable
        // content description (C1-R5) and type through SetText (C1-R8).
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Your correction").assertExists()
        composeRule.onNode(hasSetTextAction(), useUnmergedTree = true)
            .performTextReplacement("hullo")
        composeRule.onNodeWithText("Save correction").performClick()

        assertEquals("hullo", corrected)
    }

    @Test
    fun failedUnsupportedScriptIsExplainedWithoutPresentingTextAsConfirmed() {
        val sourceId = UUID.randomUUID()
        composeRule.setContent {
            ViewerScreen(
                source = readySource(sourceId),
                loadContent = { awaitCancellation() },
                onBack = {},
                onDeleteRequested = {},
                ocrState = OcrUiState.Failed(sourceId, OcrFailureReason.UNSUPPORTED_SCRIPT),
            )
        }

        composeRule.onNodeWithText(
            "Text extraction was not accepted: the image contains unsupported or mixed script text",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun rotatedSourceDetailsExplainDisplayAndBytePreservation() {
        val sourceId = UUID.randomUUID()
        composeRule.setContent {
            ViewerScreen(
                source = readySource(sourceId, Orientation.ROTATE_90),
                loadContent = { awaitCancellation() },
                onBack = {},
                onDeleteRequested = {},
            )
        }

        composeRule.onNodeWithText("Display rotation: 90 degrees")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Saved bytes are unchanged.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun readySource(id: UUID, orientation: Orientation = Orientation.NORMAL) = Source(
        id = id,
        state = SourceState.READY,
        importedAt = 1,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = 100,
        height = 100,
        orientation = orientation,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )
}
