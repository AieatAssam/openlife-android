package org.openlife.app.ui.search

import android.graphics.Bitmap
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.R
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.test.FakeAppContainer
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRevision
import org.openlife.vault.ocr.OcrRevisionState
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.ocr.OcrSpanView
import org.openlife.vault.ocr.OcrView
import org.openlife.vault.repository.ReadyReadResult
import org.openlife.vault.search.SearchHit

/**
 * P2-08-R4/R7 through the real navigation graph ([OpenLifeNavHost]): tapping
 * a result opens the viewer route with that span selected (the P2-06
 * selection: the live-region announcement names it), at compact width as a
 * full screen whose Back returns to the results and at expanded width as the
 * list's detail pane; and the search field is empty after the activity stops.
 *
 * The real-activity "field is empty after stop" check lives in
 * [SearchFieldResetTest]: a compose rule in this class stops the real list from
 * recomposing. Not exercised here: the real vault query (SearchRepositoryTest).
 * P1-20: the host is the real graph over a [FakeAppContainer]; the query is
 * typed into the entry-scoped SearchViewModel instead of being injected.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class SearchScreenTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val sourceId = UUID.randomUUID()
    private val revisionId = UUID.randomUUID()
    private val first = span(0, "Parcel collection slip", OcrEvidenceRegion(0, 0, 50, 20))
    private val target = span(1, "Pick up by Friday", OcrEvidenceRegion(0, 40, 80, 60))
    private var fake: FakeAppContainer? = null

    @Test
    fun compactResultOpensTheViewerRouteWithTheSpanSelectedAndBackReturnsToResults() {
        setHost(DpSize(400.dp, 800.dp))
        openTheResultFromSearch()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithTag("search_result").fetchSemanticsNodes().size == 1
        }
    }

    @Test
    fun expandedResultSelectsTheSpanInTheDetailPaneBesideTheList() {
        setHost(DpSize(1280.dp, 800.dp))
        openTheResultFromSearch()

        // The viewer route popped back to the list; the viewer is its detail pane.
        composeRule.onNodeWithText("Import from photos").assertIsDisplayed()
        composeRule.onNodeWithTag("search_result").assertDoesNotExist()
    }

    private fun openTheResultFromSearch() {
        val searchLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.list_search_content_description)
        composeRule.onNodeWithContentDescription(searchLabel).performClick()
        composeRule.onNodeWithTag("search_input").performTextInput("pick")
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithTag("search_result").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("search_result").assertCountEqualsOne()
        composeRule.onNodeWithTag("search_result").performClick()
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onNodeWithTag("viewer_span_announcement").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
        }
        composeRule.onNodeWithTag("viewer_span_announcement").assertTextContains("Pick up by Friday", substring = true)
    }

    private fun setHost(size: DpSize) {
        val hit = SearchHit(sourceId, revisionId, target.id, target.text, false, listOf(0..3))
        val container = FakeAppContainer(
            initialSources = listOf(readySource(sourceId)),
            readContent = { ReadyReadResult.Loaded(jpeg()) },
            searchHits = listOf(hit),
            ocrView = { ocrView() },
        ).also { fake = it }
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                OpenLifeTheme { OpenLifeNavHost(container) }
            }
        }
    }

    @After
    fun closeContainer() {
        fake?.close()
    }

    private fun ocrView(): OcrView {
        val revision = OcrRevision(
            id = revisionId,
            sourceId = sourceId,
            state = OcrRevisionState.READY,
            engineId = "test-engine",
            modelVersion = "1",
            orientation = Orientation.NORMAL,
            sourceDigest = ByteArray(32) { 1 },
            startedAt = 1L,
            extractedAt = 2L,
            reviewState = OcrReviewState.UNREVIEWED,
            failureReason = null,
            charCount = first.text.length + target.text.length,
            spanCount = 2,
        )
        return OcrView(revision, listOf(OcrSpanView(first, null), OcrSpanView(target, null)), null, emptyList())
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsOne() {
        assertTrue(fetchSemanticsNodes().size == 1)
    }

    private fun span(ordinal: Int, text: String, region: OcrEvidenceRegion) = OcrSpan(
        id = UUID.randomUUID(),
        revisionId = revisionId,
        ordinal = ordinal,
        text = text,
        confidence = null,
        coordinateSystem = OcrCoordinateSystem.SOURCE_PIXELS,
        evidenceRegion = region,
    )

    private fun readySource(id: UUID) = Source(
        id = id,
        state = SourceState.READY,
        importedAt = 1,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = 100,
        height = 100,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
