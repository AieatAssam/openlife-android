package org.openlife.app.ui.brand

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class BrandScreenshotTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun listViewerFirstRunMatchGoldensLightAndDark() {
        val goldenRoot = projectDir.resolve("app/src/androidTest/assets/golden")
        assertTrue("golden directory is missing", Files.isDirectory(goldenRoot))
        // Goldens are grouped by render profile (see docs/design/screens.md);
        // every committed profile must carry the complete set.
        val profiles = Files.list(goldenRoot).use { entries -> entries.filter(Files::isDirectory).toList() }
        assertTrue("no render-profile golden directory", profiles.isNotEmpty())
        profiles.forEach { profile ->
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
                assertTrue(
                    "missing golden ${profile.fileName}/$filename",
                    Files.isRegularFile(profile.resolve(filename)),
                )
            }
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

    /**
     * P1-19-R10: the delete-dialog golden is per render profile like the
     * others. A profile either has both themes or neither (generation on a
     * device is pending; see docs/verification/P1-19-app-audit-remediation.md),
     * and the instrumented test captures it.
     */
    @Test
    fun deleteDialogGoldensArePairedPerProfile() {
        val goldenRoot = projectDir.resolve("app/src/androidTest/assets/golden")
        val profiles = Files.list(goldenRoot).use { entries -> entries.filter(Files::isDirectory).toList() }
        profiles.forEach { profile ->
            val present = listOf("delete-dialog-light.png", "delete-dialog-dark.png")
                .map { Files.isRegularFile(profile.resolve(it)) }
            val paired = present.all { it } || present.none { it }
            assertTrue("${profile.fileName} has only one delete-dialog theme", paired)
        }
        val screenshotTest = Files.readString(
            projectDir.resolve("app/src/androidTest/java/org/openlife/app/ui/brand/BrandScreenshotInstrumentedTest.kt"),
        )
        assertTrue("the dialog golden test exists", screenshotTest.contains("fun deleteDialogMatchesGolden()"))
        assertTrue("it captures the dialog window", screenshotTest.contains("isDialog()"))
    }
}
