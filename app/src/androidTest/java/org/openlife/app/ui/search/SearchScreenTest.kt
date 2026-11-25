package org.openlife.app.ui.search

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.MainActivity
import org.openlife.app.R
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.FirstRunPreferences
import org.openlife.app.ui.OcrUiState
import org.openlife.app.ui.ViewerScreen
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.repository.ReadyReadResult
import org.openlife.vault.search.SearchHit

/**
 * P2-08-R4/R7: tapping a result opens the viewer with that span selected
 * (the P2-06 selection: the live-region announcement names it), and the
 * search field is empty again after the activity is stopped.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class SearchScreenTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun resultOpensViewerWithSpanSelectedAndFieldClearsOnStop() {
        val sourceId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val first = span(revisionId, 0, "Parcel collection slip", OcrEvidenceRegion(0, 0, 50, 20))
        val target = span(revisionId, 1, "Pick up by Friday", OcrEvidenceRegion(0, 40, 80, 60))
        val hit = SearchHit(sourceId, revisionId, target.id, target.text, isCorrection = false, highlights = listOf(0..3))

        composeRule.setContent {
            OpenLifeTheme {
                var opened by remember { mutableStateOf<SearchHit?>(null) }
                val current = opened
                if (current == null) {
                    SearchScreen(
                        query = "pick",
                        state = SearchUiState.Results(listOf(hit)),
                        onQueryChange = {},
                        onOpenResult = { opened = it },
                        onBack = {},
                    )
                } else {
                    ViewerScreen(
                        source = readySource(sourceId),
                        loadContent = { ReadyReadResult.Loaded(jpeg()) },
                        onBack = {},
                        onDeleteRequested = {},
                        ocrState = OcrUiState.Ready(sourceId, revisionId, listOf(first, target)),
                        initialSelectedSpanId = current.spanId,
                    )
                }
            }
        }
        composeRule.onAllNodesWithTag("search_result").assertCountEqualsOne()
        composeRule.onNodeWithTag("search_result").performClick()
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithTag("viewer_span_announcement").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("viewer_span_announcement").assertTextContains("Pick up by Friday", substring = true)

        fieldIsEmptyAfterTheActivityStops()
    }

    /** The real activity: type, stop, come back, reopen search: the field is empty. */
    private fun fieldIsEmptyAfterTheActivityStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        FirstRunPreferences.setAcknowledged(context)
        val searchLabel = context.getString(R.string.list_search_content_description)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc(searchLabel)), WAIT_MS))
            device.findObject(By.desc(searchLabel)).click()
            assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), WAIT_MS))
            device.findObject(By.clazz("android.widget.EditText")).text = "synthetic query"
            assertTrue(device.wait(Until.hasObject(By.text("synthetic query")), WAIT_MS))

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            assertTrue(device.wait(Until.hasObject(By.desc(searchLabel)), WAIT_MS))
            device.findObject(By.desc(searchLabel)).click()
            assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), WAIT_MS))
            assertFalse("the query survived onStop", device.hasObject(By.text("synthetic query")))
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsOne() {
        assertTrue(fetchSemanticsNodes().size == 1)
    }

    private fun span(revisionId: UUID, ordinal: Int, text: String, region: OcrEvidenceRegion) = OcrSpan(
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
