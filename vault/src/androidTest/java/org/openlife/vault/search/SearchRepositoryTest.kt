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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.repository.SearchRepository
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
        span(S1, RA, 0, "ferry ticket")
        assertEquals(1, repository.search("ferry").first().size)

        db.sourceDao().deleteById(A)

        assertEquals(emptyList<SearchHit>(), repository.search("ferry").first())
    }

    @Test
    fun cascadeOnlyDeletionLeavesNoFtsRows() = runBlocking {
        source(A)
        revision(RA, A)
        span(S1, RA, 0, "lighthouse keeper")
        correction(C1, RA, S1, "lighthouse keeper corrected")

        sql.execSQL("DELETE FROM sources")

        listOf(SearchIndex.TEXT_TABLE, SearchIndex.CORRECTION_TABLE).forEach { table ->
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
        listOf("\"", "*", "NEAR(", "OR", "parcel OR", "NOT parcel", "text:parcel", "(", "'; DROP TABLE sources; --")
            .forEach { input -> repository.search(input).first() }
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
        val migrated = helper.runMigrationsAndValidate(migrationName, 5, true, *OpenLifeDatabase.MIGRATIONS.toTypedArray())
        try {
            migrated.query("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('ocr_text_fts', 'ocr_correction_fts')").use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(migrationName)
        }
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
    }
}
