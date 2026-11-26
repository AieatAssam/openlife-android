package org.openlife.vault.storage

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openlife.vault.search.SearchFixture
import org.openlife.vault.storage.HostSqlite.Companion.createFromExportedSchema

/**
 * P3-02-R1/R2/R3/R7 on host SQLite (3.53, not SQLCipher; the SQLCipher run
 * is FactDatabaseSqlCipherTest, a device gap): the C2 schema, its triggers
 * and the C2-R21 migration order. Every refusal is a trigger RAISE whose
 * message names the C2 requirement it enforces.
 */
class FactDatabaseTest {
    private val hosts = mutableListOf<HostSqlite>()

    private fun host(): HostSqlite = HostSqlite().also { hosts += it }

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

    @Test
    fun migrationCreatesFactTablesAndPreservesEverythingElse() {
        val fresh = host()
        FactHost.fresh(fresh)
        val expected = shape(fresh)
        assertTrue("the C2 tables exist", expected.keys.containsAll(C2_TABLES))
        (1..5).forEach { from ->
            val migrated = host()
            FactHost.migrated(migrated, from) { seed(from) }
            assertEquals("v$from -> v6 equals a fresh v6", expected, shape(migrated))
            assertEquals(1L, migrated.long("SELECT COUNT(*) FROM sources WHERE id = 'a'"))
            assertEquals(0L, migrated.long("SELECT factInputEpoch FROM sources WHERE id = 'a'"))
            if (from >= 2) {
                assertEquals(listOf(listOf<Any?>("a-s1", 1L, "LATIN"), listOf<Any?>("a-s2", 0L, "UNSUPPORTED")),
                    migrated.query("SELECT id, hasEvidenceRegion, scriptStatus FROM ocr_spans ORDER BY id"))
                assertEquals("the newest READY reading is current", "a-r2",
                    migrated.query("SELECT currentOcrRevisionId FROM sources WHERE id = 'a'").single().single())
                assertEquals("corrections are chained by (createdAt, rowid)",
                    listOf(listOf<Any?>("c1", null), listOf<Any?>("c2", "c1")),
                    migrated.query("SELECT id, supersedesId FROM ocr_user_revisions ORDER BY id"))
            }
            C2_TABLES.forEach { assertEquals(0L, migrated.long("SELECT COUNT(*) FROM $it")) }
        }
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6), OpenLifeDatabase.MIGRATIONS.map { it.startVersion to it.endVersion })
    }

    private fun SearchFixture.seed(from: Int) {
        host.exec("INSERT INTO sources(id, state, importedAt, intakeKind) VALUES ('a', 'STAGED', 1, 'SHARE')")
        if (from < 2) return
        revision("a-r1", "a")
        revision("a-r2", "a")
        revision("a-r3", "a", state = "FAILED")
        host.exec(
            "INSERT INTO ocr_spans(id, revisionId, ordinal, text, confidence, coordinateSystem, `left`, top, `right`, " +
                "bottom) VALUES ('a-s1', 'a-r2', 0, 'Due 26.11.2026', NULL, 'SOURCE_PIXELS', 1, 1, 5, 5), " +
                "('a-s2', 'a-r2', 1, 'Итого 5', NULL, 'SOURCE_PIXELS', NULL, NULL, NULL, NULL)",
        )
        correction("c1", "a-r2", "a-s1", "Due 27.11.2026")
        correction("c2", "a-r2", "a-s1", "Due 28.11.2026")
        if (from >= 3) review("a-s1", "ACCEPTED")
    }

    @Test
    fun extractedRevisionCannotBeConfirmedWithoutEvidence() {
        val host = host()
        val rows = FactRows(host)
        rows.readySourceWithReading(FactHost.fresh(host), "a")
        rows.fact("f", "a")
        refused("C2-R7") { rows.revision("x", "f", status = "CONFIRMED") }
        rows.revision("r1", "f")
        rows.current("f", "r1")
        refused("C2-R7") { host.exec("UPDATE fact_revisions SET status = 'CONFIRMED' WHERE id = 'r1'") }
        rows.evidence("e1", "r1", "a", "a-s1")
        host.exec("UPDATE fact_revisions SET status = 'CONFIRMED' WHERE id = 'r1'")
        assertEquals("CONFIRMED", host.query("SELECT status FROM fact_revisions WHERE id = 'r1'").single().single())
        refused("C2-R10") { rows.evidence("e2", "r1", "a", "a-s2") }
        refused("C2-R4") { host.exec("UPDATE fact_revisions SET valueJson = '{}' WHERE id = 'r1'") }
        refused("C2 §4.3") { host.exec("UPDATE fact_evidence SET textEnd = 2") }
        rows.reviewEvent("ev1", "r1")
        refused("C2-R12") { host.exec("UPDATE review_events SET decision = 'REJECT'") }
        refused("C2-R8") {
            host.exec(
                "INSERT INTO fact_evidence(id, revisionId, ordinal, role, sourceId, ocrRevisionId, ocrSpanId, " +
                    "ocrUserRevisionId, textStart, textEnd, regionLeft, regionTop, regionRight, regionBottom, " +
                    "regionCoordinateSystem) VALUES ('e3', 'r1', 5, 'CURRENCY_CONTEXT', 'a', 'a-r', 'a-s2', NULL, " +
                    "0, 1, 0, 0, 9, 9, 'SOURCE_PIXELS')",
            )
        }
    }

    @Test
    fun userRevisionCannotCarryEvidence() {
        val host = host()
        val rows = FactRows(host)
        rows.readySourceWithReading(FactHost.fresh(host), "a")
        rows.fact("f", "a")
        rows.revision("u1", "f", origin = "USER", status = "CONFIRMED")
        refused("C2-R7") { rows.evidence("e1", "u1", "a", "a-s1") }
        assertEquals(0L, host.long("SELECT COUNT(*) FROM fact_evidence"))
        refused("C2-R7") { rows.revision("d1", "f", origin = "DERIVED") }
        refused("C2-R7") {
            host.exec(
                "INSERT INTO fact_revisions(id, factId, revisionNo, origin, kind, valueJson, canonicalText, rawText, " +
                    "status, staleReason, staleAt, confidence, methodId, methodVersion, locale, createdAt, " +
                    "supersedesRevisionId) VALUES ('u2', 'f', 9, 'USER', 'DATE', '{}', 't', 'raw', 'CONFIRMED', NULL, " +
                    "NULL, NULL, 'user:add', NULL, NULL, 1, NULL)",
            )
        }
        refused("C2 §4.2") { rows.revision("k1", "f", kind = "MONEY") }
        refused("C2-R6") {
            host.exec(
                "INSERT INTO fact_revisions(id, factId, revisionNo, origin, kind, valueJson, canonicalText, rawText, " +
                    "status, staleReason, staleAt, confidence, methodId, methodVersion, locale, createdAt, " +
                    "supersedesRevisionId) VALUES ('c9', 'f', 9, 'EXTRACTED', 'DATE', '{}', 't', 'raw', " +
                    "'UNREVIEWED', NULL, NULL, 0.9, 'date:numeric', '1', 'en-GB', 1, NULL)",
            )
        }
        refused("C2 §4.2") { rows.revision("v1", "f", valueJson = "{\"x\":\"" + "y".repeat(4100) + "\"}") }
    }

    @Test
    fun pointerTargetCannotBeDeletedUnlessSourceIsDeleting() {
        val host = host()
        val rows = FactRows(host)
        val fixture = FactHost.fresh(host)
        rows.readySourceWithReading(fixture, "a")
        rows.fact("f", "a")
        rows.revision("r1", "f")
        rows.current("f", "r1")
        rows.correction("c1", "a-r", "a-s1", "Due 27.11.2026", null)
        rows.correction("c2", "a-r", "a-s1", "Due 28.11.2026", "c1")
        refused("C2-R20") { host.exec("DELETE FROM fact_revisions WHERE id = 'r1'") }
        refused("C2-R20") { host.exec("DELETE FROM ocr_revisions WHERE id = 'a-r'") }
        refused("C2-R20") { host.exec("DELETE FROM ocr_user_revisions WHERE id = 'c1'") }
        refused("C2-R3") { host.exec("UPDATE facts SET currentRevisionId = NULL WHERE id = 'f'") }
        refused("C2-R14") { host.exec("UPDATE sources SET currentOcrRevisionId = NULL WHERE id = 'a'") }
        rows.setState("a", "DELETING")
        listOf(
            SourceRowRemoval.DELETE_REVIEW_EVENTS, SourceRowRemoval.DELETE_FACT_EVIDENCE,
            SourceRowRemoval.DELETE_FACT_REVISIONS, SourceRowRemoval.DELETE_FACTS,
            SourceRowRemoval.DELETE_EXTRACTION_RUNS, SourceRowRemoval.DELETE_SPAN_REVIEWS,
            SourceRowRemoval.DELETE_CORRECTIONS, SourceRowRemoval.DELETE_SPANS, SourceRowRemoval.DELETE_READINGS,
        ).forEach { with(FactHost) { host.named(it, "sourceId" to "a") } }
        with(FactHost) { host.named(SourceRowRemoval.DELETE_SOURCE, "id" to "a") }
        assertEquals(0L, host.long("SELECT COUNT(*) FROM sources"))
    }

    @Test
    fun ftsUpdateTriggersAreNarrowedBeforeBackfill() {
        val shipped = host()
        val mutant = host()
        listOf(shipped, mutant).forEach(::v5WithShippedIndex)
        val before = ftsData(shipped)
        assertFtsDataEqual(before, ftsData(mutant))
        val db = shipped.asSupportDatabase()
        FactMigration.narrowFtsUpdateTriggers(db)
        FactMigration.addColumns(db)
        FactMigration.backfill(db)
        assertFtsDataEqual(before, ftsData(shipped))
        val mutantDb = mutant.asSupportDatabase()
        FactMigration.addColumns(mutantDb)
        FactMigration.backfill(mutantDb)
        FactMigration.narrowFtsUpdateTriggers(mutantDb)
        assertNotEquals("a backfill under the v5 triggers re-indexes every row", before.map { it.toList() },
            ftsData(mutant).map { it.toList() })
        FactMigration.createTables(db)
        FactSchemaTriggers.create(db)
        ReadyInvariantTriggers.create(db)
        SearchIndex.ensure(db)
        assertFtsDataEqual(before, ftsData(shipped))
        val updateTriggers = shipped.query("SELECT name, sql FROM sqlite_master WHERE name LIKE '%fts_au'")
        assertEquals(2, updateTriggers.size)
        assertTrue(updateTriggers.toString(), updateTriggers.all {
            val sql = it[1] as String
            sql.contains("AFTER UPDATE OF text ON ocr_spans") || sql.contains("AFTER UPDATE OF correctedText ON")
        })
        shipped.exec("UPDATE ocr_spans SET hasEvidenceRegion = 0")
        assertFtsDataEqual(before, ftsData(shipped))
        SearchFixture(shipped).integrityCheck(SearchIndex.TEXT_TABLE)
        SearchFixture(shipped).integrityCheck(SearchIndex.CORRECTION_TABLE)
    }

    /** A v5 vault as v5 shipped it: the index with ADR-0010's any-column update triggers. */
    private fun v5WithShippedIndex(host: HostSqlite) {
        host.createFromExportedSchema(FactHost.schema(4))
        val fixture = SearchFixture(host)
        fixture.source("a")
        fixture.revision("a-r", "a")
        fixture.span("a-s1", "a-r", 0, "lighthouse invoice")
        fixture.span("a-s2", "a-r", 1, "quarterly harbour")
        fixture.correction("c1", "a-r", "a-s1", "kestrel")
        fixture.correction("c2", "a-r", "a-s1", "meadow")
        OpenLifeDatabase.MIGRATION_4_5.migrate(host.asSupportDatabase())
        SearchFixture.assumeIndexed(host)
        V5_UPDATE_TRIGGERS.forEach(host::exec)
    }

    private fun ftsData(host: HostSqlite): List<ByteArray> = SearchIndex.tables.flatMap { table ->
        host.query("SELECT block FROM ${table}_data ORDER BY id").map { it.single() as ByteArray }
    }

    private fun assertFtsDataEqual(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertArrayEquals(e, a) }
    }

    /** Columns, indices, foreign keys and triggers of every table, as SQLite reports them. */
    private fun shape(host: HostSqlite): Map<String, List<Any>> {
        val tables = host.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE '%fts%' AND name <> 'room_master_table' ORDER BY name",
        ).map { it.single() as String }
        return tables.associateWith { table ->
            listOf(
                host.query("SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('$table')"),
                host.query(
                    "SELECT l.name, l.\"unique\", group_concat(i.name, ',') FROM pragma_index_list('$table') l, " +
                        "pragma_index_info(l.name) i GROUP BY l.name ORDER BY l.name",
                ),
                host.query(
                    "SELECT \"table\", \"from\", \"to\", on_delete FROM pragma_foreign_key_list('$table') " +
                        "ORDER BY \"from\"",
                ),
                host.query("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND tbl_name = '$table' ORDER BY name"),
            )
        }
    }

    private companion object {
        val C2_TABLES = listOf(
            "facts", "fact_revisions", "fact_evidence", "review_events", "fact_extraction_runs", "dependency_edges",
        )

        /** The update triggers exactly as ADR-0010 shipped them in v5 (fire on any column). */
        val V5_UPDATE_TRIGGERS = listOf(
            "DROP TRIGGER IF EXISTS ocr_text_fts_au",
            "CREATE TRIGGER ocr_text_fts_au AFTER UPDATE ON ocr_spans BEGIN " +
                "INSERT INTO ocr_text_fts(ocr_text_fts, rowid, text) VALUES ('delete', old.rowid, old.text); " +
                "INSERT INTO ocr_text_fts(rowid, text) VALUES (new.rowid, new.text); END",
            "DROP TRIGGER IF EXISTS ocr_correction_fts_au",
            "CREATE TRIGGER ocr_correction_fts_au AFTER UPDATE ON ocr_user_revisions BEGIN " +
                "INSERT INTO ocr_correction_fts(ocr_correction_fts, rowid, correctedText) " +
                "VALUES ('delete', old.rowid, old.correctedText); " +
                "INSERT INTO ocr_correction_fts(rowid, correctedText) VALUES (new.rowid, new.correctedText); END",
        )
    }
}
