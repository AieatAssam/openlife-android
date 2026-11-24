package org.openlife.app.boundary

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * A-17 (P1-19-R20): test seams are not shipping API. Every `...ForTest`
 * function in app/src/main is internal and @VisibleForTesting, and the
 * English status describer used by instrumented tests lives in androidTest.
 */
class TestSeamVisibilityTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun appTestSeamsAreInternalAndMarked() {
        val violations = Files.walk(projectDir.resolve("app/src/main/java")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .flatMap { file ->
                    val lines = Files.readAllLines(file)
                    lines.withIndex().filter { (_, line) -> SEAM.containsMatchIn(line) }
                        .filterNot { (index, line) ->
                            line.trimStart().startsWith("internal ") &&
                                lines.subList(maxOf(0, index - 3), index).any { "@VisibleForTesting" in it }
                        }
                        .map { (index, line) -> "$file:${index + 1}: ${line.trim()}" }
                        .stream()
                }
                .collect(Collectors.toList())
        }
        assertTrue("test seams that are not internal + @VisibleForTesting: $violations", violations.isEmpty())
        val intake = Files.readString(projectDir.resolve("app/src/main/java/org/openlife/app/intake/IntakeActivity.kt"))
        assertTrue("describeForTest belongs to androidTest", "describeForTest" !in intake)
    }

    private companion object {
        val SEAM = Regex("""\bfun\s+\w*ForTest\s*\(""")
    }
}
