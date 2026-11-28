package org.openlife.vault.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.repository.SearchRepository
import org.openlife.vault.storage.FactHost
import org.openlife.vault.storage.HostSourceRowRemovalDao
import org.openlife.vault.storage.HostSqlite
import org.openlife.vault.storage.HostSqlite.Companion.createFromExportedSchema
import org.openlife.vault.storage.SearchIndex
import org.openlife.vault.storage.SourceRowRemoval
import java.io.File
import java.util.UUID

/**
 * P2-08-R2/R3/R5/R6/R8 executed on a real SQLite with FTS5 (the host's,
 * through [HostSqlite]): the shipped MIGRATION_4_5, the index triggers, the
 * cascades and the repository's own queries. Not SQLCipher and not a device;
 * the same names run on SQLCipher in the instrumented SearchRepositoryTest.
 * Tests that need the index skip on a host SQLite older than
 * SearchIndex.MIN_SQLITE_VERSION (3.46.1: no FTS5 secure-delete before 3.42,
 * a corrupting secure-delete from 3.42.0 to 3.46.0; see
 * [SearchFixture.assumeIndexed]).
 */
class SearchIndexHostTest {
    private val host = HostSqlite()

    @After
    fun tearDown() = host.close()

    private fun repository() = SearchRepository(HostSearchDao(host), Dispatchers.Unconfined)

    private fun search(query: String): List<SearchHit> = runBlocking { repository().search(query).first() }

    @Test
    fun migrationIndexesRowsThatExistedBeforeIt() {
        val fixture = SearchFixture.migrated(host) {
            source(A)
            revision(RA, A)
            span(S1, RA, 0, "Invoice 4471 from the plumber")
            correction(C1, RA, S1, "Invoice 4471 from the electrician")
        }

        assertEquals(listOf(id(S1)), search("plumber").map { it.spanId })
        val corrected = search("electrician").single()
        assertTrue(corrected.isCorrection)
        assertEquals(id(S1), corrected.spanId)
        fixture.integrityCheckAll()
    }

    @Test
    fun freshAndMigratedDatabasesHaveTheSameIndexSchema() {
        SearchFixture.migrated(host)
        val fresh = HostSqlite()
        try {
            fresh.createFromExportedSchema(SearchFixture.V5)
            assertTrue(SearchIndex.create(fresh.asSupportDatabase()))
            assertEquals(indexSchema(host), indexSchema(fresh))
            assertTrue(indexSchema(fresh).any { it.contains("fts5") && it.contains(SearchIndex.TOKENIZER) })
            SearchIndex.tables.forEach { table ->
                assertEquals("$table secure-delete after migration", 1L, SearchFixture(host).secureDelete(table))
                assertEquals("$table secure-delete on a fresh vault", 1L, SearchFixture(fresh).secureDelete(table))
            }
        } finally {
            fresh.close()
        }
    }

    @Test
    fun findsSpanTextAndCorrectionsRankedAndLinked() {
        SearchFixture.migrated(host) {
            source(A)
            revision(RA, A)
            span(S1, RA, 0, "Dentist")
            span(S2, RA, 1, "Pay at reception before you see the dentist on the second floor of the building")
            span(S3, RA, 2, "Café Lumière receipt")
            correction(C1, RA, S2, "Dentist bill paid at reception")
        }

        val hits = search("dentist")
        assertEquals("span and correction both found", 3, hits.size)
        hits.forEach {
            assertEquals(id(A), it.sourceId)
            assertEquals(id(RA), it.revisionId)
        }
        assertEquals("the short exact span ranks first (bm25)", id(S1), hits.first().spanId)
        val correction = hits.single { it.isCorrection }
        assertEquals("a correction links to the span it corrects", id(S2), correction.spanId)
        assertEquals("Dentist bill paid at reception", correction.snippet)
        assertEquals(listOf(0..6), correction.highlights)

        val prefixHits = search("recep").filterNot { it.isCorrection }.map { it.spanId }
        assertEquals("prefix of the last word", listOf(id(S2)), prefixHits)
        assertEquals("diacritics fold both ways", listOf(id(S3)), search("cafe lumiere").map { it.spanId })
        assertEquals(listOf(id(S3)), search("CAFÉ").map { it.spanId })
        assertEquals("all words must match", emptyList<SearchHit>(), search("dentist lumiere"))
    }

