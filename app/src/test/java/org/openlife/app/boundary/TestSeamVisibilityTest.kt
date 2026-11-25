package org.openlife.app.boundary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * A-17 (P1-19-R20, review Q-07): test seams are not shipping API. Every
 * `...ForTest` function, `val` or `var` in app/src/main is either private (a
 * seam's own backing field) or internal and @VisibleForTesting, and the
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
                        .filterNot { (index, line) -> isAllowedSeam(lines, index, line) }
                        .map { (index, line) -> "$file:${index + 1}: ${line.trim()}" }
                        .stream()
                }
                .collect(Collectors.toList())
        }
        assertTrue("test seams that are not internal + @VisibleForTesting: $violations", violations.isEmpty())
        val intake = Files.readString(projectDir.resolve("app/src/main/java/org/openlife/app/intake/IntakeActivity.kt"))
        assertTrue("describeForTest belongs to androidTest", "describeForTest" !in intake)
    }

    @Test
    fun theCheckSeesFunctionsValsAndVars() {
        val unmarked = listOf(
            "    fun resetForTest() {",
            "    var delayForTest = 0L",
            "    internal val clockForTest = 0L",
        )
        unmarked.forEach { line ->
            assertTrue("\"$line\" is a seam", SEAM.containsMatchIn(line))
            assertFalse("\"$line\" is not marked", isAllowedSeam(listOf(line), 0, line))
        }
        val marked = listOf("    @VisibleForTesting", "    @Volatile", "    internal var delayForTest = 0L")
        assertTrue(isAllowedSeam(marked, 2, marked[2]))
        val privateField = "    private var overrideForTest = 0L"
        assertTrue(isAllowedSeam(listOf(privateField), 0, privateField))
        val privateFunction = "    private fun resetForTest() {"
        assertFalse(isAllowedSeam(listOf(privateFunction), 0, privateFunction))
        assertFalse("a call is not a declaration", SEAM.containsMatchIn("        app.resetForTest()"))
    }

    private fun isAllowedSeam(lines: List<String>, index: Int, line: String): Boolean {
        val trimmed = line.trimStart()
        val marked = trimmed.startsWith("internal ") &&
            lines.subList(maxOf(0, index - 3), index).any { "@VisibleForTesting" in it }
        return PRIVATE_FIELD.containsMatchIn(trimmed) || marked
    }

    private companion object {
        val SEAM = Regex("""\b(fun|val|var)\s+\w*ForTest\b""")

        /** A private backing field of a seam is not API; a private function named ...ForTest is still reported. */
        val PRIVATE_FIELD = Regex("""^private\s+(@Volatile\s+)?(val|var)\s""")
    }
}
