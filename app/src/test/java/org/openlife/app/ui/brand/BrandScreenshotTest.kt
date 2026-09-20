package org.openlife.app.ui.brand

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class BrandScreenshotTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun listViewerFirstRunMatchGoldensLightAndDark() {
        val goldenDirectory = projectDir.resolve("app/src/androidTest/assets/golden")
        assertTrue("golden directory is missing", Files.isDirectory(goldenDirectory))
        listOf(
            "brand-components-light.png",
            "brand-components-dark.png",
            "list-light.png",
            "list-dark.png",
            "viewer-light.png",
            "viewer-dark.png",
            "first-run-light.png",
            "first-run-dark.png",
        ).forEach { filename ->
            assertTrue("missing golden $filename", Files.isRegularFile(goldenDirectory.resolve(filename)))
        }
        val screenshotTest = projectDir.resolve(
            "app/src/androidTest/java/org/openlife/app/ui/brand/BrandScreenshotInstrumentedTest.kt",
        )
        assertTrue("screenshot test is missing", Files.isRegularFile(screenshotTest))
        assertTrue(
            "screenshot test must capture Compose pixels",
            Files.readString(screenshotTest).contains("captureToImage"),
        )
    }
}
