package org.openlife.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class EdgeToEdgeInsetsTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun primaryActionsAreNotObscuredByNavigationBarInsets() {
        val activitySources = listOf(
            "app/src/main/java/org/openlife/app/MainActivity.kt",
            "app/src/main/java/org/openlife/app/intake/IntakeActivity.kt",
        ).map { Files.readString(projectDir.resolve(it)) }
        assertTrue(
            "every activity must opt into edge-to-edge",
            activitySources.all { it.contains("enableEdgeToEdge()") },
        )

        val screenSources = Files.walk(projectDir.resolve("app/src/main/java/org/openlife/app/ui")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .map(Files::readString)
                .toList()
        }
        assertTrue(
            "non-Scaffold screens must apply safe drawing insets",
            screenSources.any { it.contains("safeDrawingPadding()") },
        )
        assertTrue(
            "Scaffold screens must retain their inset-aware content window",
            screenSources.any { it.contains("contentWindowInsets") },
        )
    }
}
