package org.openlife.app.boundary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

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
     * BuildConfig.DEBUG guard, as the recovery log is. The mutation (the same
     * file with the guard line removed) must be reported.
     */
    @Test
    fun evidenceBoundsLogRequiresDebugGuard() {
        val file = projectDir.resolve("app/src/main/java/org/openlife/app/ui/evidence/ZoomableEvidenceImage.kt")
        val lines = Files.readAllLines(file)
        assertTrue("the bounds log is still present", lines.any { it.trim() == ALLOWED_EVIDENCE_BOUNDS_LOG })
        assertTrue("the shipped, guarded log passes: ${boundaryViolations(file, lines)}", boundaryViolations(file, lines).isEmpty())

        val unguarded = lines.filterNot { it.trim() == DEBUG_GUARD }
        assertTrue("the mutation removes a guard", unguarded.size < lines.size)
        assertFalse(
            "an EvidenceBounds log outside BuildConfig.DEBUG must be a violation",
            boundaryViolations(file, unguarded).isEmpty(),
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
        val debugOnly = enclosedByDebugGuard(lines, index)
        return clipboardViolation(line, location)
            ?: logViolation(file, line, location, recoveryLogIsDebugOnly, debugOnly)
            ?: activityViolation(file, line, location)
    }

    /** True when a block opened by [DEBUG_GUARD] encloses line [index] (brace depth, read backwards). */
    private fun enclosedByDebugGuard(lines: List<String>, index: Int): Boolean {
        var depth = 0
        for (i in index - 1 downTo 0) {
            val line = lines[i]
            depth += line.count { it == '}' } - line.count { it == '{' }
            if (depth < 0) {
                if (line.trim() == DEBUG_GUARD) return true
                depth = 0
            }
        }
        return false
    }

    private fun clipboardViolation(line: String, location: String): String? =
        if (line.contains("ClipboardManager") || line.contains("WebView")) {
            "$location: clipboard or WebView reference"
        } else {
            null
        }

    private fun logViolation(
        file: Path,
        line: String,
        location: String,
        recoveryLogIsDebugOnly: Boolean,
        debugOnly: Boolean,
    ): String? {
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
        val OUTBOUND_ACTION = Regex("ACTION_(VIEW|SEND|SENDTO)")
    }
}
