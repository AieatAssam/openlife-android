package org.openlife.vault.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * P3-02 acceptance ("migration on SQLCipher passes with existing data"):
 * a v5 SQLCipher vault with rows migrates to v6; Room validates every entity
 * table; the §4.7 backfills hold; the C2 triggers fire on SQLCipher. The host
 * twin is FactDatabaseTest. Device gap until run on hardware.
 */
@RunWith(AndroidJUnit4::class)
class FactDatabaseSqlCipherTest {
    @Before
    fun loadSqlCipher() = System.loadLibrary("sqlcipher")

    @Test
    fun versionFiveVaultWithRowsMigratesToTheC2SchemaOnSqlCipher() {
        val name = "p302-sqlcipher-${UUID.randomUUID()}"
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OpenLifeDatabase::class.java,
            emptyList(),
            SupportOpenHelperFactory(ByteArray(32) { 7 }),
        )
        val v5 = helper.createDatabase(name, 5)
        // M-04: a v5 vault as v5 shipped it, with the search index and
        // ADR-0010's any-column update triggers, so the C2-R21 order
        // (narrow the triggers before any backfill UPDATE) runs on SQLCipher.
        assertTrue("FTS5 index on SQLCipher", SearchIndex.ensure(v5))
        V5_UPDATE_TRIGGERS.forEach(v5::execSQL)
        v5.execSQL(
            "INSERT INTO sources(id, state, importedAt, intakeKind, mimeType, byteCount, sha256, width, height, " +
                "orientation, wrappedDek, artefactVersion) VALUES ('s1', 'READY', 1, 'SHARE', 'image/png', 10, " +
                "X'01', 10, 10, 'NORMAL', X'02', 1)",
        )
        v5.execSQL(
            "INSERT INTO ocr_revisions(id, sourceId, state, engineId, modelVersion, orientation, sourceDigest, " +
                "startedAt, extractedAt, reviewState, failureReason, charCount, spanCount) VALUES " +
                "('r1', 's1', 'READY', 'test', '1', 'NORMAL', X'05', 1, 2, 'ACCEPTED', NULL, 9, 2), " +
                "('r2', 's1', 'READY', 'test', '1', 'NORMAL', X'05', 3, 4, 'UNREVIEWED', NULL, 9, 2)",
        )
        v5.execSQL(
            "INSERT INTO ocr_spans(id, revisionId, ordinal, text, confidence, coordinateSystem, `left`, top, `right`, " +
                "bottom) VALUES ('sp1', 'r2', 0, 'Total 12.50', NULL, 'SOURCE_PIXELS', 1, 2, 3, 4), " +
                "('sp2', 'r2', 1, 'Итого', NULL, 'SOURCE_PIXELS', NULL, NULL, NULL, NULL)",
        )
        v5.execSQL(
            "INSERT INTO ocr_user_revisions(id, revisionId, spanId, correctedText, createdAt, actor) VALUES " +
                "('u1', 'r2', 'sp1', 'Total 12.60', 5, 'USER'), ('u2', 'r2', 'sp1', 'Total 12.70', 6, 'USER')",
        )
        val indexedBefore = ftsData(v5)
        assertTrue("the seeded rows are indexed", indexedBefore.isNotEmpty())
        v5.close()
        val migrated = helper.runMigrationsAndValidate(name, 6, false, *OpenLifeDatabase.MIGRATIONS.toTypedArray())
        try {
            NonEntityTables.assertOnlySearchIndex(migrated, 6)
            val rows = FactDeviceRows(migrated)
            assertEquals("r2", rows.text("SELECT currentOcrRevisionId FROM sources WHERE id = 's1'"))
            assertEquals("u1", rows.text("SELECT supersedesId FROM ocr_user_revisions WHERE id = 'u2'"))
            assertEquals("1|LATIN", rows.text("SELECT hasEvidenceRegion || '|' || scriptStatus FROM ocr_spans WHERE id = 'sp1'"))
            assertEquals("0|UNSUPPORTED", rows.text("SELECT hasEvidenceRegion || '|' || scriptStatus FROM ocr_spans WHERE id = 'sp2'"))
            assertEquals(0, rows.count("facts"))
            assertEquals("the backfills leave the index byte-identical", indexedBefore, ftsData(migrated))
            assertEquals(
                "update triggers are narrowed (C2-R64)",
                2,
                rows.text("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE '%fts_au' AND sql LIKE '%AFTER UPDATE OF%'")?.toInt(),
            )
            val refused = runCatching { migrated.execSQL("DELETE FROM ocr_revisions WHERE id = 'r2'") }
            assertTrue("C2-R20 guard fires on SQLCipher: ${refused.exceptionOrNull()}", refused.exceptionOrNull()?.message?.contains("C2-R20") == true)
        } finally {
            migrated.close()
            InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
        }
    }

    /** Every FTS `_data` block, hex-encoded, in id order. */
    private fun ftsData(db: SupportSQLiteDatabase): List<String> = SearchIndex.tables.flatMap { table ->
        db.query("SELECT id, hex(block) FROM ${table}_data ORDER BY id").use { cursor ->
            buildList { while (cursor.moveToNext()) add("${cursor.getLong(0)}:${cursor.getString(1)}") }
        }
    }

    private companion object {
        /** The update triggers exactly as ADR-0010 shipped them in v5 (FactDatabaseTest has the host twin). */
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
