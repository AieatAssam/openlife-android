package org.openlife.vault.search

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.repository.SearchRepository
import org.openlife.vault.storage.FactHost
import org.openlife.vault.storage.FactRows
import org.openlife.vault.storage.FactSearchIndex
import org.openlife.vault.storage.HostSourceRowRemovalDao
import org.openlife.vault.storage.HostSqlite
import org.openlife.vault.storage.HostSqlite.Failure
import org.openlife.vault.storage.SourceRowRemoval
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * P3-07-R1 on host SQLite: fact_fts is intact on a fresh database and after a v6
 * migration; a deleted Source leaves no fact text in the index (secure-delete); a
 * REJECTED fact is filtered out at query time while its text stays indexed (C2-R49).
 */
class FactSearchHostTest {
    private companion object {
        const val SOURCE = "a0000000-0000-0000-0000-000000000001"
        const val REVISION = "b0000000-0000-0000-0000-000000000001"
        const val SPAN = "c0000000-0000-0000-0000-000000000001"
        const val FACT = "d0000000-0000-0000-0000-000000000001"
        const val FACT_REVISION = "e0000000-0000-0000-0000-000000000001"
        const val EVIDENCE = "f0000000-0000-0000-0000-000000000001"
        const val NEWER_FACT_REVISION = "e0000000-0000-0000-0000-000000000002"
        const val NEWER_EVIDENCE = "f0000000-0000-0000-0000-000000000002"
    }

    private val host = HostSqlite()
    private val rows = FactRows(host)

    @After
    fun close() = host.close()

    private fun factHits(query: String): List<SearchHit> = runBlocking {
        SearchRepository(HostSearchDao(host), Dispatchers.Unconfined).search(query).first()
    }.filter { it.fact != null }

    /** One READY Source with a current reading, and one EXTRACTED fact whose VALUE names it. */
    private fun seedFact(fixture: SearchFixture) {
        fixture.source(SOURCE)
        fixture.revision(REVISION, SOURCE)
        fixture.span(SPAN, REVISION, 0, "Due 2026-11-26")
        rows.fact(FACT, SOURCE)
        rows.revision(FACT_REVISION, FACT, origin = "EXTRACTED", status = "UNREVIEWED")
        rows.evidence(EVIDENCE, FACT_REVISION, SOURCE, SPAN)
        rows.current(FACT, FACT_REVISION)
        // C2-R14: the Source's current reading, moved through the one pointer routine.
        assertEquals(1, rows.moveCurrentReading(SOURCE, REVISION))
    }

    @Test
    fun freshDatabaseHasAnIntactFactIndex() {
        val fixture = FactHost.fresh(host)
        fixture.integrityCheck(FactSearchIndex.TABLE)
    }

    @Test
    fun migratedFromV6HasAnIntactFactIndex() {
        val fixture = FactHost.migrated(host, from = 6) {
            source(SOURCE)
            revision(REVISION, SOURCE)
        }
        fixture.integrityCheck(FactSearchIndex.TABLE)
    }

    @Test
    fun deletedSourceLeavesNoFactTextAndTheIndexStaysIntact() {
        val fixture = FactHost.fresh(host)
        seedFact(fixture)
        assertTrue("positive control: the fact text is indexed", fixture.dataContains(FactSearchIndex.TABLE, "2026"))
        assertEquals(1, factHits("2026-11-26").size)
        rows.setState(SOURCE, "DELETING")
        val dao = HostSourceRowRemovalDao(host)
        runBlocking { SourceRowRemoval.remove(dao, SOURCE, dao::deleteSource) }
        assertFalse(fixture.dataContains(FactSearchIndex.TABLE, "2026"))
        fixture.integrityCheck(FactSearchIndex.TABLE)
        assertEquals(emptyList<SearchHit>(), factHits("2026-11-26"))
    }

    @Test
    fun rejectedFactIsNotShownButItsTextStaysIndexedForQueryTimeFiltering() {
        val fixture = FactHost.fresh(host)
        seedFact(fixture)
        host.exec("UPDATE fact_revisions SET status = 'REJECTED' WHERE id = '$FACT_REVISION'")
        assertEquals(emptyList<SearchHit>(), factHits("2026-11-26"))
        assertTrue(fixture.dataContains(FactSearchIndex.TABLE, "2026"))
        fixture.integrityCheck(FactSearchIndex.TABLE)
    }

