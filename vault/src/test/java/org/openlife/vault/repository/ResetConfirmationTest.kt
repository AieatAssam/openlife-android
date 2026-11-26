package org.openlife.vault.repository

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/** P1-08 (review): a reset confirmation exists only for the exact word or an unfinished confirmed reset. */
class ResetConfirmationTest {
    @Test
    fun onlyTheExactWordMintsAConfirmation() {
        assertNotNull(ResetConfirmation.ofTypedWord("RESET", "RESET"))
        listOf("", "RES", "reset", "RESET ", " RESET", "RESETS", "R E S E T").forEach { typed ->
            assertNull("'$typed' must not confirm", ResetConfirmation.ofTypedWord(typed, "RESET"))
        }
        assertNull("a blank required word confirms nothing", ResetConfirmation.ofTypedWord("", ""))
        assertNull(ResetConfirmation.ofTypedWord(" ", " "))
    }

    @Test
    fun onlyAnExistingResetMarkerMintsAResumeConfirmation() {
        val dir = Files.createTempDirectory("reset-confirmation").toFile()
        val marker = dir.resolve("RESET_IN_PROGRESS")
        assertNull(ResetConfirmation.ofIncompleteReset(marker))
        marker.writeBytes(byteArrayOf(1))
        assertNotNull(ResetConfirmation.ofIncompleteReset(marker))
        dir.deleteRecursively()
    }
}
