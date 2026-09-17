package org.openlife.app.ui

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