    @Test
    fun excludesRejectedStaleAndNonReadySources() {
        SearchFixture.migrated(host) {
            // Revision rejected by the user.
            source("10000000-0000-0000-0000-000000000001")
            revision(
                "20000000-0000-0000-0000-000000000001",
                "10000000-0000-0000-0000-000000000001",
                reviewState = "REJECTED",
            )
            span("30000000-0000-0000-0000-000000000001", "20000000-0000-0000-0000-000000000001", 0, "walrus rejected")
            // Latest revision STALE: C2-R14 keeps the current reading on a READY one (the pointer),
            // so the older READY reading is the current one and its word is found (see the report).
            source("10000000-0000-0000-0000-000000000002")
            revision("20000000-0000-0000-0000-000000000002", "10000000-0000-0000-0000-000000000002")
            span("30000000-0000-0000-0000-000000000002", "20000000-0000-0000-0000-000000000002", 0, "walrus stale")
            revision("20000000-0000-0000-0000-000000000003", "10000000-0000-0000-0000-000000000002", state = "STALE")
            // Source not READY.
            source("10000000-0000-0000-0000-000000000003", state = "CORRUPT")
            revision("20000000-0000-0000-0000-000000000004", "10000000-0000-0000-0000-000000000003")
            span("30000000-0000-0000-0000-000000000004", "20000000-0000-0000-0000-000000000004", 0, "walrus corrupt")
            // Older READY revision superseded by a newer READY one: the viewer shows only the newer.
            source("10000000-0000-0000-0000-000000000004")
            revision("20000000-0000-0000-0000-000000000005", "10000000-0000-0000-0000-000000000004")
            span("30000000-0000-0000-0000-000000000005", "20000000-0000-0000-0000-000000000005", 0, "walrus old run")
            revision("20000000-0000-0000-0000-000000000006", "10000000-0000-0000-0000-000000000004")
            span("30000000-0000-0000-0000-000000000006", "20000000-0000-0000-0000-000000000006", 0, "walrus new run")
            // A rejected span, and a span whose rejection was undone.
            span(
                "30000000-0000-0000-0000-000000000007",
                "20000000-0000-0000-0000-000000000006",
                1,
                "walrus span rejected",
            )
            review("30000000-0000-0000-0000-000000000007", "REJECTED")
            span(
                "30000000-0000-0000-0000-000000000008",
                "20000000-0000-0000-0000-000000000006",
                2,
                "walrus span restored",
            )
            review("30000000-0000-0000-0000-000000000008", "REJECTED")
            review("30000000-0000-0000-0000-000000000008", "UNREVIEWED")
            // A superseded correction no longer matches; the latest one does.
            correction(
                "40000000-0000-0000-0000-000000000001",
                "20000000-0000-0000-0000-000000000006",
                "30000000-0000-0000-0000-000000000006",
                "zebra",
            )
            correction(
                "40000000-0000-0000-0000-000000000002",
                "20000000-0000-0000-0000-000000000006",
                "30000000-0000-0000-0000-000000000006",
                "yak",
            )
        }

        assertEquals(
            setOf(
                "30000000-0000-0000-0000-000000000002",
                "30000000-0000-0000-0000-000000000006",
                "30000000-0000-0000-0000-000000000008",
            ),
            search("walrus").map { it.spanId.toString() }.toSet(),
        )
        assertEquals(emptyList<SearchHit>(), search("zebra"))
        assertEquals(1, search("yak").size)
    }

