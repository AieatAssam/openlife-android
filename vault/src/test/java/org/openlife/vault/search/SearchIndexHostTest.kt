package org.openlife.vault.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openlife.vault.repository.SearchRepository
import org.openlife.vault.storage.HostSqlite
import org.openlife.vault.storage.HostSqlite.Companion.createFromExportedSchema
import org.openlife.vault.storage.SearchIndex
import java.util.UUID

/**
 * P2-08-R2/R3/R5/R6/R8 executed on a real SQLite with FTS5 (the host's,
 * through [HostSqlite]): the shipped MIGRATION_4_5, the index triggers, the
 * cascades and the repository's own queries. Not SQLCipher and not a device;
 * the same names run on SQLCipher in the instrumented SearchRepositoryTest.
 */
class SearchIndexHostTest {
    private val host = HostSqlite()

    @After
    fun tearDown() = host.close()

    private fun repository() = SearchRepository(HostSearchDao(host), Dispatchers.Unconfined)

    private fun search(query: String): List<SearchHit> = runBlocking { repository().search(query).first() }

    @Test
    fun migrationIndexesRowsThatExistedBeforeIt() {
        SearchFixture.migrated(host) {
            source(A)
            revision(RA, A)
            span(S1, RA, 0, "Invoice 4471 from the plumber")
            correction(C1, RA, S1, "Invoice 4471 from the electrician")
        }

        assertEquals(listOf(id(S1)), search("plumber").map { it.spanId })
        val corrected = search("electrician").single()
        assertTrue(corrected.isCorrection)
        assertEquals(id(S1), corrected.spanId)
    }

