package org.openlife.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class NavigationBackTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))
    private val mainActivity = projectDir.resolve("app/src/main/java/org/openlife/app/MainActivity.kt")

    @Test
    fun backFromViewerReturnsToListInsteadOfFinishing() {
        val source = Files.readString(
            projectDir.resolve("app/src/main/java/org/openlife/app/navigation/OpenLifeNavHost.kt"),
        )
        assertTrue(
            "the navigation host must use Navigation Compose",
            source.contains("NavHost"),
        )
        assertTrue(
            "Viewer must be a typed navigation destination",
            source.contains("composable<Routes.Viewer>"),
        )
        assertTrue(
            "Viewer back must pop the navigation stack",
            source.contains("popBackStack"),
        )
        assertTrue(
            "deep links must wait for a loaded source list",
            source.contains("listState !is SourceListUiState.Loaded"),
        )
    }

    @Test
    fun backFromListFinishesActivity() {
        val activitySource = Files.readString(mainActivity)
        val hostSource = Files.readString(
            projectDir.resolve("app/src/main/java/org/openlife/app/navigation/OpenLifeNavHost.kt"),
        )
        assertTrue(
            "the list must be the navigation start destination",
            hostSource.contains("startDestination = Routes.List"),
        )
        // P1-07: deep links are consumed inside UnlockedContent, the composable
        // that also composes the NavHost, so the graph exists and the app is unlocked.
        assertTrue(
            "cold-start deep links must wait until the navigation graph exists",
            activitySource.contains("LaunchedEffect(requestedSourceId)") &&
                activitySource.indexOf("private fun UnlockedContent(") <
                activitySource.indexOf("LaunchedEffect(requestedSourceId)"),
        )
        assertFalse("the hand-rolled Screen stack must be removed", activitySource.contains("sealed interface Screen"))
    }
}
