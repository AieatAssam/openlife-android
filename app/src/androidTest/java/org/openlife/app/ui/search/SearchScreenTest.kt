package org.openlife.app.ui.search

import android.content.Context
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.MainActivity
import org.openlife.app.R
import org.openlife.app.lock.AppLockPolicy
import org.openlife.app.lock.AppLockPolicyStore
import org.openlife.app.lock.LockAvailability
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.navigation.SearchHostState
import org.openlife.app.settings.AppLockSettings
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.FirstRunPreferences
import org.openlife.app.ui.OcrUiState
import org.openlife.app.ui.SourceListUiState
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
import org.openlife.vault.repository.VaultResetResult
import org.openlife.vault.search.SearchHit

/**
 * P2-08-R4/R7 through the real navigation graph ([OpenLifeNavHost]): tapping
 * a result opens the viewer route with that span selected (the P2-06
 * selection: the live-region announcement names it), at compact width as a
 * full screen whose Back returns to the results and at expanded width as the
 * list's detail pane; and the search field is empty after the activity stops.
 *
 * Review W-06 (safe on a personally owned tablet): the activity test restores
 * the first-run flag it sets, in `finally`, and is skipped, not hung, when app
 * lock is on. Not exercised here: the real vault query (SearchRepositoryTest)
 * and the ViewModel's own flow (the NavHost gets a fixed [SearchHostState]).
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

    /** The real activity: type, stop, come back, reopen search: the field is empty. */
    @Test
    fun fieldIsEmptyAfterTheActivityStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeFalse(
            "app lock is on for this install; this test would stop at the lock screen, so it is skipped",
            AppLockPolicyStore.read(context).enabled,
        )
        val wasAcknowledged = FirstRunPreferences.isAcknowledged(context)
        try {
            FirstRunPreferences.setAcknowledged(context)
            typeStopAndReopen(context)
        } finally {
            if (!wasAcknowledged) {
                context.getSharedPreferences(FIRST_RUN_PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .remove(FIRST_RUN_ACKNOWLEDGED)
                    .commit()
            }
        }
    }

    private fun typeStopAndReopen(context: Context) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
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

    private fun openTheResultFromSearch() {
        val searchLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.list_search_content_description)
        composeRule.onNodeWithContentDescription(searchLabel).performClick()
        composeRule.onAllNodesWithTag("search_result").assertCountEqualsOne()
        composeRule.onNodeWithTag("search_result").performClick()
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithTag("viewer_span_announcement").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("viewer_span_announcement").assertTextContains("Pick up by Friday", substring = true)
    }

    private fun setHost(size: DpSize) {
        val hit = SearchHit(sourceId, revisionId, target.id, target.text, false, listOf(0..3))
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                OpenLifeTheme {
                    OpenLifeNavHost(
                        listState = SourceListUiState.Loaded(listOf(readySource(sourceId))),
                        thumbnailGeneration = 0L,
                        ocrState = { OcrUiState.Ready(sourceId, revisionId, listOf(first, target)) },
                        loadThumbnail = { null },
                        loadReadyContent = { ReadyReadResult.Loaded(jpeg()) },
                        delete = { _, _ -> },
                        extractText = {},
                        cancelOcr = {},
                        correct = { _, _, _ -> },
                        review = { _, _ -> },
                        onImportFromPhotoPicker = {},
                        onRetryVault = {},
                        onFinishReset = {},
                        onResetVault = { VaultResetResult.FAILED },
                        onVerifyAll = { null },
                        appLock = AppLockSettings(AppLockPolicy(), { LockAvailability.AVAILABLE }, {}),
                        onResetComplete = {},
                        search = SearchHostState(query = "pick", state = SearchUiState.Results(listOf(hit))),
                    )
                }
            }
        }
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

        // FirstRunPreferences has no setter that clears the flag; these mirror its private names.
        const val FIRST_RUN_PREFERENCES = "first_run"
        const val FIRST_RUN_ACKNOWLEDGED = "acknowledged"
    }
}