    @Test
    fun deletingASourceRemovesItsHits() {
        val fixture = SearchFixture.migrated(host) {
            source(A)
            revision(RA, A)
            span(S1, RA, 0, "ferry kumquat")
            correction(C1, RA, S1, "ferry kumquat jicama")
            source(B)
            revision(RB, B)
            span(S2, RB, 0, "ferry walnut")
        }
        assertEquals(3, search("ferry").size)
        assertTrue("positive control", fixture.dataContains(SearchIndex.TEXT_TABLE, "kumquat"))
        assertTrue("positive control", fixture.dataContains(SearchIndex.CORRECTION_TABLE, "jicama"))

        host.exec("DELETE FROM sources WHERE id = ?", A)

        assertEquals(listOf(id(S2)), search("ferry").map { it.spanId })
        assertEquals(emptyList<SearchHit>(), search("jicama"))
        // W-01: secure-delete removes the deleted source's terms from the live segments.
        assertFalse(fixture.dataContains(SearchIndex.TEXT_TABLE, "kumquat"))
        assertFalse(fixture.dataContains(SearchIndex.CORRECTION_TABLE, "kumquat"))
        assertFalse(fixture.dataContains(SearchIndex.CORRECTION_TABLE, "jicama"))
        assertTrue("the other source stays indexed", fixture.dataContains(SearchIndex.TEXT_TABLE, "walnut"))
        assertEquals(1L, fixture.indexedDocuments(SearchIndex.TEXT_TABLE))
        assertEquals(0L, fixture.indexedDocuments(SearchIndex.CORRECTION_TABLE))
        fixture.integrityCheck(SearchIndex.TEXT_TABLE)
        fixture.integrityCheck(SearchIndex.CORRECTION_TABLE)
    }

    /** VS-2: only the ON DELETE CASCADE from `sources` runs; no statement names an FTS table. */
    @Test
    fun cascadeOnlyDeletionLeavesNoFtsRows() {
        val fixture = SearchFixture.migrated(host) {
            source(A)
            revision(RA, A)
            span(S1, RA, 0, "lighthouse keeper")
            span(S2, RA, 1, "lighthouse lamp")
            correction(C1, RA, S1, "lighthouse keeper corrected")
            correction(C2, RA, null, "whole revision lighthouse")
            review(S2, "REJECTED")
        }
        assertEquals(2L, fixture.indexedDocuments(SearchIndex.TEXT_TABLE))
        assertEquals(2L, fixture.indexedDocuments(SearchIndex.CORRECTION_TABLE))
        val terms = listOf("lighthouse", "keeper", "corrected", "whole")
        assertTrue("positive control", fixture.dataContains(SearchIndex.TEXT_TABLE, "lighthouse"))
        assertTrue("positive control", fixture.dataContains(SearchIndex.CORRECTION_TABLE, "whole"))

        host.exec("DELETE FROM sources")

        SearchIndex.tables.forEach { table ->
            terms.forEach { assertFalse("$it left in ${table}_data", fixture.dataContains(table, it)) }
        }
        assertEquals(0L, fixture.indexedDocuments(SearchIndex.TEXT_TABLE))
        assertEquals(0L, fixture.indexedDocuments(SearchIndex.CORRECTION_TABLE))
        fixture.integrityCheck(SearchIndex.TEXT_TABLE)
        fixture.integrityCheck(SearchIndex.CORRECTION_TABLE)
        assertEquals(emptyList<SearchHit>(), search("lighthouse"))
    }

