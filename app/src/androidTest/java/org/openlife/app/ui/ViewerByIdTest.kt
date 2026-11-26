package org.openlife.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.navigation.Routes
import org.openlife.app.test.FakeAppContainer
import org.openlife.app.test.StrictModeRule
import org.openlife.app.test.syntheticJpeg
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.repository.ReadyReadResult

/**
 * P1-20-R2: the viewer loads its source by id (SourceViewRepository's
 * observe-by-id flow behind [org.openlife.app.SourceStore.observeSource]),
 * independent of what the list has loaded. A missing id shows the
 * unavailable state and returns to the list exactly once.
 *
 * These tests host the real graph over [FakeAppContainer];
 * [ViewerByIdRealStoreTest] runs the same path through the real container.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class ViewerByIdTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private var container: FakeAppContainer? = null

    @After
    fun closeContainer() {
        container?.close()
    }

    @Test
    fun viewerOpensASourceThatIsNotInTheLoadedList() {
        val hidden = source(UUID(0L, 21L))
        setHost(FakeAppContainer(listOf(hidden), readContent = { ReadyReadResult.Loaded(syntheticJpeg()) }, listed = {
            SourceListUiState.Loaded(emptyList())
        }))
        open(hidden.id)

        awaitImage()
    }

    @Test
    fun viewerOpenedBeforeTheListLoadsShowsTheSource() {
        val source = source(UUID(0L, 22L))
        setHost(FakeAppContainer(listOf(source), readContent = { ReadyReadResult.Loaded(syntheticJpeg()) }, listed = {
            null
        }))
        open(source.id)

        awaitImage()
    }

    @Test
    fun deletedIdShowsUnavailableAndReturnsToListOnce() {
        val fake = FakeAppContainer(emptyList())
        setHost(fake)
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithText(IMPORT).fetchSemanticsNodes().isNotEmpty() }

        // Hold frames so the unavailable state is observable before the exit pops it.
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { navController.navigate(Routes.ListDetail(UUID(0L, 23L).toString())) }
        var sawUnavailable = false
        repeat(MAX_FRAMES) {
            if (!sawUnavailable) {
                composeRule.mainClock.advanceTimeByFrame()
                sawUnavailable = composeRule.onAllNodesWithTag("viewer_unavailable").fetchSemanticsNodes().isNotEmpty()
            }
        }
        composeRule.mainClock.autoAdvance = true
        assertTrue("the unavailable state was not shown", sawUnavailable)

        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithText(IMPORT).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
        val entries = composeRule.runOnIdle { navController.currentBackStack.value.filter { it.destination !is NavGraph } }
        assertEquals("one exit leaves exactly the list", 1, entries.size)
        assertTrue("the remaining entry is the list", entries.single().destination.hasRoute<Routes.List>())
        assertFalse("a second exit popped the list", composeRule.activity.isFinishing)
    }

    private fun setHost(fake: FakeAppContainer) {
        container = fake
        composeRule.setContent {
            navController = rememberNavController()
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 800.dp))) {
                OpenLifeTheme { OpenLifeNavHost(fake, navController) }
            }
        }
    }

    private fun open(id: UUID) {
        composeRule.runOnIdle { navController.navigate(Routes.ListDetail(id.toString())) }
    }

    private fun awaitImage() {
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithContentDescription("Saved image").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun source(id: UUID) = Source(
        id = id,
        state = SourceState.READY,
        importedAt = 1L,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = 64,
        height = 48,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )

    private companion object {
        const val WAIT_MS = 10_000L
        const val MAX_FRAMES = 60
        const val IMPORT = "Import from photos"
    }
}
