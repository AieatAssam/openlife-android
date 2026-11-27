package org.openlife.vault.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2-08-R1: the pinned SQLCipher build (sqlcipher-android 4.19.0) supports
 * FTS5 with the tokenizer ADR-0010 names. The outcome is documented either
 * way: if this fails, SearchIndex.create leaves the index out and
 * SearchRepository uses the LIKE fallback (P2-08-R5); ADR-0010 records which.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 * Static evidence: every ABI's libsqlcipher.so carries ENABLE_FTS5 (ADR-0010).
 */
@RunWith(AndroidJUnit4::class)
class FtsAvailabilityTest {
    @Test
    fun sqlcipherBuildSupportsFts5() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.getDatabasePath("p208-fts-${UUID.randomUUID()}")
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, ByteArray(32) { 5 }, null, null, null)
        try {
            db.query("SELECT sqlite_version(), sqlite_compileoption_used('ENABLE_FTS5')").use {
                assertTrue(it.moveToFirst())
                println("P2-08 FTS availability: SQLite ${it.getString(0)}, ENABLE_FTS5=${it.getInt(1)}")
                assertEquals(1, it.getInt(1))
            }
            db.execSQL("CREATE VIRTUAL TABLE t USING fts5(x, tokenize='${SearchIndex.TOKENIZER}')")
            db.execSQL("INSERT INTO t(x) VALUES ('Café Lumière')")
            db.query("SELECT bm25(t) FROM t WHERE t MATCH ?", arrayOf<Any?>("\"cafe\" \"lumi\"*")).use {
                assertTrue("remove_diacritics 2 and prefix queries work", it.moveToFirst())
            }
        } finally {
            db.close()
            SQLiteDatabase.deleteDatabase(file)
        }
    }

    /**
     * PR #36 finding: SQLite 3.42.0 to 3.46.0 corrupt an FTS5 external-content
     * index on delete with secure-delete on. The bundled engine must be at
     * least [SearchIndex.MIN_SQLITE_VERSION], and the index's insert, update
     * and delete sequence must pass integrity-check after each step.
     */
    @Test
    fun bundledSqliteKeepsASecureDeleteExternalContentIndexIntact() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.getDatabasePath("p208-fts-integrity-${UUID.randomUUID()}")
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, ByteArray(32) { 6 }, null, null, null)
        try {
            val version = db.query("SELECT sqlite_version()").use { it.moveToFirst(); it.getString(0) }
            println("P2-08 FTS integrity: SQLite $version")
            assertTrue("SQLite $version >= ${SearchIndex.MIN_SQLITE_VERSION}", SearchIndex.isSupportedVersion(version))
            db.execSQL("CREATE TABLE c(id TEXT PRIMARY KEY NOT NULL, text TEXT NOT NULL)")
            db.execSQL(
                "CREATE VIRTUAL TABLE f USING fts5(text, content='c', content_rowid='rowid', " +
                    "tokenize='${SearchIndex.TOKENIZER}')",
            )
            db.execSQL("INSERT INTO f(f, rank) VALUES('secure-delete', 1)")
            db.execSQL(
                "CREATE TRIGGER f_ai AFTER INSERT ON c BEGIN " +
                    "INSERT INTO f(rowid, text) VALUES (new.rowid, new.text); END",
            )
            db.execSQL(
                "CREATE TRIGGER f_ad AFTER DELETE ON c BEGIN " +
                    "INSERT INTO f(f, rowid, text) VALUES ('delete', old.rowid, old.text); END",
            )
            db.execSQL(
                "CREATE TRIGGER f_au AFTER UPDATE OF text ON c BEGIN " +
                    "INSERT INTO f(f, rowid, text) VALUES ('delete', old.rowid, old.text); " +
                    "INSERT INTO f(rowid, text) VALUES (new.rowid, new.text); END",
            )
            listOf(
                "INSERT INTO c VALUES('a', 'orchard')",
                "INSERT INTO c VALUES('b', 'walnut')",
                "UPDATE c SET text = 'harbour' WHERE id = 'a'",
                "DELETE FROM c WHERE id = 'a'",
                "DELETE FROM c WHERE id = 'b'",
            ).forEach { step ->
                db.execSQL(step)
                db.execSQL("INSERT INTO f(f) VALUES('integrity-check')")
                db.execSQL("INSERT INTO f(f, rank) VALUES('integrity-check', 1)")
            }
        } finally {
            db.close()
            SQLiteDatabase.deleteDatabase(file)
        }
    }
}