    @Test
    fun updatesAndRevisionDeletionKeepTheIndexConsistent() {
        val fixture = SearchFixture.migrated(host) {
            source(A)
            revision(RA, A)
            span(S1, RA, 0, "orchard")
            correction(C1, RA, S1, "meadow")
        }
        assertTrue("positive control", fixture.dataContains(SearchIndex.TEXT_TABLE, "orchard"))
        assertTrue("positive control", fixture.dataContains(SearchIndex.CORRECTION_TABLE, "meadow"))
        host.exec("UPDATE ocr_spans SET text = 'harbour' WHERE id = ?", S1)
        host.exec("UPDATE ocr_user_revisions SET correctedText = 'valley' WHERE id = ?", C1)
        assertFalse("W-01: old text leaves _data", fixture.dataContains(SearchIndex.TEXT_TABLE, "orchard"))
        assertFalse(fixture.dataContains(SearchIndex.CORRECTION_TABLE, "meadow"))
        assertTrue(fixture.dataContains(SearchIndex.CORRECTION_TABLE, "valley"))
        assertEquals(emptyList<SearchHit>(), search("orchard"))
        assertEquals(emptyList<SearchHit>(), search("meadow"))
        assertEquals(1, search("harbour").size)
        assertEquals(1, search("valley").size)
        fixture.integrityCheck(SearchIndex.TEXT_TABLE)
        fixture.integrityCheck(SearchIndex.CORRECTION_TABLE)

        // C2-R20: the current reading goes only with its Source, through the one removal routine.
        host.exec("UPDATE sources SET state = 'DELETING' WHERE id = ?", A)
        val dao = HostSourceRowRemovalDao(host)
        runBlocking { SourceRowRemoval.remove(dao, A, dao::deleteSource) }
        assertEquals(0L, fixture.indexedDocuments(SearchIndex.TEXT_TABLE))
        assertEquals(0L, fixture.indexedDocuments(SearchIndex.CORRECTION_TABLE))
        assertFalse(fixture.dataContains(SearchIndex.TEXT_TABLE, "harbour"))
        assertFalse(fixture.dataContains(SearchIndex.CORRECTION_TABLE, "valley"))
        fixture.integrityCheckAll()
    }

    /**
     * PR #36: every index trigger (insert, narrowed update, column-only
     * update, delete, cascade) on spans and on corrections leaves an index
     * that passes FTS5 integrity-check after each step, on a v5 vault (index
     * from MIGRATION_4_5), a fresh v6 vault and a v5 vault migrated to v6. On
     * SQLite 3.42.0 to 3.46.0 the first delete fails this; SearchIndex
     * creates no index there.
     */
    @Test
    fun everyTriggerPathLeavesAnIndexThatPassesIntegrityCheck() {
        val seed: SearchFixture.() -> Unit = {
            source(A)
            revision(RA, A)
            span(S1, RA, 0, "apricot")
        }
        triggerPaths("v5", SearchFixture.migrated(host, seed))
        HostSqlite().use { v6 ->
            val fixture = FactHost.fresh(v6).apply(seed)
            assertTrue(SearchIndex.isComplete(v6.asSupportDatabase()))
            triggerPaths("fresh v6", fixture, v6)
        }
        HostSqlite().use { migrated ->
            val fixture = SearchFixture.migrated(migrated, seed)
            assertTrue(SearchIndex.isComplete(migrated.asSupportDatabase()))
            triggerPaths("migrated to v7", fixture, migrated)
        }
    }

    private fun triggerPaths(label: String, fixture: SearchFixture, db: HostSqlite = host) {
        val steps = listOf(
            "insert span" to { fixture.span(S2, RA, 1, "bramble") },
            "insert correction" to { fixture.correction(C1, RA, S1, "currant") },
            "insert revision-level correction" to { fixture.correction(C2, RA, null, "damson") },
            "update span text" to { db.exec("UPDATE ocr_spans SET text = 'elderberry' WHERE id = ?", S1) },
            "update correction text" to {
                db.exec("UPDATE ocr_user_revisions SET correctedText = 'fig' WHERE id = ?", C1)
            },
            "update span to the same text" to { db.exec("UPDATE ocr_spans SET text = text WHERE id = ?", S2) },
            "column-only span update" to { db.exec("UPDATE ocr_spans SET confidence = 0.5 WHERE id = ?", S2) },
            "delete one correction" to { db.exec("DELETE FROM ocr_user_revisions WHERE id = ?", C2) },
            "delete one span" to { db.exec("DELETE FROM ocr_spans WHERE id = ?", S2) },
            // v6 (C2-R20): a reading is deleted only while its Source is DELETING.
            "mark the Source deleting" to { db.exec("UPDATE sources SET state = 'DELETING' WHERE id = ?", A) },
            "cascade from the revision" to { db.exec("DELETE FROM ocr_revisions WHERE id = ?", RA) },
        )
        steps.forEach { (step, run) ->
            run()
            SearchIndex.tables.forEach { table ->
                val failure = runCatching { fixture.integrityCheck(table) }.exceptionOrNull()
                assertEquals("$label: $table after '$step'", null, failure?.message)
            }
        }
        assertEquals("$label: no text rows left", 0L, fixture.indexedDocuments(SearchIndex.TEXT_TABLE))
        assertEquals("$label: no correction rows left", 0L, fixture.indexedDocuments(SearchIndex.CORRECTION_TABLE))
    }

