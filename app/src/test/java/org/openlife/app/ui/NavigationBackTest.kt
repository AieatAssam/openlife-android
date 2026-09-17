package org.openlife.app.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationBackTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))
    private val mainActivity = projectDir.resolve("app/src/main/java/org/openlife/app/MainActivity.kt")

    @Test
    fun backFromViewerReturnsToListInsteadOfFinishing() {
        val source = Files.readString(mainActivity)
        assertTrue("MainActivity must host Navigation Compose", source.contains("NavHost"))
        assertTrue("Viewer must be a typed navigation destination", source.contains("composable<Routes.Viewer>"))
        assertTrue("Viewer back must pop the navigation stack", source.contains("popBackStack"))
    }

    @Test
    fun backFromListFinishesActivity() {
        val source = Files.readString(mainActivity)
        assertTrue("the list must be the navigation start destination", source.contains("startDestination = Routes.List"))
        assertFalse("the hand-rolled Screen stack must be removed", source.contains("sealed interface Screen"))
    }
}
