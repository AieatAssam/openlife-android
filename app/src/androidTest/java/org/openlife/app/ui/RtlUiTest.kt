package org.openlife.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.test.FakeAppContainer
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState

/**
 * P1-20-R6 (replaces the source-grep RtlLayoutTest once run on a device): in
 * a right-to-left layout the list pane sits at the right of the expanded
 * list-detail screen, and the viewer's Back control at the right of its bar.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class RtlUiTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val container = FakeAppContainer(listOf(source()))

    @After
    fun closeContainer() = container.close()

    @Test
    fun listAndViewerMirrorInRtl() {
        composeRule.setContent {
            val config = DeviceConfigurationOverride.LayoutDirection(LayoutDirection.Rtl) then
                DeviceConfigurationOverride.ForcedSize(DpSize(WIDTH.dp, 800.dp))
            DeviceConfigurationOverride(config) { OpenLifeTheme { OpenLifeNavHost(container) } }
        }
        val half = WIDTH.dp / 2

        val placeholder = composeRule.onNodeWithText(PLACEHOLDER).getBoundsInRoot()
        val import = composeRule.onNodeWithText("Import from photos").getBoundsInRoot()
        assertTrue("the list pane is not on the right in RTL", import.left > placeholder.right)

        composeRule.onNodeWithText("Imported", substring = true).performClick()
        val back = composeRule.onNodeWithContentDescription("Back").getBoundsInRoot()
        assertTrue("the viewer's Back is not mirrored", back.left > half)
    }

    private fun source() = Source(
        id = UUID(0L, 41L),
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
        const val WIDTH = 1280
        const val PLACEHOLDER = "Select an item to see it here."
    }
}