    /** Review: the option is stored in the database and holds after a reopen; [SearchIndex.ensure] then only reads. */
    @Test
    fun secureDeletePersistsAcrossReopenAndEnsureLeavesACompleteIndexAlone() {
        val file = File.createTempFile("search-index", ".db").apply { deleteOnExit() }
        try {
            HostSqlite(file).use { first -> SearchFixture.migrated(first) }
            HostSqlite(file).use { reopened ->
                val fixture = SearchFixture(reopened)
                SearchIndex.tables.forEach { assertEquals(1L, fixture.secureDelete(it)) }
                val executed = mutableListOf<String>()
                assertTrue(SearchIndex.ensure(reopened.asSupportDatabase(executed)))
                assertTrue("only reads: $executed", executed.all { it.startsWith("SELECT") })
            }
        } finally {
            file.delete()
        }
    }

    /** Review: at each open, an incomplete index or one without secure-delete is rebuilt over existing rows. */
    @Test
    fun ensureRecreatesAnIncompleteIndexAtOpen() {
        val fixture = SearchFixture.migrated(host) {
            source(A)
            revision(RA, A)
            span(S1, RA, 0, "quince")
        }
        host.exec("INSERT INTO ocr_text_fts(ocr_text_fts, rank) VALUES('secure-delete', 0)")
        host.exec("DROP TRIGGER ocr_correction_fts_ad")
        assertFalse(SearchIndex.isComplete(host.asSupportDatabase()))

        assertTrue(SearchIndex.ensure(host.asSupportDatabase()))

        assertTrue(SearchIndex.isComplete(host.asSupportDatabase()))
        assertEquals(1L, fixture.secureDelete(SearchIndex.TEXT_TABLE))
        assertEquals(listOf(id(S1)), search("quince").map { it.spanId })

        host.exec("DROP TABLE ocr_text_fts")
        host.exec("DROP TABLE ocr_correction_fts")
        assertTrue("a vault whose index was lost gets it back", SearchIndex.ensure(host.asSupportDatabase()))
        assertEquals(listOf(id(S1)), search("quince").map { it.spanId })
        fixture.integrityCheckAll()
    }

    /** W-01 fail closed: if any index statement fails, no part of the index is left behind. Runs on any host SQLite. */
    @Test
    fun aFailedCreateLeavesNoPartialIndex() {
        host.createFromExportedSchema(SearchFixture.V4)
        // The correction triggers cannot be created without their table.
        host.exec("DROP TABLE ocr_user_revisions")

        assertFalse(SearchIndex.create(host.asSupportDatabase()))

        assertEquals(0L, host.long("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE '%fts%'"))
        assertEquals(0L, host.long("SELECT COUNT(*) FROM sqlite_temp_master WHERE name LIKE '%fts%'"))
    }

