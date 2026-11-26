package org.openlife.vault.storage

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P3-02 review findings M-01, M-02, M-03 and M-07 on host SQLite (3.53, not
 * SQLCipher), run with recursive triggers on and off (M-05): the delete
 * guards on facts, evidence and review events (C2-R7, C2-R12, C2-R20), a
 * grounded region (C2-R8), ordered VALUE offsets (C2 §4.3), a DELETING
 * Source's facts hidden from the list (C2-R43), and a fresh v6 database
 * with the same triggers and indices as a migrated one.
 */
class FactGuardTest {
    private val hosts = mutableListOf<HostSqlite>()

    @After
    fun close() = hosts.forEach(HostSqlite::close)

    private fun refused(requirement: String, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal citing $requirement")
        } catch (e: HostSqlite.Failure) {
            assertTrue("${e.message} should cite $requirement", e.message.orEmpty().contains(requirement))
        }
    }

    /** Source `a`: fact `f` whose current CONFIRMED EXTRACTED revision `x` has one VALUE row and one event. */
    private fun confirmed(recursive: Boolean): Pair<HostSqlite, FactRows> {
        val host = HostSqlite(recursiveTriggers = recursive).also { hosts += it }
        val rows = FactRows(host)
        rows.readySourceWithReading(FactHost.fresh(host), "a")
        rows.fact("f", "a")
        rows.revision("x", "f")
        rows.current("f", "x")
        rows.evidence("e1", "x", "a", "a-s1")
        host.exec("UPDATE fact_revisions SET status = 'CONFIRMED' WHERE id = 'x'")
        rows.reviewEvent("v1", "x")
        return host to rows
    }

    @Test
    fun factsEvidenceAndReviewEventsAreDeletedOnlyWithTheirSource() {
        listOf(true, false).forEach { recursive ->
            val (host, rows) = confirmed(recursive)
            refused("C2-R20") { host.exec("DELETE FROM facts WHERE id = 'f'") }
            refused("C2-R20") { host.exec("DELETE FROM fact_evidence WHERE id = 'e1'") }
            refused("C2-R20") { host.exec("DELETE FROM review_events WHERE id = 'v1'") }
            assertEquals(
                "nothing went (recursive=$recursive)",
                listOf(1L, 1L, 1L, 1L),
                listOf("facts", "fact_revisions", "fact_evidence", "review_events").map {
                    host.long("SELECT COUNT(*) FROM $it")
                },
            )
            rows.setState("a", "DELETING")
            host.exec("DELETE FROM review_events WHERE id = 'v1'")
            host.exec("DELETE FROM fact_evidence WHERE id = 'e1'")
            host.exec("DELETE FROM facts WHERE id = 'f'")
            assertEquals(0L, host.long("SELECT COUNT(*) FROM fact_revisions"))
        }
    }

    @Test
    fun evidenceOnAnUngroundedSpanIsRefused() {
        val (host, rows) = confirmed(recursive = true)
        host.exec(
            "INSERT INTO ocr_spans(id, revisionId, ordinal, text, confidence, coordinateSystem, `left`, top, " +
                "`right`, bottom) VALUES ('a-s3', 'a-r', 2, 'Due 27.11.2026', NULL, 'SOURCE_PIXELS', NULL, NULL, NULL, NULL)",
        )
        rows.fact("g", "a")
        rows.revision("y", "g")
        refused("C2-R8") { rows.evidence("e2", "y", "a", "a-s3") }
        assertEquals(1L, host.long("SELECT COUNT(*) FROM fact_evidence"))
    }

    @Test
    fun valueOffsetsAreSetAndStrictlyOrdered() {
        val (host, rows) = confirmed(recursive = true)
        rows.fact("m", "a", kind = "MONEY")
        val money = "{\"amount\":\"12.50\",\"currency\":\"EUR\",\"rawSymbol\":null}"
        rows.revision("y", "m", kind = "MONEY", valueJson = money)
        refused("C2 §4.3") { rows.evidence("e2", "y", "a", "a-s2", start = null, end = null) }
        refused("C2 §4.3") { rows.evidence("e2", "y", "a", "a-s2", start = 3, end = 3) }
        rows.evidence("e2", "y", "a", "a-s2", start = 6, end = 11)
        refused("C2 §4.3") { rows.evidence("e3", "y", "a", "a-s2", role = "CURRENCY_CONTEXT", start = 2, end = 2) }
        rows.evidence("e3", "y", "a", "a-s2", role = "CURRENCY_CONTEXT", start = null, end = null)
        rows.evidence("e4", "y", "a", "a-s2", role = "CURRENCY_CONTEXT", start = 6, end = 9)
        assertEquals(4L, host.long("SELECT COUNT(*) FROM fact_evidence"))
    }

    @Test
    fun aDeletingSourceShowsNoFacts() = runBlocking {
        val (host, rows) = confirmed(recursive = true)
        val dao = HostFactDao(host)
        assertEquals(listOf("x"), dao.observeCurrentFacts("a").first().map { it.revision.id })
        rows.setState("a", "DELETING")
        assertEquals(emptyList<CurrentFactRow>(), dao.observeCurrentFacts("a").first())
    }

    @Test
    fun freshAndMigratedVersionSixHaveTheSameTriggersAndIndices() {
        val fresh = HostSqlite().also { hosts += it }
        FactHost.fresh(fresh)
        val migrated = HostSqlite().also { hosts += it }
        FactHost.migrated(migrated, 5)
        val query = "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE type IN ('trigger', 'index') " +
            "ORDER BY type, name"
        val expected = fresh.query(query)
        assertTrue("the M-01 guards exist", expected.map { it[1] }.containsAll(GUARDS))
        assertEquals(expected, migrated.query(query))
    }

    private companion object {
        val GUARDS = listOf("c2_facts_delete_guard", "c2_fact_evidence_delete_guard", "c2_review_events_delete_guard")
    }
}
