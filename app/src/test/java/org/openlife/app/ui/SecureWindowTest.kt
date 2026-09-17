package org.openlife.app.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureWindowTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun everyActivityWindowHasFlagSecure() {
        val manifest = Files.readString(projectDir.resolve("app/src/main/AndroidManifest.xml"))
        assertTrue(
            "predictive back must be enabled for every activity window",
            manifest.contains("android:enableOnBackInvokedCallback=\"true\""),
        )
        val secureWindow = Files.readString(
            projectDir.resolve("app/src/main/java/org/openlife/app/ui/SecureWindow.kt"),
        )
        assertTrue(secureWindow.contains("FLAG_SECURE"))
        assertTrue(
            "both activities must apply the shared secure-window policy",
            listOf(
                "app/src/main/java/org/openlife/app/MainActivity.kt",
                "app/src/main/java/org/openlife/app/intake/IntakeActivity.kt",
            ).all { Files.readString(projectDir.resolve(it)).contains("applySecureWindow()") },
        )
    }
}