    /**
     * W-03: the sanitised expression is run directly on the engine, with no
     * catch, so an FTS5 syntax error fails the test; and every row it matches
     * must contain every typed word, checked against the stored text.
     */
    @Test
    fun queryIsSanitisedAgainstFts5Syntax() {
        SearchFixture.migrated(host) {
            source(A)
            revision(RA, A)
            span(S1, RA, 0, "parcel collection slip")
            span(S2, RA, 1, "say \"hello\" * star")
            span(S3, RA, 2, "zebra crossing")
        }
        val raw = runCatching { host.query(MATCH_SQL, "NEAR(") }
        assertTrue("raw NEAR( must be an FTS5 syntax error on this engine", raw.isFailure)
        // Non-vacuous: as raw syntax this widens to a row without "parcel"; sanitised it must not.
        assertEquals(2, host.query(MATCH_SQL, "parcel OR zebra").size)
        assertEquals(emptyList<SearchHit>(), search("parcel OR zebra"))

        val hostile = listOf(
            "\"", "\"\"", "*", "NEAR(", "NEAR(parcel slip)", "OR", "parcel OR", "OR parcel", "NOT parcel",
            "parcel NOT slip", "-parcel", "text:parcel", "{text}: parcel", "^parcel", "parcel AND", "(", ")",
            "parcel\"*", "\"parcel", "pa*rcel", "parcel + slip", "'; DROP TABLE sources; --", "\\", "%", "_",
            "\u0000", "parcel\nslip", "\u200Bparcel", "parcel OR zebra", "zebra NOT parcel", "parcel* zebra",
        )
        var matchedSomething = false
        hostile.forEach { input ->
            val tokens = SearchQuery.tokens(input)
            val expression = SearchQuery.matchExpression(tokens) ?: return@forEach
            val rowids = host.query(MATCH_SQL, expression).map { it.single() as Long }
            rowids.forEach { rowid ->
                matchedSomething = true
                val text = host.query("SELECT text FROM ocr_spans WHERE rowid = ?", rowid).single().single() as String
                val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                tokens.forEachIndexed { index, token ->
                    val word = token.lowercase()
                    val found = if (index == tokens.lastIndex) words.any { it.startsWith(word) } else word in words
                    assertTrue("'$input' widened to a row without '$token': $text", found)
                }
            }
        }
        assertTrue("the hostile list must also match some rows", matchedSomething)
        assertEquals("OR is a word, not an operator", emptyList<SearchHit>(), search("parcel OR"))
        assertEquals("NOT is a word, not an operator", emptyList<SearchHit>(), search("NOT parcel"))
        assertEquals("quotes are not syntax", listOf(id(S2)), search("\"hello\"").map { it.spanId })
        assertEquals("punctuation-only input searches nothing", emptyList<SearchHit>(), search("* \" ( )"))
        assertEquals(1L, host.long("SELECT COUNT(*) FROM sources"))
    }

    /** W-02: text the word index cannot split as typed is found as a substring, through the same API. */
    @Test
    fun scriptsWithoutWordSpacingAndNumberFormsAreFound() {
        SearchFixture.migrated(host) {
            source(A)
            revision(RA, A)
            span(S1, RA, 0, "東京都の請求書")
            span(S2, RA, 1, "ใบเสร็จรับเงิน")
            span(S3, RA, 2, "हिन्दी में पत्र")
            span(S4, RA, 3, "Chapter Ⅻ total x² ½ off")
        }
        assertEquals(listOf(id(S1)), search("請求書").map { it.spanId })
        assertEquals(listOf(0..1), search("東京").single().highlights)
        assertEquals(listOf(id(S2)), search("รับเงิน").map { it.spanId })
        assertEquals(listOf(id(S3)), search("पत्र").map { it.spanId })
        assertEquals(listOf(id(S4)), search("Ⅻ").map { it.spanId })
        assertEquals(listOf(id(S4)), search("x²").map { it.spanId })
        assertEquals(listOf(id(S4)), search("½").map { it.spanId })
    }

