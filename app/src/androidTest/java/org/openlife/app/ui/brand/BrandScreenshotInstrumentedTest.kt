package org.openlife.app.ui.brand

import kotlinx.coroutines.awaitCancellation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.ui.FirstRunExplanationScreen
import org.openlife.app.ui.SourceListScreen
import org.openlife.app.ui.SourceListUiState
import org.openlife.app.ui.ViewerScreen
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState

@RunWith(AndroidJUnit4::class)
class BrandScreenshotInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val generateGoldens = InstrumentationRegistry.getArguments()
        .getString("generateGoldens") == "true"
    private val activeSpec = mutableStateOf(ScreenSpec("brand-components", false) {})

    /**
     * Rendering differs between system images, GPU paths and densities, so a
     * golden is only meaningful on the profile that produced it. CI legs are
     * the reference; generate them there with scripts/ci/generate-goldens.sh.
     */
    private val renderProfile: String = run {
        val densityDpi = InstrumentationRegistry.getInstrumentation().targetContext
            .resources.displayMetrics.densityDpi
        "api${android.os.Build.VERSION.SDK_INT}-${android.os.Build.PRODUCT}-${densityDpi}dpi"
    }

    @Test
    fun listViewerFirstRunMatchGoldensLightAndDark() {
        if (!generateGoldens) {
            val committed = InstrumentationRegistry.getInstrumentation().context.assets
                .list("golden/$renderProfile").orEmpty()
            // Reported as skipped, never as passed: see docs/design/screens.md.
            assumeTrue("no committed goldens for render profile $renderProfile", committed.isNotEmpty())
        }
        composeRule.setContent {
            val spec = activeSpec.value
            OpenLifeTheme(darkTheme = spec.darkTheme) {
                val pixelWidth = with(androidx.compose.ui.platform.LocalDensity.current) { 360.toDp() }
                val pixelHeight = with(androidx.compose.ui.platform.LocalDensity.current) { 640.toDp() }
                Box(
                    modifier = Modifier
                        .requiredSize(pixelWidth, pixelHeight)
                        .background(MaterialTheme.colorScheme.background)
                        .testTag(spec.tag),
                ) {
                    spec.content()
                }
            }
        }
        listOf(false, true).forEach { darkTheme ->
            capture("brand-components", darkTheme) {
                Column {
                    StampBadge(StampState.Confirmed)
                    FoldedCornerCard {
                        androidx.compose.material3.Text("Synthetic record")
                    }
                    PerforationDivider()
                }
            }
            capture("list", darkTheme) {
                SourceListScreen(
                    state = SourceListUiState.Loaded(emptyList()),
                    loadThumbnail = { null },
                    onOpen = {},
                    onDelete = {},
                    onImportFromPhotoPicker = {},
                )
            }
            capture("viewer", darkTheme) {
                ViewerScreen(
                    source = readySource(),
                    loadContent = { awaitCancellation() },
                    onBack = {},
                    onDeleteRequested = {},
                )
            }
            capture("first-run", darkTheme) {
                FirstRunExplanationScreen(onContinue = {})
            }
        }
    }

    private fun capture(name: String, darkTheme: Boolean, content: @Composable () -> Unit) {
        val tag = "$name-${if (darkTheme) "dark" else "light"}"
        activeSpec.value = ScreenSpec(tag, darkTheme, content)
        composeRule.waitForIdle()
        val bitmap = composeRule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val filename = "$name-${if (darkTheme) "dark" else "light"}.png"
        if (generateGoldens) {
            writeGolden(filename, bitmap)
        } else {
            assertGolden(filename, bitmap)
        }
    }

    private fun writeGolden(filename: String, bitmap: Bitmap) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir("goldens/$renderProfile")!!
        directory.mkdirs()
        File(directory, filename).outputStream().use { output ->
            assertTrue("could not write golden $filename", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun assertGolden(filename: String, actual: Bitmap) {
        val expected = InstrumentationRegistry.getInstrumentation().context.assets
            .open("golden/$renderProfile/$filename").use { BitmapFactory.decodeStream(it)!! }
        assertEquals("golden width for $filename", expected.width, actual.width)
        assertEquals("golden height for $filename", expected.height, actual.height)
        var differentPixels = 0
        for (x in 0 until actual.width) {
            for (y in 0 until actual.height) {
                val expectedPixel = expected.getPixel(x, y)
                val actualPixel = actual.getPixel(x, y)
                if (channelDifference(expectedPixel, actualPixel) > 3) differentPixels++
            }
        }
        val tolerance = actual.width * actual.height * 0.005
        assertTrue(
            "$renderProfile/$filename differs in $differentPixels pixels; tolerance is $tolerance",
            differentPixels <= tolerance,
        )
    }

    private fun channelDifference(first: Int, second: Int): Int = maxOf(
        kotlin.math.abs(android.graphics.Color.red(first) - android.graphics.Color.red(second)),
        kotlin.math.abs(android.graphics.Color.green(first) - android.graphics.Color.green(second)),
        kotlin.math.abs(android.graphics.Color.blue(first) - android.graphics.Color.blue(second)),
        kotlin.math.abs(android.graphics.Color.alpha(first) - android.graphics.Color.alpha(second)),
    )

    private fun readySource(): Source = Source(
        id = UUID(0L, 1L),
        state = SourceState.READY,
        importedAt = 1L,
        intakeKind = org.openlife.vault.model.IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = 32,
        height = 24,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )

    private data class ScreenSpec(
        val tag: String,
        val darkTheme: Boolean,
        val content: @Composable () -> Unit,
    )
}
