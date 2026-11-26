package org.openlife.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.navigation.Routes
import org.openlife.app.settings.AboutScreen
import org.openlife.app.test.FakeAppContainer
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState

/**
 * P1-16-R1: at expanded width the list and the viewer are two panes of one
 * screen; at compact width opening an item replaces the list. P1-16-R4: a
 * low-RAM device is told about the supported memory floor. P1-20-R3: the
 * selection is a route argument, so a resize keeps it and Back is one pop.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val container = FakeAppContainer(listOf(readySource()))
    private var size by mutableStateOf(DpSize(400.dp, 800.dp))
    private lateinit var navController: NavHostController

    @After
    fun closeContainer() = container.close()

    @Test
    fun expandedWidthShowsTwoPanes() {
        setHost(DpSize(1280.dp, 800.dp))

        composeRule.onNodeWithText(PLACEHOLDER).assertIsDisplayed()
        composeRule.onNodeWithText("Imported", substring = true).performClick()

        // The viewer's own top bar is in the detail pane (its content scrolls below).
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText(PLACEHOLDER).assertDoesNotExist()
        // The list, with its import action, is still on screen beside the viewer.
        composeRule.onNodeWithText("Import from photos").assertIsDisplayed()
    }

    @Test
    fun compactWidthOpensTheViewerInPlaceOfTheList() {
        setHost(DpSize(400.dp, 800.dp))

        composeRule.onNodeWithText("Imported", substring = true).performClick()

        composeRule.onNodeWithText("Details").assertIsDisplayed()
        composeRule.onNodeWithText("Import from photos").assertDoesNotExist()
    }

    /** P1-20-R3: compact to expanded and back keeps the selected source and the back stack. */
    @Test
    fun selectionSurvivesCompactToExpandedAndBack() {
        setHost(DpSize(400.dp, 800.dp))
        composeRule.onNodeWithText("Imported", substring = true).performClick()
        composeRule.onNodeWithText("Details").assertIsDisplayed()
        val stack = backStackRoutes()

        size = DpSize(1280.dp, 800.dp)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Details").assertIsDisplayed()
        composeRule.onNodeWithText("Import from photos").assertIsDisplayed()
        assertEquals("a resize does not navigate", stack, backStackRoutes())

        size = DpSize(400.dp, 800.dp)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Details").assertIsDisplayed()
        composeRule.onNodeWithText("Import from photos").assertDoesNotExist()
        assertEquals(stack, backStackRoutes())
        composeRule.runOnIdle {
            assertEquals(SOURCE_ID.toString(), navController.currentBackStackEntry!!.toRoute<Routes.ListDetail>().sourceId)
        }
    }

    /** P1-20-R3: at expanded width Back first clears the selection, then leaves. */
    @Test
    fun expandedBackClearsSelectionBeforeLeaving() {
        setHost(DpSize(1280.dp, 800.dp))
        composeRule.onNodeWithText("Imported", substring = true).performClick()
        composeRule.onNodeWithText(PLACEHOLDER).assertDoesNotExist()

        pressBack()
        composeRule.onNodeWithText(PLACEHOLDER).assertIsDisplayed()
        composeRule.onNodeWithText("Import from photos").assertIsDisplayed()

        pressBackUnconditionally()
        composeRule.waitForIdle()
        assertTrue("the second Back leaves", composeRule.activity.isFinishing)
    }

    @Test
    fun aboutWarnsOnLowRamDevice() {
        composeRule.setContent { OpenLifeTheme { AboutScreen(lowRamDevice = true) } }

        composeRule.onNodeWithText("low-memory device", substring = true).assertIsDisplayed()
    }

    private fun setHost(initial: DpSize) {
        size = initial
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                OpenLifeTheme { Host() }
            }
        }
    }

    @Composable
    private fun Host() {
        navController = rememberNavController()
        OpenLifeNavHost(container, navController)
    }

    private fun backStackRoutes(): List<String?> = composeRule.runOnIdle {
        navController.currentBackStack.value.mapNotNull { it.destination.route }
    }

    private fun readySource() = Source(
        id = SOURCE_ID,
        state = SourceState.READY,
        importedAt = 1L,
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

    private companion object {
        const val PLACEHOLDER = "Select an item to see it here."
        val SOURCE_ID: UUID = UUID(0L, 16L)
    }
}
