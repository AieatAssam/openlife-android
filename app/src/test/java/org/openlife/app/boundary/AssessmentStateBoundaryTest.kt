package org.openlife.app.boundary

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * P2-07-R2: no production code in C1 produces an assessment result.
 * NOT_APPLICABLE is reserved for a future C10 rule and nothing in C1 sets
 * NO_INDICATORS_FOUND (or any other result), so outside the enum declaration
 * no shipped source names them, and no SQL writes `assessmentState`.
 */
class AssessmentStateBoundaryTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))
    private val declaration = "vault/model/AssessmentState.kt"
    private val results = Regex("""\b(NOT_APPLICABLE|NO_INDICATORS_FOUND|INCONCLUSIVE|CONCERNS_FOUND)\b""")
    private val sqlWrite = Regex("""SET\b[^"]*\bassessmentState\s*=""", RegexOption.IGNORE_CASE)

    @Test
    fun noShippedCodeProducesAnAssessmentResult() {
        val violations = mainSources().flatMap { file ->
            Files.readAllLines(file).withIndex()
                .filter { (_, line) -> results.containsMatchIn(line) || sqlWrite.containsMatchIn(line) }
                .map { (index, line) -> "$file:${index + 1}: ${line.trim()}" }
        }
        assertTrue("assessment results are not produced in C1: $violations", violations.isEmpty())
    }

    private fun mainSources(): List<Path> = listOf("app", "vault").flatMap { module ->
        Files.walk(projectDir.resolve("$module/src/main/java")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { !it.toString().endsWith(declaration) }
                .collect(Collectors.toList())
        }
    }
}
