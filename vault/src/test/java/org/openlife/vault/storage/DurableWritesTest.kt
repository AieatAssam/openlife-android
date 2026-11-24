package org.openlife.vault.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * P1-18-R16 (V-16), review R-03: every time Room opens the vault database
 * its writer connection is switched to synchronous=FULL, after SQLCipher has
 * applied its own NORMAL default. The effective value on a device is
 * DatabasePragmaTest's job; this pins the statement and where it runs.
 */
class DurableWritesTest {

    @Test
    fun everyOpenSetsFullSynchronousOnTheWriterConnection() {
        val executed = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") executed += args[0] as String
            null
        } as SupportSQLiteDatabase

        DurableWrites.callback.onOpen(db)
        DurableWrites.callback.onOpen(db)

        assertEquals(listOf("PRAGMA synchronous = FULL", "PRAGMA synchronous = FULL"), executed)
    }
}
