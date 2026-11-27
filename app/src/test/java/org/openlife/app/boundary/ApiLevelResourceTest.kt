package org.openlife.app.boundary

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * P1-21-R4: `windowLightNavigationBar` needs API 27. Lint (NewApi) rejects it
 * in the base and night theme, so it may only be declared in the -v27 folders.
 */
class ApiLevelResourceTest {
    private val res = Path.of(System.getProperty("openlife.projectDir", "."), "app/src/main/res")
    private val attribute = "windowLightNavigationBar"

    @Test
    fun lightNavigationBarIsOnlyDeclaredInApi27Folders() {
        val declaredIn = Files.list(res).use { dirs ->
            dirs.filter { it.fileName.toString().startsWith("values") }
                .filter { Files.isRegularFile(it.resolve("themes.xml")) }
                .filter { Files.readString(it.resolve("themes.xml")).contains(attribute) }
                .map { it.fileName.toString() }
                .toList()
                .sorted()
        }
        val outsideApi27 = declaredIn.filterNot { it.endsWith("-v27") }
        assertTrue("$attribute declared below API 27 in: $outsideApi27", outsideApi27.isEmpty())
        assertTrue("$attribute is missing from values-v27", "values-v27" in declaredIn)
        assertTrue("$attribute is missing from values-night-v27", "values-night-v27" in declaredIn)
    }
}
