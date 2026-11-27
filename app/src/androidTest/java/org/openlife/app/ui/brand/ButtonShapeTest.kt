package org.openlife.app.ui.brand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.theme.OpenLifeTheme

/**
 * A-04 (P1-19-R9): buttons use the brief's 8 dp corner, not Material's pill.
 * Samples a pixel 9 dp in and 3 dp down from the drawn button's top-left
 * corner: inside an 8 dp corner, outside a full-height pill's arc.
 */
@RunWith(AndroidJUnit4::class)
class ButtonShapeTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun buttonsUseTheEightDpShape() {
        var containerArgb = 0
        var buttonArgb = 0
        var density = 1f
        composeRule.setContent {
            OpenLifeTheme(darkTheme = false) {
                density = LocalDensity.current.density
                containerArgb = BACKDROP.toArgbInt()
                buttonArgb = MaterialTheme.colorScheme.primary.toArgbInt()
                Box(Modifier.background(BACKDROP).padding(PADDING.dp).testTag(TAG)) {
                    OpenLifeButton(onClick = {}, modifier = Modifier.testTag(BUTTON_TAG)) { Text("Synthetic") }
                }
            }
        }
        val image = composeRule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
        // Locate the drawn button inside the captured image from layout bounds, not from assumed offsets.
        val tagBounds = composeRule.onNodeWithTag(TAG).fetchSemanticsNode().boundsInRoot
        val buttonBounds = composeRule.onNodeWithTag(BUTTON_TAG).fetchSemanticsNode().boundsInRoot
        val left = (buttonBounds.left - tagBounds.left).roundToInt()
        val top = (buttonBounds.top - tagBounds.top).roundToInt()
        val inside = image.getPixel(left + (9 * density).toInt(), top + (3 * density).toInt())
        // 1 dp in from the corner is outside an 8 dp arc at any density.
        val corner = image.getPixel(left + (1 * density).toInt(), top + (1 * density).toInt())
        assertEquals("the corner itself is outside the shape", containerArgb, corner)
        assertEquals("9 dp x 3 dp is inside an 8 dp corner (a pill would leave it outside)", buttonArgb, inside)
    }

    private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
        (alpha * 255 + 0.5f).toInt(),
        (red * 255 + 0.5f).toInt(),
        (green * 255 + 0.5f).toInt(),
        (blue * 255 + 0.5f).toInt(),
    )

    private companion object {
        const val TAG = "button-shape"
        const val PADDING = 16
        const val BUTTON_TAG = "button-shape-button"
        val BACKDROP = Color(0xFF00FF00)
    }
}
