package org.openlife.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.R
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRevision
import org.openlife.vault.ocr.OcrRevisionState
import org.openlife.vault.ocr.OcrRunResult
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.ocr.OcrSpanView
import org.openlife.vault.ocr.OcrView
import org.openlife.vault.repository.ReadyReadResult

/**
 * P3-05-R3 (C2-R39): an earlier reading opens in the viewer with a visible
 * label and no action that changes anything. The current reading keeps its
 * actions. Device evidence: pending the physical tablet (see
 * docs/verification/P3-05-facts-ui.md).
 */
@RunWith(AndroidJUnit4::class)
class EarlierReadingViewerTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun anEarlierReadingIsLabelledAndOffersNoMutatingAction() {
        val source = source()
        val span = span(source.id)
        composeRule.setContent {
            OpenLifeTheme {
                ViewerScreen(
                    source = source,
                    loadContent = { ReadyReadResult.Loaded(tinyJpeg()) },
                    onBack = {},
                    onDeleteRequested = {},
                    ocrState = readyState(source, span),
                    initialSelectedSpanId = span.id,
                    readOnly = true,
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.viewer_earlier_reading)).assertIsDisplayed()
        MUTATING_TEXTS.forEach { text ->
            val found = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size
            assertEquals("read-only must not offer: $text", 0, found)
        }
        val facts = composeRule.onAllNodesWithTag("viewer_open_facts").fetchSemanticsNodes().size
        assertEquals("read-only must not offer facts", 0, facts)
        assertEquals(
            "read-only must not offer delete",
            0,
            composeRule.onAllNodesWithContentDescription("Delete", substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun aCurrentReadingStillOffersItsActions() {
        val source = source()
        val span = span(source.id)
        composeRule.setContent {
            OpenLifeTheme {
                ViewerScreen(
                    source = source,
                    loadContent = { ReadyReadResult.Loaded(tinyJpeg()) },
                    onBack = {},
                    onDeleteRequested = {},
                    ocrState = readyState(source, span),
                    initialSelectedSpanId = span.id,
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.viewer_correct)).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("viewer_open_facts").fetchSemanticsNodes().isNotEmpty())
        val label = context.getString(R.string.viewer_earlier_reading)
        assertEquals(0, composeRule.onAllNodesWithText(label).fetchSemanticsNodes().size)
    }

    /**
     * P3-05 review U-01: a route that names a reading of another source shows "unavailable" and never that
     * reading's text over this source's image.
     */
    @Test
    fun readingOfAnotherSourceIsRejected() {
        val source = source()
        val otherRevision = UUID(0L, 80L)
        val otherSpan = OcrSpan(UUID(0L, 81L), otherRevision, 0, OTHER_TEXT, null, OcrCoordinateSystem.SOURCE_PIXELS, null)
        val otherSourceId = UUID(0L, 82L)
        val revision = OcrRevision(
            id = otherRevision,
            sourceId = otherSourceId,
            state = OcrRevisionState.READY,
            engineId = "test-engine",
            modelVersion = "1",
            orientation = Orientation.NORMAL,
            sourceDigest = ByteArray(32) { 1 },
            startedAt = 1L,
            extractedAt = 2L,
            reviewState = OcrReviewState.UNREVIEWED,
            failureReason = null,
            charCount = 4,
            spanCount = 1,
        )
        val backend = FixedReadingBackend(OcrView(revision, listOf(OcrSpanView(otherSpan, null)), null, emptyList()))
        val presenter = OcrPresenter(backend, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        composeRule.setContent {
            OpenLifeTheme {
                val state by presenter.readingState(source.id, otherRevision).collectAsState()
                ViewerScreen(
                    source = source,
                    loadContent = { ReadyReadResult.Loaded(tinyJpeg()) },
                    onBack = {},
                    onDeleteRequested = {},
                    ocrState = state,
                    readOnly = true,
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.content_unavailable)).assertIsDisplayed()
        assertEquals(
            "another source's text must not be shown",
            0,
            composeRule.onAllNodesWithText(OTHER_TEXT).fetchSemanticsNodes().size,
        )
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun source() = Source(
        id = UUID(0L, 61L),
        state = SourceState.READY,
        importedAt = 1L,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = 64,
        height = 64,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )

    private fun readyState(source: Source, span: OcrSpan) =
        OcrUiState.Ready(source.id, span.revisionId, listOf(span), reviewState = OcrReviewState.UNREVIEWED)

    private fun span(sourceId: UUID) = OcrSpan(
        UUID(0L, 62L),
        UUID(0L, 63L),
        0,
        "Synthetic appointment 14 October",
        null,
        OcrCoordinateSystem.SOURCE_PIXELS,
        OcrEvidenceRegion(0, 0, 50, 20),
    )

    private fun tinyJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    /** Serves one fixed reading whatever the request names: the store's answer is what the presenter must check. */
    private class FixedReadingBackend(private val view: OcrView) : OcrBackend {
        override fun observe(sourceId: UUID): Flow<OcrView?> = flowOf(null)

        override suspend fun run(sourceId: UUID): OcrRunResult = OcrRunResult.Failed(null, OcrFailureReason.SOURCE_NOT_READY)

        override fun observeReading(sourceId: UUID, revisionId: UUID): Flow<OcrView?> = flowOf(view)

        override suspend fun correct(revisionId: UUID, spanId: UUID?, correctedText: String) = Unit

        override suspend fun review(revisionId: UUID, reviewState: OcrReviewState) = Unit

        override suspend fun rejectSpan(spanId: UUID) = Unit

        override suspend fun undoRejectSpan(spanId: UUID) = Unit
    }

    private companion object {
        const val OTHER_TEXT = "Other source appointment 3 May"
        val MUTATING_TEXTS = listOf(
            "Extract text on this device",
            "Run again",
            "Correct",
            "Reject span",
            "Accept text",
            "Reject",
            "Undo reject",
        )
    }
}
