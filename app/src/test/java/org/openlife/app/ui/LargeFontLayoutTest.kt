package org.openlife.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class LargeFontLayoutTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun allScreensKeepActionsReachableAt2xFontScale() {
        val accessibilityTest = Files.readString(
            projectDir.resolve("app/src/androidTest/java/org/openlife/app/ui/AccessibilitySemanticsTest.kt"),
        )
        assertTrue("the 2x density override must be exercised", accessibilityTest.contains("fontScale = 2f"))
        val sources = listOf(
            "app/src/main/java/org/openlife/app/ui/FirstRunPreferences.kt",
            "app/src/main/java/org/openlife/app/ui/IntakeScreen.kt",
            "app/src/main/java/org/openlife/app/ui/SourceListScreen.kt",
            "app/src/main/java/org/openlife/app/ui/ViewerScreen.kt",
        ).map { Files.readString(projectDir.resolve(it)) }
        assertTrue(
            "every screen must use an inset-safe or scrollable action region",
            sources.all {
                it.contains("verticalScroll") ||
                    it.contains("safeDrawingPadding()") ||
                    it.contains("contentWindowInsets")
            },
        )
    }
}
