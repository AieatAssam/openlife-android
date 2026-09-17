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
            Files.readAllLines(file).forEachIndexed { index, line ->
                val location = "$file:${index + 1}"
                if (line.contains("ClipboardManager") || line.contains("WebView")) {
                    violations += "$location: clipboard or WebView reference"
                }
                if (line.contains("android.util.Log") &&
                    (file != recoveryLogFile || line.trim() != ALLOWED_RECOVERY_LOG)
                ) {
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
