package org.openlife.app.boundary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Source-level egress boundaries. These scans are tripwires, not proofs: they
 * read source lines and catch the known ways content could leave (clipboard,
 * logs, share and document intents, selectable platform text views,
 * selection containers, unwrapped text fields), each with a self-check
 * sample. Code that reaches the same API by a route the patterns do not name
 * (reflection, a new library) is not caught.
 */
class SourceEgressBoundaryTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))
    private val sourceRoots = listOf(
        projectDir.resolve("app/src/main"),
        projectDir.resolve("vault/src/main"),
    )
    private val intakeRoot = projectDir.resolve("app/src/main/java/org/openlife/app/intake")
    private val mainActivity = projectDir.resolve("app/src/main/java/org/openlife/app/MainActivity.kt")
    private val recoveryLogFile = projectDir.resolve("app/src/main/java/org/openlife/app/OpenLifeApp.kt")

    @Test
    fun noOutboundContentChannelsInShippedSource() {
        val violations = sourceFiles().flatMap { file -> boundaryViolations(file) }
        assertTrue("source boundary violations: $violations", violations.isEmpty())
    }

    /**
     * P2-06-R15 (E-08): the EvidenceBounds log is allowed only inside its
     * BuildConfig.DEBUG guard, as the recovery log is. Two built-in mutations
     * must be reported: the guard line removed, and the log moved into the
     * guard's `else` branch (release-only code, S-03).
     */
    @Test
    fun evidenceBoundsLogRequiresDebugGuard() {
        val file = projectDir.resolve("app/src/main/java/org/openlife/app/ui/evidence/ZoomableEvidenceImage.kt")
        val lines = Files.readAllLines(file)
        assertTrue("the bounds log is still present", lines.any { it.trim() == ALLOWED_EVIDENCE_BOUNDS_LOG })
        val shipped = boundaryViolations(file, lines)
        assertTrue("the shipped, guarded log passes: $shipped", shipped.isEmpty())

        val unguarded = lines.filterNot { it.trim() == DEBUG_GUARD }
        assertTrue("the mutation removes a guard", unguarded.size < lines.size)
        assertFalse(
            "an EvidenceBounds log outside BuildConfig.DEBUG must be a violation",
            boundaryViolations(file, unguarded).isEmpty(),
        )

        val inElseBranch = lines.flatMap { if (it.trim() == DEBUG_GUARD) listOf(it, "} else {") else listOf(it) }
        assertFalse(
            "an EvidenceBounds log in the else branch of the DEBUG guard must be a violation",
            boundaryViolations(file, inElseBranch).isEmpty(),
        )
    }

    private fun boundaryViolations(file: Path, lines: List<String> = Files.readAllLines(file)): List<String> {
        val recoveryLogIsDebugOnly = file == recoveryLogFile && lines.any {
            it.trim() == "if (BuildConfig.DEBUG) {"
        }
        return lines.mapIndexedNotNull { index, line ->
            lineViolation(file, lines, index, recoveryLogIsDebugOnly)
        }
    }

    private fun lineViolation(file: Path, lines: List<String>, index: Int, recoveryLogIsDebugOnly: Boolean): String? {
        val line = lines[index]
        val location = "$file:${index + 1}"
        val debugOnly = line.trim() == ALLOWED_EVIDENCE_BOUNDS_LOG && enclosedByDebugGuard(lines, index)
        return clipboardViolation(file, line, location)
            ?: logViolation(file, line, location, recoveryLogIsDebugOnly, debugOnly)
            ?: activityViolation(file, line, location)
            ?: selectableTextViolation(line, location)
            ?: shareViolation(line, location)
            ?: linkViolation(line, location)
    }

    /** P1-08-R5: no link or URI handler, HTML link parsing, Linkify or custom tab anywhere in shipped code. */
    private fun linkViolation(line: String, location: String): String? =
        if (LINK_OPENING.containsMatchIn(line)) "$location: link-opening API" else null

    /** Review U-02: platform text views (AndroidView-hosted) and SelectionContainer can copy shown text. */
    private fun selectableTextViolation(line: String, location: String): String? =
        if (SELECTABLE_TEXT.containsMatchIn(line)) "$location: selectable platform text or SelectionContainer" else null

    /** Review U-02: no share sheet or document export anywhere in shipped code. */
    private fun shareViolation(line: String, location: String): String? =
        if (SHARE_OR_EXPORT.containsMatchIn(line)) "$location: share or document-export API" else null

    /** Review U-02 self-checks: each new egress pattern appended to ViewerScreen is reported. */
    @Test
    fun everyNewEgressPatternIsReported() {
        val viewer = projectDir.resolve("app/src/main/java/org/openlife/app/ui/ViewerScreen.kt")
        val lines = Files.readAllLines(viewer)
        listOf(
            "    AndroidView(factory = { context -> EditText(context) })",
            "    AndroidView(factory = { context -> TextView(context).apply { setTextIsSelectable(true) } })",
            "    SelectionContainer { Text(text) }",
            "import android.util.*",
            "import android.util.Log",
            "    Log.d(\"Viewer\", text)",
            "    println(text)",
            "    error.printStackTrace()",
            "    System.err.write(bytes)",
            "    val send = Intent(Intent.ACTION_SEND)",
            "    val send = Intent(\"android.intent.action.SEND\")",
            "    ShareCompat.IntentBuilder(context).setText(text).startChooser()",
            "    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(\"text/plain\")) {}",
            "    context.startActivity(Intent.createChooser(intent, null))",
        ).forEach { mutation ->
            assertFalse(
                "\"$mutation\" in ViewerScreen must be a violation",
                boundaryViolations(viewer, lines + mutation).isEmpty(),
            )
        }
    }

    /**
     * P1-08-R5: no link is ever opened. A Compose link or URI handler opens a
     * browser without any `startActivity` in OpenLife's source, so those APIs
     * are named here; About shows addresses as plain text.
     */
    @Test
    fun noLinkOpeningApiIsReported() {
        val viewer = projectDir.resolve("app/src/main/java/org/openlife/app/ui/ViewerScreen.kt")
        val lines = Files.readAllLines(viewer)
        listOf(
            "    val uriHandler = LocalUriHandler.current",
            "    onClick = { handler.openUri(url) }",
            "    withLink(LinkAnnotation.Url(url)) { append(url) }",
            "    pushUrlAnnotation(UrlAnnotation(url))",
            "    Text(AnnotatedString.fromHtml(html))",
            "    Linkify.addLinks(view, Linkify.WEB_URLS)",
            "    CustomTabsIntent.Builder().build().launchUrl(context, uri)",
            "    view.autoLinkMask = 1",
            "    val handler = AndroidUriHandler(context)",
        ).forEach { mutation ->
            assertFalse(
                "\"$mutation\" in ViewerScreen must be a violation",
                boundaryViolations(viewer, lines + mutation).isEmpty(),
            )
        }
    }

    /**
     * P1-08-R5 (review): XML can turn on links without Kotlin: `autoLink` on
     * a TextView, or a BROWSABLE / http(s) intent filter that would make
     * OpenLife a link target. No shipped resource or manifest may contain one.
     */
    @Test
    fun noResourceOrManifestEnablesLinks() {
        val violations = xmlFiles().flatMap { file -> xmlLinkViolations(file, Files.readAllLines(file)) }
        assertTrue("link-enabling XML:\n${violations.joinToString("\n")}", violations.isEmpty())

        val sample = projectDir.resolve("app/src/main/res/raw/keep.xml")
        listOf(
            """<TextView android:autoLink="web" />""",
            """<category android:name="android.intent.category.BROWSABLE" />""",
            """<data android:scheme="https" />""",
        ).forEach { mutation ->
            assertFalse(
                "\"$mutation\" must be a violation",
                xmlLinkViolations(sample, Files.readAllLines(sample) + mutation).isEmpty(),
            )
        }
    }

    private fun xmlLinkViolations(file: Path, lines: List<String>): List<String> =
        lines.mapIndexedNotNull { index, line ->
            if (XML_LINK.containsMatchIn(line)) "${projectDir.relativize(file)}:${index + 1}: link-enabling XML" else null
        }

    private fun xmlFiles(): List<Path> = listOf("main", "debug", "release").flatMap { set ->
        val root = projectDir.resolve("app/src/$set")
        if (!Files.isDirectory(root)) {
            emptyList()
        } else {
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".xml") }.collect(Collectors.toList())
            }
        }
    }

    /**
     * True when a block opened by [DEBUG_GUARD] encloses line [index]. The
     * guard's `else` block has the header `} else`, so code there is not
     * accepted (S-03).
     */
    private fun enclosedByDebugGuard(lines: List<String>, index: Int): Boolean =
        enclosingBlockHeaders(lines, index).any { it == DEBUG_GUARD_HEADER }

    /**
     * The text before each `{` that opens a block enclosing line [index],
     * innermost first. The scan walks backwards one character at a time; a
     * `{` met while the depth is zero opens an enclosing block.
     */
    private fun enclosingBlockHeaders(lines: List<String>, index: Int): List<String> {
        val headers = mutableListOf<String>()
        var depth = 0
        for (i in index - 1 downTo 0) {
            val line = lines[i]
            for (column in line.lastIndex downTo 0) {
                val char = line[column]
                when {
                    char == '}' -> depth++
                    char == '{' && depth > 0 -> depth--
                    char == '{' -> headers += line.substring(0, column).trim()
                }
            }
        }
        return headers
    }

    /**
     * Review Q-01: the platform clipboard manager may be named only on the one
     * read-only line of ContentTextField.kt, and the Compose clipboard
     * (LocalClipboard, setClipEntry) only in that file.
     */
    private fun clipboardViolation(file: Path, line: String, location: String): String? {
        val inContentField = file.endsWith(CONTENT_TEXT_FIELD_FILE)
        val platformClipboard = line.contains("ClipboardManager", ignoreCase = true) &&
            !(inContentField && line.trim() == ALLOWED_CLIPBOARD_READ)
        val composeClipboard = !inContentField && COMPOSE_CLIPBOARD.containsMatchIn(line)
        return if (platformClipboard || composeClipboard || line.contains("WebView")) {
            "$location: clipboard or WebView reference"
        } else {
            null
        }
    }

    @Test
    fun clipboardReferencesAreConfinedToTheReadOnlyField() {
        val viewer = projectDir.resolve("app/src/main/java/org/openlife/app/ui/ViewerScreen.kt")
        val field = projectDir.resolve(CONTENT_TEXT_FIELD_FILE.toString())
        assertTrue(
            "the read-only clipboard line exists",
            Files.readAllLines(field).any { it.trim() == ALLOWED_CLIPBOARD_READ },
        )
        listOf(
            viewer to "    val clipboard = LocalClipboard.current",
            viewer to "    scope.launch { clipboard.setClipEntry(entry) }",
            field to "        platform.nativeClipboardManager.setPrimaryClip(clip)",
        ).forEach { (file, mutation) ->
            assertFalse(
                "\"$mutation\" in ${file.fileName} must be a violation",
                boundaryViolations(file, Files.readAllLines(file) + mutation).isEmpty(),
            )
        }
    }

    private fun logViolation(
        file: Path,
        line: String,
        location: String,
        recoveryLogIsDebugOnly: Boolean,
        debugOnly: Boolean,
    ): String? {
        if (BARE_OUTPUT.containsMatchIn(line)) return "$location: bare Log, println, printStackTrace or System output"
        if (!line.contains("android.util.Log") || allowlistedLog(file, line, recoveryLogIsDebugOnly, debugOnly)) {
            return null
        }
        return "$location: unallowlisted Log reference"
    }

    private fun allowlistedLog(file: Path, line: String, recoveryLogIsDebugOnly: Boolean, debugOnly: Boolean): Boolean {
        val trimmed = line.trim()
        val recovery = file == recoveryLogFile && trimmed == ALLOWED_RECOVERY_LOG && recoveryLogIsDebugOnly
        // P2-06-R15 (E-08): the bounds log must sit inside its BuildConfig.DEBUG block.
        val bounds = file.fileName.toString() == "ZoomableEvidenceImage.kt" &&
            trimmed == ALLOWED_EVIDENCE_BOUNDS_LOG && debugOnly
        return recovery || bounds
    }

    private fun activityViolation(file: Path, line: String, location: String): String? {
        val outside = !file.startsWith(intakeRoot) && file != mainActivity
        if (!outside) return null
        return when {
            line.contains("startActivity") -> "$location: startActivity outside the intake/MainActivity allowlist"

            OUTBOUND_ACTION.containsMatchIn(line) ->
                "$location: outbound Intent action outside the intake/MainActivity allowlist"

            else -> null
        }
    }

    @Test
    fun noRepositoryConstructsItsOwnMutationQueue() {
        val violations = sourceFiles().flatMap { file ->
            if (file.fileName.toString() == "OpenLifeApp.kt") return@flatMap emptyList()
            Files.readAllLines(file).mapIndexedNotNull { index, line ->
                if (line.contains("MutationQueue()")) {
                    "$file:${index + 1}: repository constructs its own MutationQueue"
                } else {
                    null
                }
            }
        }
        assertTrue("mutation queue ownership violations: $violations", violations.isEmpty())
    }

    /** P1-02-R3 / design §8 R3: picker and document grants stay one-shot. */
    @Test
    fun noPersistableUriGrantIsEverTaken() {
        val violations = sourceFiles().flatMap { file ->
            Files.readAllLines(file).mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//")
                val persistable = code.contains("takePersistableUriPermission") ||
                    code.contains("FLAG_GRANT_PERSISTABLE_URI_PERMISSION")
                if (persistable) {
                    "$file:${index + 1}: persistable URI grant"
                } else {
                    null
                }
            }
        }
        assertTrue("persistable grant violations: $violations", violations.isEmpty())
    }

    /**
     * A-02 (P1-19-R5/R6, review Q-06): a text field that can hold user content
     * must be the ContentTextField wrapper (no Copy/Cut, no autocorrect, no
     * personalised IME learning). That covers Material and foundation text
     * fields, search-bar inputs, fully qualified calls, function references
     * and aliased imports. The reset-confirmation word field holds no content
     * and is the one named exemption: the marker comment must sit directly on
     * `OutlinedTextField(` whose first argument is `value = word,`.
     */
    @Test
    fun everyContentTextFieldUsesTheSafeWrapper() {
        val violations = textFieldViolations(sourceFiles())
        assertTrue("text fields outside ContentTextField: $violations", violations.isEmpty())

        val viewer = projectDir.resolve("app/src/main/java/org/openlife/app/ui/ViewerScreen.kt")
        val viewerLines = Files.readAllLines(viewer)
        listOf(
            "    OutlinedTextField(value = text, onValueChange = {})",
            "    androidx.compose.material3.OutlinedTextField(value = text, onValueChange = {})",
            "import androidx.compose.material3.TextField as PlainField",
            "    SearchBarDefaults.InputField(query = text, onQueryChange = {}, onSearch = {}, expanded = false)",
            "    SearchBar(inputField = {}, expanded = false, onExpandedChange = {}) {}",
            "    val field = ::BasicTextField",
            "    `OutlinedTextField`(value = text, onValueChange = {})",
            "    val field = ::`TextField`",
        ).forEach { mutation ->
            assertFalse(
                "\"$mutation\" in ViewerScreen must be a violation",
                textFieldLineViolations(viewer, viewerLines + mutation).isEmpty(),
            )
        }
        val markedElsewhere = viewerLines +
            listOf("    // $RESET_WORD_EXEMPTION", "    OutlinedTextField(", "        value = word,")
        assertFalse(
            "the exemption marker outside ResetVaultFlow must not exempt anything",
            textFieldLineViolations(viewer, markedElsewhere).isEmpty(),
        )

        val reset = projectDir.resolve(RESET_FLOW_FILE.toString())
        val resetLines = Files.readAllLines(reset)
        val exemptCall = resetLines.indexOfFirst { it.trim() == "OutlinedTextField(" }
        assertTrue("the exempt reset-word call exists", exemptCall > 0)
        val nearTheMarker = resetLines.toMutableList().apply {
            add(exemptCall, "                OutlinedTextField(value = note, onValueChange = {})")
        }
        assertTrue(
            "a second field next to the exemption marker must be a violation",
            textFieldLineViolations(reset, nearTheMarker).any { "value = note" in it },
        )
    }

    private fun textFieldViolations(files: List<Path>): List<String> =
        files.flatMap { file -> textFieldLineViolations(file, Files.readAllLines(file)) }

    private fun textFieldLineViolations(file: Path, lines: List<String>): List<String> {
        val wrapperFile = file.endsWith(CONTENT_TEXT_FIELD_FILE)
        return lines.mapIndexedNotNull { index, line ->
            val isImport = line.trimStart().startsWith("import ")
            val call = !isImport &&
                (BARE_TEXT_FIELD.containsMatchIn(line) || TEXT_FIELD_REFERENCE.containsMatchIn(line))
            val violation = ALIASED_TEXT_FIELD_IMPORT.containsMatchIn(line) ||
                (call && !wrapperFile && !isResetWordExemption(file, lines, index))
            if (violation) "$file:${index + 1}: ${line.trim()}" else null
        }
    }

    /** True only for the reset-word call: marker comment block directly above, `value = word,` directly below. */
    private fun isResetWordExemption(file: Path, lines: List<String>, index: Int): Boolean {
        if (!file.endsWith(RESET_FLOW_FILE) || lines[index].trim() != "OutlinedTextField(") return false
        if (lines.getOrNull(index + 1)?.trim() != "value = word,") return false
        val comments = (index - 1 downTo 0).asSequence()
            .map { lines[it].trim() }
            .takeWhile { it.startsWith("//") }
            .toList()
        return comments.any { RESET_WORD_EXEMPTION in it }
    }

    private fun sourceFiles(): List<Path> = sourceRoots.flatMap { root ->
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .collect(Collectors.toList())
        }
    }

    private companion object {
        const val ALLOWED_RECOVERY_LOG = "android.util.Log.i(\"OpenLifeRecovery\", report.toString())"
        const val ALLOWED_EVIDENCE_BOUNDS_LOG = "android.util.Log.d(\"EvidenceBounds\", message)"
        const val DEBUG_GUARD = "if (BuildConfig.DEBUG) {"
        const val DEBUG_GUARD_HEADER = "if (BuildConfig.DEBUG)"
        val OUTBOUND_ACTION = Regex("""ACTION_(VIEW|SEND|SENDTO)|"android\.intent\.action\.(VIEW|SEND|SENDTO)""")
        val SELECTABLE_TEXT = Regex(
            """\b(EditText|TextView|AppCompatEditText|AppCompatTextView|TextInputEditText|SelectionContainer)\b|""" +
                """TextIsSelectable|textIsSelectable""",
        )
        val XML_LINK = Regex("""autoLink|android\.intent\.category\.BROWSABLE|android:scheme="https?"""")
        val LINK_OPENING = Regex(
            """\b(LocalUriHandler|UriHandler|openUri|LinkAnnotation|UrlAnnotation|fromHtml|Linkify|launchUrl)\b|""" +
                """\bCustomTabs\w*|\bautoLink\w*""",
        )
        val SHARE_OR_EXPORT = Regex("""\bShareCompat\b|\bCreateDocument\b|ACTION_CREATE_DOCUMENT|\bcreateChooser\b""")
        val BARE_OUTPUT = Regex(
            """^\s*import\s+android\.util\.(\*|Log\b)|(?<![\w.])Log\.(v|d|i|w|e|wtf|println)\s*\(|""" +
                """\bprintln\s*\(|\.printStackTrace\s*\(|\bSystem\.(out|err)\b""",
        )
        const val TEXT_FIELD_NAMES =
            "TextField|OutlinedTextField|BasicTextField|BasicSecureTextField|SecureTextField|" +
                "OutlinedSecureTextField|SearchBar|DockedSearchBar|InputField"
        val BARE_TEXT_FIELD = Regex("""(?<![A-Za-z0-9_])($TEXT_FIELD_NAMES)`?\s*\(""")
        val TEXT_FIELD_REFERENCE = Regex("""::`?($TEXT_FIELD_NAMES)\b""")
        val ALIASED_TEXT_FIELD_IMPORT = Regex("""^\s*import\s+[\w.]+\.($TEXT_FIELD_NAMES)\s+as\s+\w+""")
        const val ALLOWED_CLIPBOARD_READ =
            "override val clipboardManager get() = (platform as AndroidClipboard).clipboardManager"
        val COMPOSE_CLIPBOARD = Regex("""\bLocalClipboard\b|\bsetClipEntry\b|\bsetPrimaryClip\b""")
        val CONTENT_TEXT_FIELD_FILE: Path = Path.of("app/src/main/java/org/openlife/app/ui/ContentTextField.kt")
        val RESET_FLOW_FILE: Path = Path.of("app/src/main/java/org/openlife/app/settings/ResetVaultFlow.kt")
        const val RESET_WORD_EXEMPTION = "P1-19-R5 exemption: reset confirmation word"
    }
}
