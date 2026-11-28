package org.openlife.vault.repository

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openlife.vault.search.SearchFixture
import org.openlife.vault.storage.FactHost
import org.openlife.vault.storage.FactRows
import org.openlife.vault.storage.HostFactDao
import org.openlife.vault.storage.HostSqlite

/**
 * P3-05-R1 on host SQLite: the read queries the facts UI uses (C2-R34, C2-R39, C2-R41), run against the production
 * SQL. Ids are the fixture's strings, as in FactWriterTest.
 */
class FactReadHostTest {
    private val host = HostSqlite()
    private val rows = FactRows(host)
    private val dao = HostFactDao(host)

    @After
    fun close() = host.close()

    /** Source `a` with READY current reading `a-r`; fact `f` whose EXTRACTED `x` has VALUE evidence on `a-s1`. */
    private fun extracted(): SearchFixture {
        val fixture = FactHost.fresh(host)
        rows.readySourceWithReading(fixture, "a")
        rows.fact("f", "a")
        rows.revision("x", "f")
        rows.current("f", "x")
        rows.evidence("e1", "x", "a", "a-s1")
        return fixture
    }

    @Test
    fun evidenceOfARevisionNamesItsReadingAndSpan() = runBlocking {
        extracted()
        val evidence = dao.evidenceOfRevision("x")
        assertEquals(1, evidence.size)
        assertEquals("VALUE", evidence.single().role)
        assertEquals("a-r", evidence.single().ocrRevisionId)
        assertEquals("a-s1", evidence.single().ocrSpanId)
    }

    @Test
    fun latestDecisionFollowsTheHighestSequence() = runBlocking {
        extracted()
        assertNull(dao.latestDecision("x"))
        rows.reviewEvent("v1", "x", "CONFIRM")
        rows.reviewEvent("v2", "x", "REJECT")
        assertEquals("REJECT", dao.latestDecision("x"))
    }

    @Test
    fun extractionOutcomeIsOnlyReportedForTheCurrentReading() = runBlocking {
        val fixture = extracted()
        assertNull(dao.extractionOutcomeOfCurrentReading("a"))
        fixture.revision("a-old", "a")
        rows.run("run-old", "a", "a-old")
        assertNull(dao.extractionOutcomeOfCurrentReading("a"))
        rows.run("run-current", "a", "a-r")
        assertEquals("COMPLETED", dao.extractionOutcomeOfCurrentReading("a"))
    }
}
