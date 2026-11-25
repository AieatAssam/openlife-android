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
}
