package org.openlife.app.boundary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * P1-08 (added by review, acting owner 2026-11-26): a vault reset needs a
 * `ResetConfirmation`, whose constructor is private. Each way to mint one
 * has exactly one caller in shipped code:
 * - `ofTypedWord` in SettingsController, when the exact word was typed;
 * - `ofIncompleteReset` in AppContainer (finishVaultReset), to finish a reset
 *   the user already confirmed whose marker is still on disk.
 * Tests may mint one; shipped code elsewhere may not.
 */
class ResetConfirmationBoundaryTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))
    private val roots = listOf(projectDir.resolve("app/src/main"), projectDir.resolve("vault/src/main"))

    @Test
    fun confirmationsAreMintedOnlyByTheirOneCaller() {
        val files = sourceFiles()
        assertEquals(emptyList<String>(), violations(files.associateWith { Files.readAllLines(it) }))
        MINTERS.forEach { (call, owner) ->
            val file = files.single { it.endsWith(owner) }
            assertTrue("$owner must mint through $call", Files.readAllLines(file).any { it.contains(call) })
        }
    }

    @Test
    fun aMintElsewhereIsReported() {
        val files = sourceFiles().associateWith { Files.readAllLines(it) }
        val elsewhere = files.keys.single { it.endsWith("app/src/main/java/org/openlife/app/OpenLifeApp.kt") }
        listOf(
            "    val token = ResetConfirmation.ofTypedWord(\"RESET\", \"RESET\")",
            "    val token = ResetConfirmation.ofIncompleteReset(marker)",
        ).forEach { mutation ->
            val mutated = files + (elsewhere to files.getValue(elsewhere) + mutation)
            assertFalse("\"$mutation\" in OpenLifeApp must be a violation", violations(mutated).isEmpty())
        }
    }

    private fun violations(files: Map<Path, List<String>>): List<String> = files.flatMap { (file, lines) ->
        lines.mapIndexedNotNull { index, line ->
            MINTERS.entries.firstOrNull { (call, owner) -> line.contains(call) && !file.endsWith(owner) }
                ?.let { (call, _) -> "${projectDir.relativize(file)}:${index + 1}: $call outside its one caller" }
        }
    }

    private fun sourceFiles(): List<Path> = roots.flatMap { root ->
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.collect(Collectors.toList())
        }
    }.filterNot { it.endsWith("vault/src/main/java/org/openlife/vault/repository/ResetConfirmation.kt") }

    private companion object {
        val MINTERS = mapOf(
            "ofTypedWord(" to "app/src/main/java/org/openlife/app/settings/SettingsController.kt",
            "ofIncompleteReset(" to "app/src/main/java/org/openlife/app/AppContainer.kt",
        )
    }
}
