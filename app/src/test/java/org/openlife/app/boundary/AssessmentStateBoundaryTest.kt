package org.openlife.app.boundary

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * P2-07-R2: no production code in C1 produces an assessment result.
 * NOT_APPLICABLE is reserved for a future C10 rule and nothing in C1 sets
 * NO_INDICATORS_FOUND (or any other result). Each file is scanned as one
 * text (review T-02), so a rule split over lines, for example a multi-line
 * `@Query`, is still found. Outside the enum declaration (and, for the
 * mapper rules, the storage mapper that decodes stored rows) no shipped
 * source may name a result, assign `assessmentState` or
 * `assessmentMethodVersion`, write them in SQL, or build an
 * AssessmentState from an index or a name. Every rule carries a mutation
 * sample that it must match, so the scan is proven able to fail.
 */
class AssessmentStateBoundaryTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    private class Rule(
        val name: String,
        val pattern: Regex,
        val exemptFiles: List<String>,
        val mutation: String,
    )

    private val declaration = "vault/model/AssessmentState.kt"
    private val mapper = "vault/storage/SourceMapper.kt"
    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private val rules = listOf(
        Rule(
            "names an assessment result",
            Regex("""\b(NOT_APPLICABLE|NO_INDICATORS_FOUND|INCONCLUSIVE|CONCERNS_FOUND)\b"""),
            listOf(declaration),
            "val result = AssessmentState.NO_INDICATORS_FOUND",
        ),
        Rule(
            "SQL UPDATE writes an assessment column",
            Regex("""\bUPDATE\s+`?sources`?\s+SET\b.{0,300}?\bassessment(State|MethodVersion)`?\s*=""", options),
            emptyList(),
            "@Query(\n    \"\"\"\n    UPDATE sources SET userContentKind = :kind,\n" +
                "        assessmentState = :state\n    \"\"\",\n)",
        ),
        Rule(
            "SQL INSERT names an assessment column",
            Regex("""\bINSERT\b[^;]{0,300}?\([^)]*\bassessment(State|MethodVersion)\b""", options),
            emptyList(),
            "\"INSERT INTO sources(id, state,\n    assessmentState) VALUES (?, ?, ?)\"",
        ),
        Rule(
            "assigns an assessment field (named argument, copy or property)",
            Regex("""\bassessment(State|MethodVersion)\s*=(?!=)"""),
            listOf(mapper),
            "source.copy(\n    assessmentState = state,\n)",
        ),
        Rule(
            "builds an AssessmentState from an index or a name",
            Regex(
                """AssessmentState\s*\.\s*(entries|values\s*\(|valueOf\s*\()|""" +
                    """enum(Values|ValueOf)\s*<\s*AssessmentState\s*>""",
            ),
            listOf(declaration, mapper),
            "AssessmentState.entries[\n    index\n]",
        ),
    )

    @Test
    fun everyRuleMatchesItsMutationSample() {
        rules.forEach { rule ->
            assertTrue(
                "rule '${rule.name}' cannot fail: no match in ${rule.mutation}",
                rule.pattern.containsMatchIn(rule.mutation),
            )
        }
    }

    @Test
    fun noShippedCodeProducesAnAssessmentResult() {
        val violations = mainSources().flatMap { file ->
            val text = Files.readString(file)
            rules.filter { rule -> rule.exemptFiles.none { file.toString().endsWith(it) } }
                .flatMap { rule ->
                    rule.pattern.findAll(text).map { match ->
                        val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                        "$file:$line (${rule.name}): ${match.value.lines().first().trim()}"
                    }.toList()
                }
        }
        assertTrue("assessment results are not produced in C1: $violations", violations.isEmpty())
    }

    private fun mainSources(): List<Path> = listOf("app", "vault").flatMap { module ->
        Files.walk(projectDir.resolve("$module/src/main/java")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .collect(Collectors.toList())
        }
    }
}
