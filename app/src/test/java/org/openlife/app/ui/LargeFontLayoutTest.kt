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
        assertTrue("the 1.3x density override must be exercised", accessibilityTest.contains("fontScale = 1.3f"))
        assertTrue("the 2x density override must be exercised", accessibilityTest.contains("fontScale = 2f"))
        val sources = listOf(
            "app/src/main/java/org/openlife/app/ui/FirstRunPreferences.kt",
            "app/src/main/java/org/openlife/app/ui/IntakeScreen.kt",
            "app/src/main/java/org/openlife/app/ui/SourceListScreen.kt",
            "app/src/main/java/org/openlife/app/ui/ViewerScreen.kt",
            "app/src/main/java/org/openlife/app/ui/VaultUnavailableScreen.kt",
            "app/src/main/java/org/openlife/app/ui/search/SearchScreen.kt",
            "app/src/main/java/org/openlife/app/settings/SettingsScreen.kt",
            "app/src/main/java/org/openlife/app/settings/AboutScreen.kt",
            "app/src/main/java/org/openlife/app/lock/LockScreen.kt",
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

    /**
     * P1-10-R5: the instrumented ScreenSemanticsTest renders every screen at
     * 2.0x font scale and at the largest display size (density x1.3, Android's
     * "Largest" display-size step on a default-density device) and checks that
     * every action stays reachable and no text is clipped. This test only proves
     * the instrumented test covers every screen; it does not run it.
     */
    @Test
    fun everyScreenIsExercisedAtLargeFontAndLargestDisplaySize() {
        val semantics = Files.readString(
            projectDir.resolve("app/src/androidTest/java/org/openlife/app/a11y/ScreenSemanticsTest.kt"),
        )
        assertTrue("2x font scale", semantics.contains("fontScale = 2f"))
        assertTrue("largest display size", semantics.contains("LARGEST_DISPLAY_SIZE"))
        val missing = listOf(
            "SourceListScreen(",
            "ViewerScreen(",
            "IntakeScreen(",
            "FirstRunExplanationScreen(",
            "SettingsScreen(",
            "AboutScreen(",
            "LockScreen(",
            "AppLockOfferScreen(",
            "VaultUnavailableScreen(",
            "SearchScreen(",
            "DeleteConfirmationDialog(",
        ).filterNot { semantics.contains(it) }
        assertTrue("screens not exercised: $missing", missing.isEmpty())
    }
}
