package org.openlife.app.version

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionCodeTest {

    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun versionCodeDerivesFromVersionFile() {
        val versionName = Files.readString(projectDir.resolve("VERSION")).trim()

        assertEquals(100, versionCode("0.1.0"))
        assertEquals(10_000, versionCode("1.0.0"))
        assertEquals(10_203, versionCode("1.2.3"))
        assertEquals(versionCode(versionName), org.openlife.app.BuildConfig.VERSION_CODE)
    }

    private fun versionCode(versionName: String): Int {
        val match = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(versionName)
            ?: error("invalid semantic version: $versionName")
        val (major, minor, patch) = match.destructured
        return major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
    }
}
