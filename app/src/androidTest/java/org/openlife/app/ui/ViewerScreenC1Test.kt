package org.openlife.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
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

    /** P2-01-R3/P3: a correction is shown under the immutable OCR text, attributed to the user. */
    @Test
    fun correctionIsShownBesideImmutableOcrTextAndReviewChipReflectsState() {
        val sourceId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val span = OcrSpan(
            UUID.randomUUID(), revisionId, 0, "Appointment 14 October", null, OcrCoordinateSystem.SOURCE_PIXELS, null,
        )
        composeRule.setContent {
            ViewerScreen(
                source = readySource(sourceId),
                loadContent = { awaitCancellation() },
                onBack = {},
                onDeleteRequested = {},
                ocrState = OcrUiState.Ready(
                    sourceId = sourceId,
                    revisionId = revisionId,
                    spans = listOf(span),
                    corrections = mapOf(span.id to "Appointment 15 October"),
                    reviewState = org.openlife.vault.ocr.OcrReviewState.ACCEPTED,
                    engineId = "mlkit-latin",
                    modelVersion = "16.0.1",
                    extractedAt = 1_700_000_000_000L,
                ),
            )
        }

        composeRule.onNodeWithText("Appointment 14 October").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Corrected by you: Appointment 15 October").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Review: Accepted").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("mlkit-latin 16.0.1", substring = true).performScrollTo().assertIsDisplayed()
    }

    /** P2-01-R3: Run again asks for a new revision; earlier revisions stay listed under History. */
    @Test
    fun runAgainCreatesANewRevisionAndKeepsHistory() {
        val sourceId = UUID.randomUUID()
        var runs = 0
        composeRule.setContent {
            ViewerScreen(
                source = readySource(sourceId),
                loadContent = { awaitCancellation() },
                onBack = {},
                onDeleteRequested = {},
                ocrState = OcrUiState.Ready(
                    sourceId = sourceId,
                    revisionId = UUID.randomUUID(),
                    spans = emptyList(),
                    history = listOf(
                        OcrHistoryItem(
                            UUID.randomUUID(),
                            org.openlife.vault.ocr.OcrRevisionState.FAILED,
                            1_700_000_000_000L,
                            "mlkit-latin",
                            "16.0.1",
                        ),
                    ),
                ),
                onExtractText = { runs++ },
            )
        }

        composeRule.onNodeWithText("Run again").performScrollTo().performClick()
        assertEquals(1, runs)
        composeRule.onNodeWithText("History (1)").performScrollTo().performClick()
        composeRule.onNodeWithText("Failed", substring = true).performScrollTo().assertIsDisplayed()
    }

    /** P2-02-R6/R7, C1-R7: deleting the source clears its OCR panel and leaves no OCR rows. */
    @Test
    fun deletingTheSourceWhileOcrTextIsShownClearsItAndRemovesRows(): Unit = kotlinx.coroutines.runBlocking {
        val application = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<org.openlife.app.OpenLifeApp>()
        val (list, ocr) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            SourceListViewModel(application) to OcrViewModel(application)
        }
        val sourceId = saveSyntheticSource(application)

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { ocr.run(sourceId) }
        val finished = awaitNonNull("OCR finished") {
            ocr.states.value[sourceId]?.takeIf { it is OcrUiState.Ready || it is OcrUiState.Failed }
        }
        val revisionId = (finished as? OcrUiState.Ready)?.revisionId
        composeRule.setContent {
            val states by ocr.states.collectAsState()
            ViewerScreen(
                source = readySource(sourceId),
                loadContent = { awaitCancellation() },
                onBack = {},
                onDeleteRequested = {},
                ocrState = states[sourceId] ?: OcrUiState.Idle,
            )
        }
        composeRule.onNodeWithText("Extract text on this device").assertDoesNotExist()

        val deleted = java.util.concurrent.atomic.AtomicReference<org.openlife.vault.repository.DeleteResult?>(null)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { list.delete(sourceId) { deleted.set(it) } }
        awaitNonNull("deletion finished") { deleted.get() }

        awaitNonNull("OCR state forgotten after deletion") { Unit.takeIf { ocr.states.value[sourceId] == null } }
        composeRule.onNodeWithText("Extract text on this device").performScrollTo().assertIsDisplayed()
        val access = application.vault() as org.openlife.app.VaultAccess.Ready
        if (revisionId != null) assertEquals(null, access.ocrRepository.findRevision(revisionId))
    }

    private suspend fun saveSyntheticSource(application: org.openlife.app.OpenLifeApp): UUID {
        val intake = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            IntakeViewModel(application, androidx.lifecycle.SavedStateHandle())
        }
        val bitmap = android.graphics.Bitmap.createBitmap(48, 32, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(UUID.randomUUID().hashCode() or 0xFF000000.toInt())
        val bytes = java.io.ByteArrayOutputStream().also {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        bitmap.recycle()
        repeat(BUSY_RETRIES) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                intake.startImport(java.io.ByteArrayInputStream(bytes), "image/jpeg", IntakeKind.SHARE)
            }
            val settled = awaitNonNull {
                intake.state.value.takeIf { it is IntakeUiState.Preview || it is IntakeUiState.Busy }
            }
            if (settled is IntakeUiState.Preview) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { intake.confirmSave() }
                awaitNonNull { intake.state.value as? IntakeUiState.Saved }
                return settled.sourceId
            }
            kotlinx.coroutines.delay(BUSY_RETRY_DELAY_MS)
        }
        throw AssertionError("import stayed Busy")
    }

    private suspend fun <T : Any> awaitNonNull(what: String = "condition", timeoutMs: Long = 60_000, read: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        throw AssertionError("$what not met within ${timeoutMs}ms")
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

    private companion object {
        const val BUSY_RETRIES = 5
        const val BUSY_RETRY_DELAY_MS = 500L
    }
}
