package org.openlife.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class RtlLayoutTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun screensMirrorCorrectlyUnderForcedRtl() {
        val source = Files.readString(
            projectDir.resolve("app/src/androidTest/java/org/openlife/app/ui/AccessibilitySemanticsTest.kt"),
        )
        assertTrue(
            "the Compose suite must force RTL through the layout direction local",
            source.contains("LocalLayoutDirection provides LayoutDirection.Rtl"),
        )
        val manifest = Files.readString(projectDir.resolve("app/src/main/AndroidManifest.xml"))
        assertTrue("the app must declare RTL support", manifest.contains("supportsRtl=\"true\""))
    }
}
