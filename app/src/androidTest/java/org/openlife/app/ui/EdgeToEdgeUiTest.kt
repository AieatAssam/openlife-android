package org.openlife.app.ui

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.MainActivity
import org.openlife.app.test.StrictModeRule

/**
 * P1-20-R6 (replaces the source-grep EdgeToEdgeInsetsTest once run on a
 * device): with large system-bar insets dispatched to the real MainActivity,
 * the list's title and its import action stay clear of both bars.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class EdgeToEdgeUiTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Before
    fun acknowledgeFirstRun() {
        FirstRunPreferences.setAcknowledged(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun contentClearsSystemBarsWithLargeInsets() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var density = 1f
            var heightPx = 0
            scenario.onActivity { activity ->
                density = activity.resources.displayMetrics.density
                val content = activity.window.decorView
                heightPx = content.height
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, INSET_PX, 0, INSET_PX))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(content, insets)
            }
            composeRule.waitForIdle()
            val inset = Dp(INSET_PX / density)
            val bottom = Dp(heightPx / density) - inset

            val title = composeRule.onNodeWithText("OpenLife").getBoundsInRoot()
            assertTrue("the title is under the status bar", title.top >= inset)
            val import = composeRule.onNodeWithText("Import from photos").getBoundsInRoot()
            assertTrue("the import action is under the navigation bar", import.bottom <= bottom)
        }
    }

    private companion object {
        const val INSET_PX = 300
    }
}
