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
        val files = sourceFiles()
        val violations = mutableListOf<String>()
        files.forEach { file ->
            val lines = Files.readAllLines(file)
            val recoveryLogIsDebugOnly = file == recoveryLogFile && lines.any {
                it.trim() == "if (BuildConfig.DEBUG) {"
            }
            lines.forEachIndexed { index, line ->
                val location = "$file:${index + 1}"
                if (line.contains("ClipboardManager") || line.contains("WebView")) {
                    violations += "$location: clipboard or WebView reference"
                }
                val allowlistedRecoveryLog = file == recoveryLogFile &&
                    line.trim() == ALLOWED_RECOVERY_LOG && recoveryLogIsDebugOnly
                if (line.contains("android.util.Log") && !allowlistedRecoveryLog) {
                    violations += "$location: unallowlisted Log reference"
                }
                if (line.contains("startActivity") && !file.startsWith(intakeRoot) && file != mainActivity) {
                    violations += "$location: startActivity outside the intake/MainActivity allowlist"
                }
                if (Regex("ACTION_(VIEW|SEND|SENDTO)").containsMatchIn(line) &&
                    !file.startsWith(intakeRoot) && file != mainActivity
                ) {
                    violations += "$location: outbound Intent action outside the intake/MainActivity allowlist"
                }
            }
        }
        assertTrue("source boundary violations: $violations", violations.isEmpty())
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
    }
}
