package org.openlife.vault.search

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.repository.SearchRepository
import org.openlife.vault.storage.NonEntityTables
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.OpenLifeDatabaseFactory
import org.openlife.vault.storage.SearchIndex
import org.openlife.vault.storage.VaultPaths

/**
 * P2-08 on SQLCipher through Room: the index inside the encrypted database,
 * the repository's queries, deletion through the cascade only (VS-2), and
 * Room opening and validating a migrated v5 database with the FTS tables
 * present. The same cases run on the host engine in SearchIndexHostTest.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class SearchRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "p208-search-${UUID.randomUUID()}"
    private lateinit var db: OpenLifeDatabase
    private lateinit var sql: SupportSQLiteDatabase
    private lateinit var repository: SearchRepository

    @Before
    fun setUp() {
        db = OpenLifeDatabaseFactory.create(
            context = context,
            paths = VaultPaths(context),
            databaseSecret = ByteArray(32) { 9 },
            databasePath = context.getDatabasePath(name).absolutePath,
        )
        sql = db.openHelper.writableDatabase
        repository = SearchRepository(db.searchDao())
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun findsSpanTextAndCorrectionsRankedAndLinked() = runBlocking {
        source(A)
        revision(RA, A)
        span(S1, RA, 0, "Dentist")
        span(S2, RA, 1, "Pay at reception before you see the dentist on the second floor")
        correction(C1, RA, S2, "Dentist bill paid at reception")

        val hits = repository.search("dentist").first()
        assertEquals(3, hits.size)
        assertEquals(UUID.fromString(S1), hits.first().spanId)
        hits.forEach { assertEquals(UUID.fromString(A), it.sourceId) }
        val correction = hits.single { it.isCorrection }
        assertEquals(UUID.fromString(S2), correction.spanId)
        assertEquals(listOf(0..6), correction.highlights)
    }

    @Test
    fun excludesRejectedStaleAndNonReadySources() = runBlocking {
        source(A)
        revision(RA, A, reviewState = "REJECTED")
        span(S1, RA, 0, "walrus rejected")
        source(B, state = "CORRUPT")
        revision(RB, B)
        span(S2, RB, 0, "walrus corrupt")
        source(C)
        revision(RC, C)
        span(S3, RC, 0, "walrus older")
        revision(RC2, C, state = "STALE")

        assertEquals(emptyList<SearchHit>(), repository.search("walrus").first())
    }

    @Test
    fun deletingASourceRemovesItsHits() = runBlocking {
        source(A)
        revision(RA, A)
        span(S1, RA, 0, "ferry kumquat")
        correction(C1, RA, S1, "ferry jicama")
        assertEquals(2, repository.search("ferry").first().size)
        assertTrue("positive control", dataContains(SearchIndex.TEXT_TABLE, "kumquat"))
        assertTrue("positive control", dataContains(SearchIndex.CORRECTION_TABLE, "jicama"))

        db.sourceDao().deleteById(A)

        assertEquals(emptyList<SearchHit>(), repository.search("ferry").first())
        // W-01: with secure-delete the terms leave the live segments at once.
        assertFalse(dataContains(SearchIndex.TEXT_TABLE, "kumquat"))
        assertFalse(dataContains(SearchIndex.CORRECTION_TABLE, "jicama"))
    }

    /**
     * P3-07-R1 (C2-R49): facts are found in the current revisions of a READY Source only. A confirmed or
     * unreviewed VALUE that names the current reading is found; a rejected, stale, or earlier-reading value
     * is not.
     */
    @Test
    fun findsConfirmedAndUnreviewedFactsButNotRejectedOrStale() = runBlocking {
        source(A)
        revision(RX, A)
        revision(RA, A)
        sql.execSQL("UPDATE sources SET currentOcrRevisionId = ? WHERE id = ?", arrayOf<Any?>(RA, A))
        span(S1, RA, 0, "Due 2026-10-08")
        span(S2, RX, 0, "Old 2026-10-12")
        fact(1, A, "EXTRACTED", "UNREVIEWED", "2026-10-08", RA to S1)
        fact(2, A, "USER", "CONFIRMED", "2026-10-09", null)
        fact(3, A, "EXTRACTED", "REJECTED", "2026-10-10", RA to S1)
        fact(4, A, "EXTRACTED", "STALE", "2026-10-11", RA to S1)
        fact(5, A, "EXTRACTED", "UNREVIEWED", "2026-10-12", RX to S2)

        val hits = repository.search("2026").first()
        assertEquals(setOf(RF1, RF2), hits.mapNotNull { it.fact?.revisionId?.toString() }.toSet())
        val user = hits.single { it.fact?.revisionId == UUID.fromString(RF2) }.fact!!
        assertEquals("DATE", user.kind)
        assertEquals("CONFIRMED", user.status)
        assertEquals("USER", user.origin)
    }

    /** W-01: both tables carry the persisted secure-delete option on a fresh SQLCipher vault. */
    @Test
    fun secureDeleteIsOnForBothTables() {
        SearchIndex.tables.forEach { table ->
            sql.query("SELECT v FROM ${table}_config WHERE k = 'secure-delete'").use {
                assertTrue("$table has the option", it.moveToFirst())
                assertEquals(1L, it.getLong(0))
            }
        }
    }

    @Test
    fun anUpdatedCorrectionLeavesNoOldTermInTheIndex() = runBlocking {
        source(A)
        revision(RA, A)
        span(S1, RA, 0, "orchard")
        correction(C1, RA, S1, "meadow")
        assertTrue("positive control", dataContains(SearchIndex.CORRECTION_TABLE, "meadow"))

        sql.execSQL("UPDATE ocr_user_revisions SET correctedText = 'valley' WHERE id = ?", arrayOf<Any?>(C1))

        assertFalse(dataContains(SearchIndex.CORRECTION_TABLE, "meadow"))
        assertEquals(1, repository.search("valley").first().size)
    }

    @Test
    fun cascadeOnlyDeletionLeavesNoFtsRows() = runBlocking {
        source(A)
        revision(RA, A)
        span(S1, RA, 0, "lighthouse keeper")
        correction(C1, RA, S1, "lighthouse keeper corrected")

        assertTrue("positive control", dataContains(SearchIndex.TEXT_TABLE, "lighthouse"))

        sql.execSQL("DELETE FROM sources")

        listOf(SearchIndex.TEXT_TABLE, SearchIndex.CORRECTION_TABLE).forEach { table ->
            listOf("lighthouse", "keeper", "corrected").forEach {
                assertFalse("$it left in ${table}_data", dataContains(table, it))
            }
            sql.query("SELECT COUNT(*) FROM ${table}_docsize").use {
                assertTrue(it.moveToFirst())
                assertEquals("$table keeps no document after the cascade", 0, it.getInt(0))
            }
            sql.execSQL("INSERT INTO $table($table) VALUES('integrity-check')")
        }
        assertEquals(emptyList<SearchHit>(), repository.search("lighthouse").first())
    }

    @Test
    fun queryIsSanitisedAgainstFts5Syntax() = runBlocking {
        source(A)
        revision(RA, A)
        span(S1, RA, 0, "parcel collection slip")
        span(S2, RA, 1, "zebra crossing")
        val hostile = listOf(
            "\"", "*", "NEAR(", "OR", "parcel OR", "NOT parcel", "text:parcel", "(", "'; DROP TABLE sources; --",
        )
        // W-03: the expression runs directly on SQLCipher with no catch: a syntax error fails the test.
        hostile.forEach { input ->
            val expression = SearchQuery.matchExpression(SearchQuery.tokens(input)) ?: return@forEach
            sql.query("SELECT rowid FROM ocr_text_fts WHERE ocr_text_fts MATCH ?", arrayOf<Any?>(expression)).close()
        }
        sql.query("SELECT rowid FROM ocr_text_fts WHERE ocr_text_fts MATCH ?", arrayOf<Any?>("parcel OR zebra")).use {
            assertEquals("raw syntax would widen", 2, it.count)
        }
        assertEquals(emptyList<SearchHit>(), repository.search("parcel OR zebra").first())
        assertEquals(emptyList<SearchHit>(), repository.search("parcel OR").first())
        assertEquals(1, repository.search("parcel").first().size)
    }

    /** P2-08-R2: Room opens and validates a migrated v5 database with the virtual tables present. */
    @Test
    fun roomValidatesAMigratedDatabaseWithTheFtsTablesPresent() {
        val migrationName = "p208-migrate-${UUID.randomUUID()}"
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OpenLifeDatabase::class.java,
            emptyList(),
            SupportOpenHelperFactory(ByteArray(32) { 3 }),
        )
        helper.createDatabase(migrationName, 4).close()
        // validateDroppedTables = false: Room's scan of sqlite_master rejects any table that is not an
        // entity, and the FTS5 tables (and their shadow tables) deliberately are not. Every entity table
        // is still validated against 5.json.
        val migrated = helper.runMigrationsAndValidate(
            migrationName,
            5,
            false,
            *OpenLifeDatabase.MIGRATIONS.toTypedArray(),
        )
        try {
            NonEntityTables.assertOnlySearchIndex(migrated, 5)
        } finally {
            migrated.close()
            context.deleteDatabase(migrationName)
        }
    }

    /**
     * Inserts fact [n] with one revision as its current revision. [evidence] is the VALUE row's
     * (ocrRevisionId to ocrSpanId); null for a USER revision. STALE gets its staleReason and staleAt.
     */
    private fun fact(
        n: Int,
        sourceId: String,
        origin: String,
        status: String,
        canonical: String,
        evidence: Pair<String, String>?,
    ) {
        val factId = "fa000000-0000-0000-0000-00000000000$n"
        val revisionId = "f1000000-0000-0000-0000-00000000000$n"
        sql.execSQL(
            "INSERT INTO facts(id, sourceId, kind, ordinal, currentRevisionId, createdAt) " +
                "VALUES (?, ?, 'DATE', ?, NULL, 1)",
            arrayOf<Any?>(factId, sourceId, n),
        )
        val stale = status == "STALE"
        sql.execSQL(
            "INSERT INTO fact_revisions(id, factId, revisionNo, origin, kind, valueJson, canonicalText, rawText, " +
                "status, staleReason, staleAt, confidence, methodId, methodVersion, locale, createdAt, " +
                "supersedesRevisionId) VALUES (?, ?, 1, ?, 'DATE', '{}', ?, NULL, ?, ?, ?, NULL, 'test', NULL, NULL, " +
                "?, NULL)",
            arrayOf<Any?>(
                revisionId, factId, origin, canonical, status,
                if (stale) "READING_REPLACED" else null, if (stale) 2L else null, clock++,
            ),
        )
        if (evidence != null) {
            sql.execSQL(
                "INSERT INTO fact_evidence(id, revisionId, ordinal, role, sourceId, ocrRevisionId, ocrSpanId, " +
                    "ocrUserRevisionId, textStart, textEnd, regionLeft, regionTop, regionRight, regionBottom, " +
                    "regionCoordinateSystem) VALUES (?, ?, 0, 'VALUE', ?, ?, ?, NULL, 0, 4, 1, 1, 5, 5, " +
                    "'SOURCE_PIXELS')",
                arrayOf<Any?>("e0000000-0000-0000-0000-00000000000$n", revisionId, sourceId,
                    evidence.first, evidence.second),
            )
        }
        sql.execSQL("UPDATE facts SET currentRevisionId = ? WHERE id = ?", arrayOf<Any?>(revisionId, factId))
    }

    private fun dataContains(table: String, term: String): Boolean {
        val needle = term.toByteArray(Charsets.UTF_8)
        sql.query("SELECT block FROM ${table}_data").use { cursor ->
            while (cursor.moveToNext()) {
                val block = if (cursor.isNull(0)) continue else cursor.getBlob(0)
                val found = (0..block.size - needle.size).any { start ->
                    needle.indices.all { block[start + it] == needle[it] }
                }
                if (found) return true
            }
        }
        return false
    }

    private fun source(id: String, state: String = "READY") {
        if (state == "READY") {
            sql.execSQL(
                "INSERT INTO sources(id, state, importedAt, intakeKind, mimeType, byteCount, sha256, width, height, " +
                    "orientation, wrappedDek, artefactVersion) VALUES (?, 'READY', 1, 'SHARE', 'image/png', 10, X'01', " +
                    "10, 10, 'NORMAL', X'02', 1)",
                arrayOf<Any?>(id),
            )
        } else {
            sql.execSQL(
                "INSERT INTO sources(id, state, importedAt, intakeKind) VALUES (?, ?, 1, 'SHARE')",
                arrayOf<Any?>(id, state),
            )
        }
    }

    private var clock = 1L

    private fun revision(id: String, sourceId: String, state: String = "READY", reviewState: String = "UNREVIEWED") {
        sql.execSQL(
            "INSERT INTO ocr_revisions(id, sourceId, state, engineId, modelVersion, orientation, sourceDigest, " +
                "startedAt, extractedAt, reviewState, failureReason, charCount, spanCount) " +
                "VALUES (?, ?, ?, 'test', '1', 'NORMAL', X'01', ?, ?, ?, NULL, 0, 0)",
            arrayOf<Any?>(id, sourceId, state, clock, clock++, reviewState),
        )
    }

    private fun span(id: String, revisionId: String, ordinal: Int, text: String) {
        sql.execSQL(
            "INSERT INTO ocr_spans(id, revisionId, ordinal, text, confidence, coordinateSystem, `left`, top, `right`, " +
                "bottom) VALUES (?, ?, ?, ?, NULL, 'SOURCE_PIXELS', 1, 1, 5, 5)",
            arrayOf<Any?>(id, revisionId, ordinal, text),
        )
    }

    private fun correction(id: String, revisionId: String, spanId: String?, text: String) {
        sql.execSQL(
            "INSERT INTO ocr_user_revisions(id, revisionId, spanId, correctedText, createdAt, actor) " +
                "VALUES (?, ?, ?, ?, ?, 'USER')",
            arrayOf<Any?>(id, revisionId, spanId, text, clock++),
        )
    }

    private companion object {
        const val A = "a0000000-0000-0000-0000-00000000000a"
        const val B = "b0000000-0000-0000-0000-00000000000b"
        const val C = "c0000000-0000-0000-0000-00000000000c"
        const val RA = "a1000000-0000-0000-0000-00000000000a"
        const val RB = "b1000000-0000-0000-0000-00000000000b"
        const val RC = "c1000000-0000-0000-0000-00000000000c"
        const val RC2 = "c2000000-0000-0000-0000-00000000000c"
        const val S1 = "51000000-0000-0000-0000-000000000001"
        const val S2 = "52000000-0000-0000-0000-000000000002"
        const val S3 = "53000000-0000-0000-0000-000000000003"
        const val C1 = "c1000000-0000-0000-0000-000000000001"
        const val RX = "a2000000-0000-0000-0000-00000000000a"
        const val RF1 = "f1000000-0000-0000-0000-000000000001"
        const val RF2 = "f1000000-0000-0000-0000-000000000002"
    }
}