    @Test
    fun freshAndMigratedDatabasesHaveTheSameIndexSchema() {
        SearchFixture.migrated(host)
        val fresh = HostSqlite()
        try {
            fresh.createFromExportedSchema(SearchFixture.V5)
            SearchIndex.create(fresh.asSupportDatabase())
            assertEquals(indexSchema(host), indexSchema(fresh))
            assertTrue(indexSchema(fresh).any { it.contains("fts5") && it.contains(SearchIndex.TOKENIZER) })
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

        assertEquals("prefix of the last word", listOf(id(S2)), search("recep").filterNot { it.isCorrection }.map { it.spanId })
        assertEquals("diacritics fold both ways", listOf(id(S3)), search("cafe lumiere").map { it.spanId })
        assertEquals(listOf(id(S3)), search("CAFÉ").map { it.spanId })
        assertEquals("all words must match", emptyList<SearchHit>(), search("dentist lumiere"))
    }

    @Test
    fun excludesRejectedStaleAndNonReadySources() {
        SearchFixture.migrated(host) {
            // Revision rejected by the user.
            source("10000000-0000-0000-0000-000000000001")
            revision("20000000-0000-0000-0000-000000000001", "10000000-0000-0000-0000-000000000001", reviewState = "REJECTED")
            span("30000000-0000-0000-0000-000000000001", "20000000-0000-0000-0000-000000000001", 0, "walrus rejected")
            // Latest revision STALE; an older READY one has the word.
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
            span("30000000-0000-0000-0000-000000000007", "20000000-0000-0000-0000-000000000006", 1, "walrus span rejected")
            review("30000000-0000-0000-0000-000000000007", "REJECTED")
            span("30000000-0000-0000-0000-000000000008", "20000000-0000-0000-0000-000000000006", 2, "walrus span restored")
            review("30000000-0000-0000-0000-000000000008", "REJECTED")
            review("30000000-0000-0000-0000-000000000008", "UNREVIEWED")
            // A superseded correction no longer matches; the latest one does.
            correction("40000000-0000-0000-0000-000000000001", "20000000-0000-0000-0000-000000000006", "30000000-0000-0000-0000-000000000006", "zebra")
            correction("40000000-0000-0000-0000-000000000002", "20000000-0000-0000-0000-000000000006", "30000000-0000-0000-0000-000000000006", "yak")
        }

        assertEquals(
            setOf("30000000-0000-0000-0000-000000000006", "30000000-0000-0000-0000-000000000008"),
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
            span(S1, RA, 0, "ferry ticket")
            correction(C1, RA, S1, "ferry ticket return")
            source(B)
            revision(RB, B)
            span(S2, RB, 0, "ferry timetable")
        }
        assertEquals(3, search("ferry").size)

        host.exec("DELETE FROM sources WHERE id = ?", A)

        assertEquals(listOf(id(S2)), search("ferry").map { it.spanId })
        assertEquals(emptyList<SearchHit>(), search("return"))
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

        host.exec("DELETE FROM sources")

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
        host.exec("UPDATE ocr_spans SET text = 'harbour' WHERE id = ?", S1)
        host.exec("UPDATE ocr_user_revisions SET correctedText = 'valley' WHERE id = ?", C1)
        assertEquals(emptyList<SearchHit>(), search("orchard"))
        assertEquals(emptyList<SearchHit>(), search("meadow"))
        assertEquals(1, search("harbour").size)
        assertEquals(1, search("valley").size)
        fixture.integrityCheck(SearchIndex.TEXT_TABLE)
        fixture.integrityCheck(SearchIndex.CORRECTION_TABLE)

        host.exec("DELETE FROM ocr_revisions WHERE id = ?", RA)
        assertEquals(0L, fixture.indexedDocuments(SearchIndex.TEXT_TABLE))
        assertEquals(0L, fixture.indexedDocuments(SearchIndex.CORRECTION_TABLE))
    }

    @Test
    fun queryIsSanitisedAgainstFts5Syntax() {
        SearchFixture.migrated(host) {
            source(A)
            revision(RA, A)
            span(S1, RA, 0, "parcel collection slip")
            span(S2, RA, 1, "say \"hello\" * star")
        }
        // The same inputs as raw MATCH syntax would error or widen; prove at least one errors raw.
        val raw = runCatching { host.query("SELECT rowid FROM ocr_text_fts WHERE ocr_text_fts MATCH ?", "NEAR(") }
        assertTrue("raw NEAR( must be an FTS5 syntax error on this engine", raw.isFailure)

        val hostile = listOf(
            "\"", "\"\"", "*", "NEAR(", "NEAR(parcel slip)", "OR", "parcel OR", "OR parcel", "NOT parcel",
            "parcel NOT slip", "-parcel", "text:parcel", "{text}: parcel", "^parcel", "parcel AND", "(", ")",
            "parcel\"*", "\"parcel", "pa*rcel", "parcel + slip", "'; DROP TABLE sources; --", "\\", "%", "_",
            "\u0000", "parcel\nslip", "​parcel",
        )
        hostile.forEach { input ->
            val hits = runCatching { search(input) }.getOrElse { fail("'$input' failed: $it"); emptyList() }
            val literal = SearchQuery.tokens(input)
            hits.forEach { hit ->
                assertTrue(
                    "'$input' widened to a hit that lacks its words: ${hit.snippet}",
                    literal.all { token -> hit.snippet.lowercase().contains(token.lowercase()) },
                )
            }
        }
        assertEquals("OR is a word, not an operator", emptyList<SearchHit>(), search("parcel OR"))
        assertEquals("NOT is a word, not an operator", emptyList<SearchHit>(), search("NOT parcel"))
        assertEquals("quotes are not syntax", listOf(id(S2)), search("\"hello\"").map { it.spanId })
        assertEquals("punctuation-only input searches nothing", emptyList<SearchHit>(), search("* \" ( )"))
        assertEquals(1L, host.long("SELECT COUNT(*) FROM sources"))
    }

    @Test
    fun likeFallbackHasTheSameApiWhenFts5IsAbsent() {
        host.createFromExportedSchema(SearchFixture.V5)
        SearchFixture(host).apply {
            source(A)
            revision(RA, A)
            span(S1, RA, 0, "Dentist appointment")
            span(S2, RA, 1, "Pay 50% at reception_desk")
            correction(C1, RA, S1, "Dentist appointment moved")
            source(B, state = "STAGED")
            revision(RB, B)
            span(S3, RB, 0, "Dentist staged")
        }
        assertEquals(0L, host.long("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE '%fts%'"))

        val hits = search("dentist appoint")
        assertEquals(setOf(false to id(S1), true to id(S1)), hits.map { it.isCorrection to it.spanId }.toSet())
        assertEquals("LIKE wildcards never reach the pattern", emptyList<SearchHit>(), search("% _ %_"))
        assertEquals(listOf(id(S2)), search("50").map { it.spanId })
    }

    /** Acceptance asks < 200 ms for 500 spans on a device; this is the host engine only (recorded). */
    @Test
    fun fiveHundredSpansOnTheHostEngine() {
        SearchFixture.migrated(host) {
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
        println("P2-08 host-engine search latency: ${elapsedMs} ms for $SPANS spans, ${hits.size} hits (limit 50)")
        assertEquals(SearchRepository.DEFAULT_LIMIT, hits.size)
        assertTrue("host search took $elapsedMs ms", elapsedMs < HOST_BUDGET_MS)
    }

    private fun indexSchema(db: HostSqlite): List<String> = db.query(
        "SELECT type || ' ' || name || ' ' || COALESCE(sql, '') FROM sqlite_master " +
            "WHERE name LIKE '%fts%' ORDER BY type, name",
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
        const val C1 = "c1000000-0000-0000-0000-000000000001"
        const val C2 = "c2000000-0000-0000-0000-000000000002"
        const val SPANS = 500
        const val HOST_BUDGET_MS = 1_000L
    }
}