    @Test
    fun supersededRevisionIsNotShownButItsTextStaysIndexedForQueryTimeFiltering() {
        val fixture = FactHost.fresh(host)
        seedFact(fixture)
        // FTS indexes rawText as well as canonicalText, so the newer raw text must not contain 2026.
        rows.revision(
            NEWER_FACT_REVISION,
            FACT,
            supersedes = FACT_REVISION,
            canonicalText = "2027-01-05",
            rawText = "05.01.2027",
        )
        rows.evidence(NEWER_EVIDENCE, NEWER_FACT_REVISION, SOURCE, SPAN)
        assertEquals(1, rows.current(FACT, NEWER_FACT_REVISION, expected = FACT_REVISION))
        assertEquals(emptyList<SearchHit>(), factHits("2026-11-26"))
        val shown = factHits("2027-01-05")
        assertEquals(listOf(NEWER_FACT_REVISION), shown.map { it.fact?.revisionId?.toString() })
        assertTrue(fixture.dataContains(FactSearchIndex.TABLE, "2026"))
        fixture.integrityCheck(FactSearchIndex.TABLE)
    }

    /**
     * C2-13 lists UNRECOGNISED. The C2 triggers reject an unknown stored status (FactSchemaTriggers,
     * on insert and on update), so no UNRECOGNISED revision can reach the search filter.
     */
    @Test
    fun unrecognisedStatusIsRejectedByTheSchemaSoNoSuchRevisionIsSearchable() {
        val fixture = FactHost.fresh(host)
        seedFact(fixture)
        assertThrows(Failure::class.java) {
            host.exec("UPDATE fact_revisions SET status = 'UNRECOGNISED' WHERE id = '$FACT_REVISION'")
        }
        assertEquals(1, factHits("2026-11-26").size)
        fixture.integrityCheck(FactSearchIndex.TABLE)
    }

    /**
     * [host] as a SupportSQLiteDatabase whose `SELECT sqlite_version()` reports [version]
     * (as SearchIndexEngineGateTest).
     */
    private fun reporting(version: String): SupportSQLiteDatabase {
        val real = host.asSupportDatabase()
        val literal = "SELECT '$version'"
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            val sql = args?.singleOrNull() as? String
            val asksVersion = method.name == "query" && sql?.contains("sqlite_version()", ignoreCase = true) == true
            try {
                if (asksVersion) real.query(literal) else method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as SupportSQLiteDatabase
    }

    private fun factIndexCount(): Long = host.long("SELECT COUNT(*) FROM sqlite_master WHERE name = 'fact_fts'")

    @Test
    fun updatingRawTextIsRefusedByTheImmutabilityTrigger() {
        val fixture = FactHost.fresh(host)
        seedFact(fixture)
        assertThrows(Failure::class.java) {
            host.exec("UPDATE fact_revisions SET rawText = 'changed' WHERE id = '$FACT_REVISION'")
        }
        assertTrue(fixture.dataContains(FactSearchIndex.TABLE, "2026"))
    }

    @Test
    fun factIndexPassesIntegrityCheckWithRankOneAfterSourceRowRemoval() {
        val fixture = FactHost.fresh(host)
        seedFact(fixture)
        rows.setState(SOURCE, "DELETING")
        val dao = HostSourceRowRemovalDao(host)
        runBlocking { SourceRowRemoval.remove(dao, SOURCE, dao::deleteSource) }
        fixture.integrityCheck(FactSearchIndex.TABLE)
        host.exec("INSERT INTO ${FactSearchIndex.TABLE}(${FactSearchIndex.TABLE}, rank) VALUES('integrity-check', 1)")
    }

    @Test
    fun createOnAnEngineBelowTheFloorReturnsFalseAndLeavesNoFactIndex() {
        val fixture = FactHost.fresh(host)
        seedFact(fixture)
        assertEquals(1L, factIndexCount())
        assertFalse(FactSearchIndex.create(reporting("3.46.0")))
        assertEquals(0L, factIndexCount())
    }

    @Test
    fun ensureOnAnEngineBelowTheFloorDoesNotKeepAnIntactIndex() {
        val fixture = FactHost.fresh(host)
        seedFact(fixture)
        assertFalse(FactSearchIndex.ensure(reporting("3.46.0")))
        assertEquals(0L, factIndexCount())
    }

    @Test
    fun likeFallbackFindsARawTextOnlyMatch() {
        val fixture = FactHost.fresh(host)
        fixture.source(SOURCE)
        fixture.revision(REVISION, SOURCE)
        fixture.span(SPAN, REVISION, 0, "Due 14 March 2026")
        rows.fact(FACT, SOURCE)
        rows.revision(FACT_REVISION, FACT, canonicalText = "2026-03-14", rawText = "14 March 2026")
        rows.evidence(EVIDENCE, FACT_REVISION, SOURCE, SPAN)
        rows.current(FACT, FACT_REVISION)
        assertEquals(1, rows.moveCurrentReading(SOURCE, REVISION))
        // Force the LIKE path: without fact_fts the repository does not use FTS for facts.
        host.exec("DROP TRIGGER fact_fts_ai")
        host.exec("DROP TRIGGER fact_fts_ad")
        host.exec("DROP TABLE fact_fts")
        assertEquals(listOf(FACT_REVISION), factHits("March").map { it.fact?.revisionId.toString() })
        assertEquals(emptyList<SearchHit>(), factHits("2027"))
    }
}
