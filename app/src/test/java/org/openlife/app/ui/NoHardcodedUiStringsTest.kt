package org.openlife.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import java.util.stream.Collectors

class NoHardcodedUiStringsTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun allUserVisibleTextComesFromResources() {
        val literalPatterns = listOf(
            Pattern.compile("\\bText\\s*\\(\\s*\\\""),
            Pattern.compile("contentDescription\\s*=\\s*\\\""),
            Pattern.compile("label\\s*=\\s*\\{\\s*Text\\s*\\(\\s*\\\""),
        )
        val violations = Files.walk(projectDir.resolve("app/src/main/java")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .flatMap { file ->
                    Files.readAllLines(file).stream()
                        .filter { line -> literalPatterns.any { it.matcher(line).find() } }
                        .map { line -> "$file: $line" }
                }
                .collect(Collectors.toList())
        }
        assertTrue("hardcoded UI strings: $violations", violations.isEmpty())
    }

    /**
     * A-17 (P1-19-R20): a string assembled in code from placeholders and
     * punctuation (for example "\$cause: \$count" or a ", " join) is
     * English-only and reaches the screen without a resource. Debug-only
     * geometry logging is the one exemption.
     */
    @Test
    fun stringTemplatesInTextCallsAreFlagged() {
        val violations = Files.walk(projectDir.resolve("app/src/main/java")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { file -> TEMPLATE_EXEMPT_FILES.none { file.endsWith(it) } }
                .flatMap { file ->
                    Files.readAllLines(file).withIndex().toList().stream()
                        .filter { (_, line) -> templateViolation(line) }
                        .map { (index, line) -> "$file:${index + 1}: ${line.trim()}" }
                }
                .collect(Collectors.toList())
        }
        assertTrue("hand-built UI strings: $violations", violations.isEmpty())

        listOf(
            """Text("${'$'}cause: ${'$'}count")""",
            """return counts.joinToString(", ") { (cause, count) -> "${'$'}cause: ${'$'}count" }""",
            """).joinToString(", "),""",
            """Text(label + ": " + value)""",
        ).forEach { mutation -> assertTrue("the check catches: $mutation", templateViolation(mutation)) }
    }

    private fun templateViolation(line: String): Boolean =
        TEMPLATE_PATTERNS.any { it.containsMatchIn(line) }

    private companion object {
        val TEMPLATE_EXEMPT_FILES = listOf(
            // BuildConfig.DEBUG-only EvidenceBounds log (P2-06-R15); never shown.
            "app/src/main/java/org/openlife/app/ui/evidence/ZoomableEvidenceImage.kt",
        ).map { Path.of(it) }
        private const val PLACEHOLDER = """(\$\{[^}]*\}|\$[A-Za-z_]\w*)"""
        val TEMPLATE_PATTERNS = listOf(
            // Two placeholders joined by literal punctuation: "${'$'}a: ${'$'}b", "${'$'}a, ${'$'}b".
            Regex(""""[^"]*$PLACEHOLDER[^"\w${'$'}]+$PLACEHOLDER[^"]*""""),
            // A hand-made English list separator.
            Regex("""joinToString\(\s*(separator\s*=\s*)?", """"),
            // Concatenation with a literal inside Text(...).
            Regex("""\bText\s*\([^)]*\+\s*"[^"]*""""),
        )
    }
}
