package org.openlife.app.a11y

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * P1-10-R1, with a recorded deviation: the step asks for Compose's
 * ATF-backed `enableAccessibilityChecks()`. That lives in a separate artefact
 * (`androidx.compose.ui:ui-test-accessibility`, which brings Google's
 * Accessibility Test Framework) that is not a dependency of this project, and
 * adding a dependency needs an owner decision (docs/verification/P1-10-accessibility.md).
 * Until then this shared rule runs OpenLife's own checks over the Compose
 * semantics tree, the same tree TalkBack reads, after every test that uses
 * it (and on demand through [check]). Failures are errors.
 *
 * Checks (ATF's nearest equivalent in brackets):
 * - an actionable node has a spoken label (SpeakableTextPresentCheck);
 * - an actionable node is at least 48 x 48 dp (TouchTargetSizeCheck);
 * - an image node has a description (ImageContentDescriptionCheck);
 * - an editable field has a label besides its text (EditableContentDescCheck);
 * - a toggle or selectable node states its role (ClassNameCheck);
 * - two actionable nodes do not share the same bounds (DuplicateClickableBoundsCheck).
 * Not covered here, unlike ATF: pixel colour contrast (ThemeContrastTest on the
 * JVM covers the palette), traversal-order cycles, and link checks.
 *
 * [suppressions] is the documented suppression list; its target is empty.
 */
class AccessibilityRule(
    private val composeRule: ComposeContentTestRule,
    val suppressions: List<Suppression> = DEFAULT_SUPPRESSIONS,
) : TestRule {
    private var checked = false

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            base.evaluate()
            // A test that never asked for a check is still checked on its final screen.
            if (!checked) check(description.methodName.orEmpty())
        }
    }

    /** Fails with every issue on the current screen. */
    fun check(context: String = "") {
        checked = true
        val issues = findIssues()
        if (issues.isNotEmpty()) throw AssertionError("accessibility issues ($context):\n" + issues.joinToString("\n"))
    }

    /** Every issue on the current screen, unsuppressed, as readable lines. */
    fun findIssues(): List<String> {
        composeRule.waitForIdle()
        val nodes = composeRule.onAllNodes(ANY).fetchSemanticsNodes(atLeastOneRootRequired = true)
        val minimum = with(composeRule.density) { MIN_TARGET.toPx() } - PIXEL_TOLERANCE
        val issues = mutableListOf<Issue>()
        nodes.forEach { node ->
            val config = node.config
            val actionable = config.contains(SemanticsActions.OnClick) || config.contains(SemanticsActions.OnLongClick)
            if (actionable && label(node).isBlank()) issues += Issue("label", node, "actionable node has no label")
            if (actionable && config.getOrNull(SemanticsProperties.InvisibleToUser) == null) {
                val width = maxOf(node.size.width.toFloat(), node.touchBoundsInRoot.width)
                val height = maxOf(node.size.height.toFloat(), node.touchBoundsInRoot.height)
                if (width < minimum || height < minimum) {
                    issues += Issue("touch-target", node, "touch target ${width.toInt()}x${height.toInt()} px")
                }
            }
            if (config.getOrNull(SemanticsProperties.Role) == Role.Image &&
                config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().all { it.isBlank() }
            ) {
                issues += Issue("image", node, "image without a description")
            }
            // A field's Text includes its label (Material's TextField merges it in).
            if (config.contains(SemanticsActions.SetText) && label(node).isBlank()) {
                issues += Issue("field-label", node, "editable field without a label")
            }
            val toggles = config.contains(SemanticsProperties.ToggleableState) || config.contains(SemanticsProperties.Selected)
            if (toggles && actionable && config.getOrNull(SemanticsProperties.Role) == null) {
                issues += Issue("role", node, "toggle or selectable without a role")
            }
        }
        nodes.filter { it.config.contains(SemanticsActions.OnClick) }
            .groupBy { it.boundsInRoot }
            .filter { (bounds, group) -> group.size > 1 && bounds.width > 0f }
            .values.forEach { group -> issues += Issue("duplicate-bounds", group.first(), "${group.size} actions share bounds") }
        return issues.filterNot { issue -> suppressions.any { it.check == issue.check && it.matches(issue.node) } }
            .map { it.toString() }
    }

    /** Text nodes whose laid-out text overflows its bounds (clipped or cut by maxLines). */
    fun findClippedText(): List<String> {
        composeRule.waitForIdle()
        return composeRule.onAllNodes(ANY, useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { node ->
            val layout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: return@mapNotNull null
            val results = mutableListOf<TextLayoutResult>()
            layout.action?.invoke(results)
            val clipped = results.any { it.hasVisualOverflow }
            val issue = Issue("clipped-text", node, "text overflows its bounds")
            if (clipped && suppressions.none { it.check == issue.check && it.matches(node) }) issue.toString() else null
        }
    }

    /** One way a node can fail a check; [reason] says why the exception is acceptable. */
    class Suppression(val check: String, val reason: String, val matches: (SemanticsNode) -> Boolean)

    private class Issue(val check: String, val node: SemanticsNode, val message: String) {
        override fun toString(): String = "[$check] ${label(node).ifBlank { "<no label>" }} (${node.boundsInRoot}): $message"
    }

    companion object {
        private val MIN_TARGET = 48.dp
        private const val PIXEL_TOLERANCE = 0.5f
        private val ANY = SemanticsMatcher("any node") { true }

        /** Has an ancestor (or is the node) tagged [tag]. */
        fun underTag(tag: String): (SemanticsNode) -> Boolean = { node ->
            generateSequence(node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        }

        /**
         * The documented suppression list. Target: empty. Each entry is a
         * known, reviewed exception with an accessible equivalent.
         */
        val DEFAULT_SUPPRESSIONS = listOf(
            Suppression(
                check = "touch-target",
                reason = "Evidence regions are as large as the text they outline. Each one has a 48 dp " +
                    "'Show region' button in the extracted-text panel, which does the same thing.",
                matches = underTag("viewer_evidence_canvas"),
            ),
            Suppression(
                check = "clipped-text",
                reason = "Search snippets are capped at four lines with an ellipsis on purpose; the result opens the " +
                    "whole text in the viewer.",
                matches = underTag("search_result"),
            ),
        )

        fun label(node: SemanticsNode): String {
            val config = node.config
            return (
                config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
                ).joinToString(" ").trim()
        }
    }
}