    @Test
    fun likeFallbackHasTheSameApiWhenFts5IsAbsent() {
        host.createFromExportedSchema(SearchFixture.V5)
        SearchFixture(host).apply {
            source(A)
            revision(RA, A)
            span(S1, RA, 0, "Dentist appointment")
            span(S2, RA, 1, "Pay 50% at reception_desk")
            span(S4, RA, 2, "Ärzte Praxis")
            correction(C1, RA, S1, "Dentist appointment moved")
            source(B, state = "STAGED")
            revision(RB, B)
            span(S3, RB, 0, "Dentist staged")
        }
        SearchFixture(host).migrateToV6WithoutFts5()
        assertEquals(0L, host.long("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE '%fts%'"))

        val hits = search("dentist appoint")
        assertEquals(setOf(false to id(S1), true to id(S1)), hits.map { it.isCorrection to it.spanId }.toSet())
        assertEquals("LIKE wildcards never reach the pattern", emptyList<SearchHit>(), search("% _ %_"))
        assertEquals(listOf(id(S2)), search("50").map { it.spanId })
        // W-05: the pattern is lowercased as SQLite's lower() lowercases the column (ASCII only).
        assertEquals("typed exactly as stored", listOf(id(S4)), search("Ärzte").map { it.spanId })
        assertEquals(listOf(id(S4)), search("ÄRZTE praxis").map { it.spanId })
        // Disclosed limit (ADR-0010): no non-ASCII case folding on the fallback.
        assertEquals(emptyList<SearchHit>(), search("ärzte"))
    }

    /** Acceptance asks < 200 ms for 500 spans on a device; this is the host engine only (recorded). */
    @Test
    fun fiveHundredSpansOnTheHostEngine() {
        val fixture = SearchFixture.migrated(host) {
            source(A)
            revision(RA, A)
            repeat(SPANS) { i ->
                span(UUID(3L, i.toLong()).toString(), RA, i, "line $i of a synthetic receipt total ${i * 7} item w$i")
            }
        }
        search("synthetic")
        val started = System.nanoTime()
        val hits = search("receipt total")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("P2-08 host-engine search latency: $elapsedMs ms for $SPANS spans, ${hits.size} hits (limit 50)")
        assertEquals(SearchRepository.DEFAULT_LIMIT, hits.size)
        assertTrue("host search took $elapsedMs ms", elapsedMs < HOST_BUDGET_MS)
        fixture.integrityCheckAll()
    }

    /** P2-08-R5: the LIKE fallback's latency on the same 500 spans, with no index (host engine only). */
    @Test
    fun fiveHundredSpansOnTheLikeFallback() {
        host.createFromExportedSchema(SearchFixture.V5)
        SearchFixture(host).apply {
            source(A)
            revision(RA, A)
            repeat(SPANS) { i ->
                span(UUID(3L, i.toLong()).toString(), RA, i, "line $i of a synthetic receipt total ${i * 7} item w$i")
            }
        }
        SearchFixture(host).migrateToV6WithoutFts5()
        search("synthetic")
        val started = System.nanoTime()
        val hits = search("receipt total")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("P2-08 host-engine LIKE fallback latency: $elapsedMs ms for $SPANS spans, ${hits.size} hits (limit 50)")
        assertEquals(SearchRepository.DEFAULT_LIMIT, hits.size)
        assertTrue("host fallback search took $elapsedMs ms", elapsedMs < HOST_BUDGET_MS)
    }

    private fun indexSchema(db: HostSqlite): List<String> = db.query(
        "SELECT type || ' ' || name || ' ' || COALESCE(sql, '') FROM sqlite_master " +
            "WHERE name LIKE '%fts%' AND name NOT LIKE 'fact%' ORDER BY type, name",
    ).map { it.single() as String }

    private fun id(value: String) = UUID.fromString(value)

    private companion object {
        const val A = "a0000000-0000-0000-0000-00000000000a"
        const val B = "b0000000-0000-0000-0000-00000000000b"
        const val RA = "a1000000-0000-0000-0000-00000000000a"
        const val RB = "b1000000-0000-0000-0000-00000000000b"
        const val S1 = "51000000-0000-0000-0000-000000000001"
        const val S2 = "52000000-0000-0000-0000-000000000002"
        const val S3 = "53000000-0000-0000-0000-000000000003"
        const val S4 = "54000000-0000-0000-0000-000000000004"
        const val C1 = "c1000000-0000-0000-0000-000000000001"
        const val C2 = "c2000000-0000-0000-0000-000000000002"
        const val SPANS = 500
        const val HOST_BUDGET_MS = 1_000L
        const val MATCH_SQL = "SELECT rowid FROM ocr_text_fts WHERE ocr_text_fts MATCH ?"
    }
}
