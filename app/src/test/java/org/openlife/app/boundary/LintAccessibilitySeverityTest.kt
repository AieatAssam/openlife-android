package org.openlife.app.boundary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * P1-10-R3: Android Lint's Accessibility category fails the build (P0-03 had
 * left it at warning), and lint.xml does not quietly lower any of its checks.
 * AOSP's accessibility lint checks mostly target XML views; they see little of
 * Compose, so this complements, not replaces, AccessibilitySourceRulesTest.
 */
class LintAccessibilitySeverityTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun accessibilityLintIssuesAreErrors() {
        val build = Files.readString(projectDir.resolve("app/build.gradle.kts"))
        val lintBlock = build.substringAfter("    lint {").substringBefore("\n    }")
        assertTrue("lint must raise Accessibility to error", lintBlock.contains("""error.add("Accessibility")"""))
        assertFalse("Accessibility must not be a warning", lintBlock.contains("""warning.add("Accessibility")"""))
        assertFalse("Accessibility must not be disabled", lintBlock.contains("""disable.add("Accessibility")"""))

        val lintXml = Files.readString(projectDir.resolve("app/lint.xml"))
        val lowered = ACCESSIBILITY_ISSUES.filter { id ->
            Regex("""<issue\s+id="$id"\s+severity="(ignore|informational|warning)"""").containsMatchIn(lintXml)
        }
        assertTrue("accessibility lint issues lowered in lint.xml: $lowered", lowered.isEmpty())
    }

    private companion object {
        // AOSP lint's Accessibility category (lint-checks, AGP 9.x).
        val ACCESSIBILITY_ISSUES = listOf(
            "Accessibility",
            "ClickableViewAccessibility",
            "ContentDescription",
            "GetContentDescriptionOverride",
            "KeyboardInaccessibleWidget",
            "LabelFor",
            "SpeakableTextPresentCheck",
        )
    }
}
