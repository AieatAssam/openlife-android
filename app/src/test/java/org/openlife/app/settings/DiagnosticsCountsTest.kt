package org.openlife.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.model.SourceState
import org.openlife.vault.repository.RecoveryReport
import org.openlife.vault.storage.VaultUnavailableCause

/** P1-08-R4: diagnostics are counts only; no identifier, digest or content reaches them. */
class DiagnosticsCountsTest {
    private val counts = DiagnosticsCounts(
        sourcesByState = mapOf(SourceState.READY to 12, SourceState.CORRUPT to 1, SourceState.DELETING to 2),
        lastRecovery = RecoveryReport(
            cleanedStaged = 1,
            confirmedReady = 12,
            markedCorrupt = 1,
            resumedDeletions = 2,
            removedOrphanFiles = 3,
            markedStaleOcrRevisions = 4,
            unrecognisedRows = 5,
            keptBlobsWithoutRows = 6,
        ),
        openFailures = mapOf(VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE to 2),
    )

    @Test
    fun containsNoUuidOrHashLikeStrings() {
        val rows = counts.rows()
        val rendered = rows.map { "${it.group} ${it.item} ${it.count}" } + rows.toString() + counts.toString()
        rendered.forEach { text ->
            assertFalse("UUID in $text", UUID_LIKE.containsMatchIn(text))
            assertFalse("hex digest in $text", HEX_RUN.containsMatchIn(text))
            assertFalse("base64 run in $text", BASE64_RUN.containsMatchIn(text))
        }
        // Every row names an enum counter and an integer; nothing else can be carried.
        rows.forEach { row ->
            val allowed = row.item is SourceState || row.item is RecoveryCount || row.item is VaultUnavailableCause
            assertTrue("unexpected counter ${row.item::class}", allowed)
        }
    }

    @Test
    fun everyStateAndRecoveryCounterIsListedAndFailuresOnlyWhenRecorded() {
        val rows = counts.rows()
        val states = rows.filter { it.group == DiagnosticGroup.SOURCES_BY_STATE }
        assertEquals(SourceState.entries.toList(), states.map { it.item })
        assertEquals(listOf(0, 12, 2, 1, 0), states.map { it.count })

        val recovery = rows.filter { it.group == DiagnosticGroup.LAST_RECOVERY }
        assertEquals(RecoveryCount.entries.toList(), recovery.map { it.item })
        assertEquals(listOf(1, 12, 1, 2, 3, 4, 5, 6), recovery.map { it.count })

        val failures = rows.filter { it.group == DiagnosticGroup.VAULT_OPEN_FAILURES }
        val keystore = VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE
        assertEquals(listOf(DiagnosticRow(DiagnosticGroup.VAULT_OPEN_FAILURES, keystore, 2)), failures)

        // A vault that is not open has no item or recovery counts, only failures.
        val closed = DiagnosticsCounts(null, null, emptyMap()).rows()
        assertTrue(closed.isEmpty())
    }

    private companion object {
        val UUID_LIKE = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        val HEX_RUN = Regex("""[0-9a-fA-F]{16,}""")
        val BASE64_RUN = Regex("""[A-Za-z0-9+/]{24,}={0,2}""")
    }
}
