package org.openlife.app.boundary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * P1-10: accessibility rules that can be checked in shipped source. Each rule
 * reads whole calls through [KotlinSource] and carries a self-check that a
 * known violation is reported. These are tripwires over source text, not
 * proofs: they do not see layout, the merged semantics tree, what TalkBack
 * says, or code that reaches a composable through an unusual path. The
 * instrumented ScreenSemanticsTest and docs/runbooks/talkback.md cover what
 * only a device can show.
 */
class AccessibilitySourceRulesTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))
    private val sources by lazy { KotlinSource.app(projectDir) }

    /** Every Icon/Image names itself from resources, or says why it is decorative (null). */
    @Test
    fun iconsAndImagesHaveADescriptionOrADecorativeReason() {
        val violations = sources.flatMap { source ->
            (source.calls("Icon") + source.calls("Image")).mapNotNull { call -> descriptionViolation(call) }
        } + sources.flatMap { source ->
            // An IconButton has no other label: its icon must not be null.
            source.calls("IconButton").filter { NULL_DESCRIPTION.containsMatchIn(it.body.orEmpty()) }
                .map { "${it.location()}: IconButton with a null icon description" }
        }
        assertTrue("icons/images without a description or decorative reason: $violations", violations.isEmpty())
        val unjustified = KotlinSource(
            Path.of("Self.kt"),
            "fun a() {\n    Icon(Icons.Filled.Add, contentDescription = null)\n}",
        )
        assertTrue("self-check", descriptionViolation(unjustified.calls("Icon").single()) != null)
        val positional = KotlinSource(Path.of("Self.kt"), "fun a() {\n    Icon(Icons.Filled.Add, label)\n}")
        assertTrue("self-check: positional", descriptionViolation(positional.calls("Icon").single()) != null)
        val justified = KotlinSource(
            Path.of("Self.kt"),
            "fun a() {\n    // Decorative: the button's text names the action.\n" +
                "    Icon(Icons.Filled.Add, contentDescription = null)\n}",
        )
        assertTrue("self-check: justified", descriptionViolation(justified.calls("Icon").single()) == null)
    }

    /** Clickable, selectable and toggleable modifiers state a role, name the action and keep a 48 dp target. */
    @Test
    fun clickableElementsHaveARoleALabelAndA48DpTarget() {
        val violations = sources.flatMap { source ->
            CLICK_MODIFIERS.flatMap { modifier -> source.calls(modifier).mapNotNull { clickViolation(source, it) } }
        }
        assertTrue("clickable modifiers missing role, label or 48 dp target: $violations", violations.isEmpty())
        val bare = KotlinSource(
            Path.of("Self.kt"),
            "fun a() {\n    Column(Modifier.clickable { open() }) { Text(x) }\n}",
        )
        assertTrue("self-check", clickViolation(bare, bare.calls(".clickable").single()) != null)
        val good = KotlinSource(
            Path.of("Self.kt"),
            "fun a() {\n    Row(Modifier.heightIn(min = 48.dp)" +
                ".clickable(onClickLabel = l, role = Role.Button) { o() }) {}\n}",
        )
        assertTrue("self-check: good", clickViolation(good, good.calls(".clickable").single()) == null)
    }

    /** Headings: every display/headline/title-styled Text and every bar or dialog title is a heading. */
    @Test
    fun titlesAreMarkedAsHeadings() {
        val violations = sources.flatMap { source ->
            source.calls("Text").filter { HEADING_STYLE.containsMatchIn(it.args) }.filterNot(::isHeadingOrExempt)
                .map { "${it.location()}: ${it.args.lines().first().trim()}" }
        } + sources.flatMap { source ->
            TITLE_SLOT.findAll(source.code).mapNotNull { match ->
                // The title's text is a Text or, where it autosizes, a BasicText.
                (source.calls("Text") + source.calls("BasicText"))
                    .filter { it.start > match.range.last }.minByOrNull { it.start }
                    ?.takeUnless { HEADING.containsMatchIn(it.args) }
                    ?.let { "${it.location()}: title slot without heading()" }
            }.toList()
        }
        assertTrue("titles not marked as headings: $violations", violations.isEmpty())
        val plain = KotlinSource(
            Path.of("Self.kt"),
            "fun a() {\n    Text(x, style = MaterialTheme.typography.titleLarge)\n}",
        )
        assertFalse("self-check", isHeadingOrExempt(plain.calls("Text").single()))
    }

    /** Text sizes scale with the user's font size: no dp text, no pinned font scale. */
    @Test
    fun textSizesFollowTheUserFontScale() {
        val violations = sources.flatMap { source ->
            FIXED_TEXT_SIZE.flatMap { pattern ->
                pattern.findAll(
                    source.code,
                ).map { "${source.path.fileName}:${source.lineOf(it.range.first)}: ${it.value}" }
            }
        }
        assertTrue("text sizes that ignore font scale: $violations", violations.isEmpty())
        listOf("fontSize = 14.dp", "with(density) { 14.dp.toSp() }", "fontScale = 1f", "TextUnit(14f, TextUnitType.Sp)")
            .forEach { mutation ->
                assertTrue(
                    "self-check: $mutation",
                    FIXED_TEXT_SIZE.any { it.containsMatchIn(mutation) },
                )
            }
    }

    /** No fixed height on a container that holds text: at 200 % font it would clip. */
    @Test
    fun textContainersHaveNoFixedHeight() {
        val violations = sources.flatMap { source -> fixedHeightTextViolations(source) }
        assertTrue("fixed-height containers holding Text: $violations", violations.isEmpty())
        val clipped = KotlinSource(
            Path.of("Self.kt"),
            "fun a() {\n    Box(Modifier.fillMaxWidth().height(320.dp)) { Text(x) }\n}",
        )
        assertTrue("self-check", fixedHeightTextViolations(clipped).isNotEmpty())
        val icon = KotlinSource(
            Path.of("Self.kt"),
            "fun a() {\n    Box(Modifier.size(56.dp)) { Icon(i, contentDescription = d) }\n}",
        )
        assertTrue("self-check: icon box", fixedHeightTextViolations(icon).isEmpty())
    }

    /** Custom gestures (pinch, pan, double tap) have accessibility actions for the same result. */
    @Test
    fun customGesturesHaveAccessibilityActions() {
        val violations = sources.filter { source -> GESTURES.any { it.containsMatchIn(source.code) } }
            .filterNot { source -> GESTURE_EQUIVALENTS.all { it.containsMatchIn(source.code) } }
            .map { it.path.fileName.toString() }
        assertTrue("gesture handlers without customActions/onClick semantics: $violations", violations.isEmpty())
    }

    /** State is not shown by colour alone: highlights carry a second cue and selection is exposed. */
    @Test
    fun stateIsNotConveyedByColourAlone() {
        val spanStyles = sources.flatMap { source ->
            source.calls("SpanStyle").filter { colourOnly(it.args) }.map { "${it.location()}: ${it.args.trim()}" }
        }
        val hiddenSelection = sources.filter { SELECTED_PARAMETER.containsMatchIn(it.code) }
            .filterNot { source -> SELECTION_EXPOSED.any { it.containsMatchIn(source.code) } }
            .map { "${it.path.fileName}: a selected flag that never reaches semantics" }
        val violations = spanStyles + hiddenSelection
        assertTrue("state conveyed by colour alone: $violations", violations.isEmpty())
        assertTrue("self-check", colourOnly("background = highlighter, color = Ink"))
        assertFalse(
            "self-check: underlined",
            colourOnly("background = h, color = Ink, textDecoration = TextDecoration.Underline"),
        )
    }

    /** Status changes and errors are announced: a polite live region on each listed status text. */
    @Test
    fun statusChangesAndErrorsAreAnnounced() {
        val errors = sources.flatMap { source ->
            source.calls("Text").filter { it.args.contains("colorScheme.error") }
                .filterNot { announcedAt(source, it.start) }
                .map { "${it.location()}: error text not posted to the status host" }
        }
        val statuses = sources.flatMap { source ->
            STATUS_RESOURCES.flatMap { resource ->
                Regex("""R\.(string|plurals)\.$resource\b""").findAll(source.code)
                    .mapNotNull { statusViolation(source, it.range.first, resource) }.toList()
            }
        }
        val found = STATUS_RESOURCES.filter { resource ->
            sources.any { Regex("""R\.(string|plurals)\.$resource\b""").containsMatchIn(it.code) }
        }
        assertTrue(
            "status resources no longer used (update the list): ${STATUS_RESOURCES - found.toSet()}",
            found == STATUS_RESOURCES,
        )
        val violations = errors + statuses
        assertTrue("status changes not announced: $violations", violations.isEmpty())
        val silent = KotlinSource(
            Path.of("Self.kt"),
            "fun a() {\n    Text(stringResource(R.string.viewer_extracting))\n}",
        )
        assertTrue("self-check", statusViolation(silent, silent.code.indexOf("R.string"), "viewer_extracting") != null)
    }

    /** Each screen names itself as a pane, from resources, so TalkBack announces navigation. */
    @Test
    fun everyScreenDeclaresAPaneTitleFromResources() {
        val violations = SCREENS.mapNotNull { (file, function) ->
            val source = sources.single { it.path.endsWith(file) }
            val body = source.functionBody(function) ?: return@mapNotNull "$file: $function not found"
            val value = PANE_TITLE.find(
                body,
            )?.groupValues?.get(1) ?: return@mapNotNull "$file: $function has no paneTitle"
            val fromResources = value.startsWith("stringResource(R.string.") ||
                Regex("""val\s+$value\s*=\s*stringResource\(R\.string\.""").containsMatchIn(body)
            if (fromResources) null else "$file: $function paneTitle is not a string resource ($value)"
        }
        assertTrue("screens without a resource paneTitle: $violations", violations.isEmpty())
    }

    /** Accessibility-only text (labels, pane titles, states, actions) comes from resources. */
    @Test
    fun accessibilityTextComesFromResources() {
        val violations = sources.flatMap { source ->
            source.text.lines().withIndex().filter { (_, line) -> HARDCODED_A11Y_TEXT.any { it.containsMatchIn(line) } }
                .map { (index, line) -> "${source.path.fileName}:${index + 1}: ${line.trim()}" }
        }
        assertTrue("hard-coded accessibility text: $violations", violations.isEmpty())
        listOf(
            """Modifier.clickable(onClickLabel = "Open") {}""",
            """semantics { paneTitle = "Settings" }""",
            """semantics { stateDescription = "On" }""",
            """CustomAccessibilityAction("Zoom in") { true }""",
            """onClick(label = "Show") { true }""",
        ).forEach { mutation ->
            assertTrue(
                "self-check: $mutation",
                HARDCODED_A11Y_TEXT.any { it.containsMatchIn(mutation) },
            )
        }
    }

    private fun descriptionViolation(call: SourceCall): String? {
        val description = DESCRIPTION.find(
            call.args,
        ) ?: return "${call.location()}: ${call.name} without a named contentDescription"
        val value = description.groupValues[1]
        val justified = DECORATIVE.containsMatchIn(call.args) || DECORATIVE.containsMatchIn(call.preceding(2))
        return when {
            value.startsWith("\"") -> "${call.location()}: literal contentDescription"
            value == "null" && !justified -> "${call.location()}: null contentDescription without a decorative reason"
            else -> null
        }
    }

    private fun clickViolation(source: KotlinSource, call: SourceCall): String? {
        val missing = buildList {
            if (!call.args.contains("role =")) add("role")
            if (call.name == "clickable" && !call.args.contains("onClickLabel =")) add("onClickLabel")
            // Climb out of `.then(...)`, which only wraps a conditional modifier.
            var owner = source.enclosingCall(call.start)
            while (owner?.name == "then") owner = source.enclosingCall(owner.start)
            if (owner == null || MIN_TARGET.none { it.containsMatchIn(owner.args) }) add("48 dp minimum")
        }
        return if (missing.isEmpty()) null else "${call.location()}: .${call.name} missing $missing"
    }

    private fun isHeadingOrExempt(call: SourceCall): Boolean =
        HEADING.containsMatchIn(call.args) || HEADING_EXEMPT.any { (file, snippet) ->
            call.source.path.endsWith(file) && call.args.contains(snippet)
        }

    private fun fixedHeightTextViolations(source: KotlinSource): List<String> = FIXED_SIZE.findAll(source.code)
        .mapNotNull { match ->
            val owner = source.enclosingCall(match.range.first) ?: return@mapNotNull null
            val body = KotlinSource(source.path, owner.body.orEmpty()).code
            val holdsText = owner.name == "Text" || TEXT_CALL.containsMatchIn(body)
            val line = source.lineOf(match.range.first)
            if (holdsText) "${source.path.fileName}:$line: ${owner.name}${match.value}" else null
        }.toList()

    private fun colourOnly(args: String): Boolean =
        (args.contains("background =") || args.contains("color =")) && SECOND_CUE.none { args.contains(it) }

    private fun announces(text: String) = ANNOUNCE.containsMatchIn(text)

    /** True when the composable that holds the call at [offset] posts through [Announce]. */
    private fun announcedAt(source: KotlinSource, offset: Int): Boolean {
        // A resource passed to a composable of this file (a helper such as CenteredMessage)
        // is announced by that helper.
        var call = source.enclosingCall(offset)
        while (call != null && call.name in STRING_HELPERS) call = source.enclosingCall(call.start)
        val callee = call?.let { source.functionBody(it.name) }
        if (callee != null) return announces(callee)
        val declaration = FUN_DECLARATION.findAll(source.code.substring(0, offset)).lastOrNull() ?: return false
        return announces(source.functionBody(declaration.groupValues[1]).orEmpty())
    }

    /** P1-10 (review H-01): a live region exists only in the status host, so it is never inserted with its text. */
    @Test
    fun statusIsPostedOnlyThroughTheStableStatusHost() {
        val outside = sources.filterNot { it.path.fileName.toString() == "AccessibilitySemantics.kt" }
            .flatMap { source ->
                LIVE_OUTSIDE_HOST.findAll(source.code).map {
                    "${source.path.fileName}:${source.lineOf(it.range.first)}: ${it.value} outside the status host"
                }.toList()
            }
        assertTrue("status posted outside the host: $outside", outside.isEmpty())
        val legacy = sources.filter { ANNOUNCE_STATUS_LEGACY.containsMatchIn(it.code) }
            .map { it.path.fileName.toString() }
        assertTrue("announceStatus still used in: $legacy", legacy.isEmpty())
    }

    /**
     * P1-10 review fix 6: an announcement is built from string and plural resource
     * ids and counts. Span text, OCR text, file names and queries are shown as
     * visible text only, so no `Announce(...)` argument may name them.
     */
    @Test
    fun announcementArgumentsAreNeverDocumentContent() {
        val calls = sources.flatMap { source -> source.calls("Announce") }
        assertTrue("no Announce calls found: the rule would pass vacuously", calls.isNotEmpty())
        val violations = calls.filter { DOCUMENT_CONTENT.containsMatchIn(it.args) }
            .map { "${it.location()}: announcement argument names document content" }
        assertTrue("announcements carry document content: $violations", violations.isEmpty())
    }

    /**
     * The call that shows [resource]: string helpers are skipped; a Text must
     * announce itself, and any other composable must announce in its own body.
     */
    private fun statusViolation(source: KotlinSource, offset: Int, resource: String): String? {
        var call = source.enclosingCall(offset)
        while (call != null && call.name in STRING_HELPERS) call = source.enclosingCall(call.start)
        val where = "${source.path.fileName}:${source.lineOf(offset)}: $resource"
        return when {
            // An enum entry holds the resource as data; the composable that shows it is checked where it is called.
            call != null && call.name.isNotEmpty() && call.name.all { it.isUpperCase() || it == '_' } -> null

            announcedAt(source, offset) -> null

            else -> "$where is shown without Announce(...) in its composable"
        }
    }

    private companion object {
        val NULL_DESCRIPTION = Regex("""contentDescription\s*=\s*null""")
        val DESCRIPTION = Regex("""contentDescription\s*=\s*([^,\n)]+)""")
        val DECORATIVE = Regex("""//.*[Dd]ecorative""")
        val CLICK_MODIFIERS = listOf(".clickable", ".combinedClickable", ".selectable", ".toggleable")
        val MIN_TARGET = listOf(
            Regex("""heightIn\(\s*min\s*=\s*(4[89]|[5-9]\d|\d{3,})\.dp"""),
            Regex("""sizeIn\(\s*minWidth\s*=\s*(4[89]|[5-9]\d|\d{3,})\.dp"""),
            Regex("""minimumInteractiveComponentSize\(\)"""),
        )
        val HEADING_STYLE = Regex("""typography\.(display|headline|title)(Large|Medium|Small)""")
        val HEADING = Regex("""\b(heading|asHeading)\(\)""")
        val TITLE_SLOT = Regex("""\btitle\s*=\s*\{""")

        /** Title-styled text that is a label, not a heading; each with its reason. */
        val HEADING_EXEMPT = listOf(
            // A fact's label, merged with its value into one node (AboutScreen.Fact).
            "settings/AboutScreen.kt" to "stringResource(label)",
        )
        val FIXED_TEXT_SIZE = listOf(
            Regex("""fontSize\s*=\s*[\d.]+f?\s*\.dp"""),
            Regex("""\.toSp\(\)"""),
            Regex("""\bfontScale\s*="""),
            Regex("""\bTextUnit\("""),
        )
        val FIXED_SIZE = Regex("""\.(height|size|requiredHeight|requiredSize)\(\s*[\d.]+\.dp""")
        val TEXT_CALL = Regex("""(?<![\w.])Text\s*\(""")
        val GESTURES = listOf(
            Regex("""\.transformable\("""),
            Regex("""\bpointerInput\("""),
            Regex("""\bdetect\w*Gestures\("""),
            Regex("""\.draggable\("""),
        )

        // Assignments, not imports: a gesture's equivalent must be set in semantics.
        val GESTURE_EQUIVALENTS = listOf(Regex("""\bcustomActions\s*="""), Regex("""\bonClick\("""))
        val SECOND_CUE = listOf("textDecoration =", "fontWeight =", "fontStyle =")
        val SELECTED_PARAMETER = Regex("""\bselected:\s*Boolean""")
        val SELECTION_EXPOSED = listOf(Regex("""\bselected\s*=\s*selected\b"""), Regex("""\.selectable\("""))
        val ANNOUNCE = Regex("""\bAnnounce\(""")
        val LIVE_OUTSIDE_HOST = Regex("""liveRegion\s*=|LiveRegionMode\.""")
        val ANNOUNCE_STATUS_LEGACY = Regex("""announceStatus\(""")
        val DOCUMENT_CONTENT = Regex("""evidenceSpanPreview|\.text\b|spanText|selectedSpan|fileName|displayName|\bquery\b""")
        val FUN_DECLARATION = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")
        val STRING_HELPERS = setOf("stringResource", "pluralStringResource", "listOf", "localizedList", "withDataSpan")

        /** Text that appears when state changes without the user moving focus there. */
        val STATUS_RESOURCES = listOf(
            "viewer_extracting",
            "viewer_extraction_failed",
            "viewer_extraction_cancelled",
            "viewer_extraction_stale",
            "intake_preparing",
            "intake_saving",
            "intake_saved",
            "intake_duplicate",
            "intake_not_imported",
            "intake_busy",
            "intake_failed",
            "intake_storage_unavailable",
            "intake_vault_unavailable",
            "verify_all_verified",
            "verify_all_unavailable",
            "verify_all_failed",
            "lock_not_verified",
            "reset_vault_busy",
            "reset_vault_failed",
        )

        /** Screen root composables that must declare a pane title. */
        val SCREENS = listOf(
            "ui/SourceListScreen.kt" to "SourceListScreen",
            "ui/ViewerScreen.kt" to "ViewerBody",
            "ui/search/SearchScreen.kt" to "SearchScreen",
            "ui/IntakeScreen.kt" to "IntakeScreen",
            "ui/FirstRunPreferences.kt" to "FirstRunExplanationScreen",
            "settings/SettingsScreen.kt" to "SettingsScreen",
            "settings/AboutScreen.kt" to "AboutScreen",
            "lock/LockScreen.kt" to "LockScreen",
            "lock/LockScreen.kt" to "AppLockOfferScreen",
        )
        val PANE_TITLE = Regex("""paneTitle\s*=\s*([\w(.]+)""")
        val HARDCODED_A11Y_TEXT = listOf(
            Regex("""\b(onClickLabel|paneTitle|stateDescription|onLongClickLabel)\s*=\s*""""),
            Regex("""CustomAccessibilityAction\(\s*(label\s*=\s*)?""""),
            Regex("""\bonClick\(\s*label\s*=\s*""""),
        )
    }
}
