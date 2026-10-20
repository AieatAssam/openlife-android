package org.openlife.app.boundary

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

    private fun boundaryViolations(file: Path): List<String> {
        val lines = Files.readAllLines(file)
        val recoveryLogIsDebugOnly = file == recoveryLogFile && lines.any {
            it.trim() == "if (BuildConfig.DEBUG) {"
        }
        return lines.mapIndexedNotNull { index, line ->
            lineViolation(file, index, line, recoveryLogIsDebugOnly)
        }
    }

    private fun lineViolation(file: Path, index: Int, line: String, recoveryLogIsDebugOnly: Boolean): String? {
        val location = "$file:${index + 1}"
        return clipboardViolation(line, location)
            ?: logViolation(file, line, location, recoveryLogIsDebugOnly)
            ?: activityViolation(file, line, location)
    }

    private fun clipboardViolation(line: String, location: String): String? =
        if (line.contains("ClipboardManager") || line.contains("WebView")) {
            "$location: clipboard or WebView reference"
        } else {
            null
        }

    private fun logViolation(file: Path, line: String, location: String, recoveryLogIsDebugOnly: Boolean): String? {
        if (!line.contains("android.util.Log") || allowlistedLog(file, line, recoveryLogIsDebugOnly)) return null
        return "$location: unallowlisted Log reference"
    }

    private fun allowlistedLog(file: Path, line: String, recoveryLogIsDebugOnly: Boolean): Boolean {
        val trimmed = line.trim()
        val recovery = file == recoveryLogFile && trimmed == ALLOWED_RECOVERY_LOG && recoveryLogIsDebugOnly
        val bounds = file.fileName.toString() == "ZoomableEvidenceImage.kt" && trimmed == ALLOWED_EVIDENCE_BOUNDS_LOG
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
        val OUTBOUND_ACTION = Regex("ACTION_(VIEW|SEND|SENDTO)")
    }
}
