package org.openlife.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.navigation.Routes
import org.openlife.app.test.FakeAppContainer
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState

/**
 * P1-20-R6 (replaces the source-grep LargeFontLayoutTest once run on a
 * device): at font scale 2 on a 320x640dp window, no clickable control in
 * the list, viewer, search, settings or about destination is narrower on
 * screen than its own measured width, and no text overflows its layout.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class LargeFontUiTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val container = FakeAppContainer(listOf(source()))
    private lateinit var navController: NavHostController

    @After
    fun closeContainer() = container.close()

    @Test
    fun noControlIsClippedAtFontScaleTwoOn320x640() {
        showAtLargeFont()
        val destinations = listOf<Any>(
            Routes.ListDetail(SOURCE_ID.toString()),
            Routes.Search,
            Routes.Settings,
            Routes.About,
        )
        assertNothingClipped("list")
        destinations.forEach { route ->
            composeRule.runOnIdle { navController.navigate(route) }
            assertNothingClipped(route.toString())
            composeRule.runOnIdle { navController.popBackStack() }
        }
    }

    /** P3-05-R6: the facts destination at 200% font on a 320x640dp window has no clipped control or text. */
    @Test
    fun factsAreNotClippedAtFontScaleTwoOn320x640() {
        showAtLargeFont()
        composeRule.runOnIdle { navController.navigate(Routes.Facts(SOURCE_ID.toString())) }
        assertNothingClipped("facts")
    }

    private fun showAtLargeFont() {
        composeRule.setContent {
            navController = rememberNavController()
            val config = DeviceConfigurationOverride.FontScale(2f) then
                DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp))
            DeviceConfigurationOverride(config) { OpenLifeTheme { OpenLifeNavHost(container, navController) } }
        }
    }

    private fun assertNothingClipped(where: String) {
        composeRule.waitForIdle()
        val root = composeRule.onRoot().fetchSemanticsNode()
        walk(root).forEach { node ->
            if (node.config.getOrNull(SemanticsActions.OnClick) != null) {
                val name = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                assertTrue(
                    "$where: a control is clipped horizontally: '$name' ${node.size} ${node.boundsInRoot}",
                    node.boundsInRoot.width + 1f >= node.size.width,
                )
            }
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            // The node's text is named so a failure says which label overflows.
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            layouts.forEach {
                assertTrue("$where: text overflows its layout: '$text'", !it.didOverflowWidth)
            }
        }
    }

    private fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onRoot() =
        onNode(androidx.compose.ui.test.isRoot(), useUnmergedTree = true)

    private fun source() = Source(
        id = SOURCE_ID,
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
        val SOURCE_ID: UUID = UUID(0L, 31L)
    }
}
