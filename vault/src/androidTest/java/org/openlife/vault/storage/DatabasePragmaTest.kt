package org.openlife.vault.storage

import android.util.Log
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1-18-R16 (V-16): records the effective journal mode and synchronous level
 * of the vault database as production opens it (OpenLifeDatabaseFactory,
 * sqlcipher-android). Read inside a write transaction, so the values are
 * those of the primary connection, the one every commit goes through.
 * Asserts FULL (2): a commit reported as saved must survive power loss.
 */
@RunWith(AndroidJUnit4::class)
class DatabasePragmaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var paths: VaultPaths

    @Before
    fun setUp() {
        paths = VaultPaths(context)
        paths.vaultDir.deleteRecursively()
        paths.ensureDirectoriesExist()
    }

    @After
    fun tearDown() {
        paths.vaultDir.deleteRecursively()
    }

    @Test
    fun effectiveSynchronousAndJournalModeAreRecorded() {
        assertFullSynchronous(RoomDatabase.JournalMode.AUTOMATIC)
    }

    /** Low-RAM devices get TRUNCATE from AUTOMATIC; FULL must hold there too (review R-03). */
    @Test
    fun synchronousIsFullInTruncateJournalModeToo() {
        assertFullSynchronous(RoomDatabase.JournalMode.TRUNCATE)
    }

    private fun assertFullSynchronous(mode: RoomDatabase.JournalMode) {
        val db = OpenLifeDatabaseFactory.create(context, paths, ByteArray(32) { 4 }, journalMode = mode)
        try {
            val sqlite = db.openHelper.writableDatabase
            var journalMode = ""
            var synchronous = -1
            var cipherVersion = ""
            // Inside a transaction, so the pragmas are read on the writer connection.
            sqlite.beginTransaction()
            try {
                sqlite.query("PRAGMA journal_mode").use { it.moveToFirst(); journalMode = it.getString(0) }
                sqlite.query("PRAGMA synchronous").use { it.moveToFirst(); synchronous = it.getInt(0) }
                sqlite.query("PRAGMA cipher_version").use { if (it.moveToFirst()) cipherVersion = it.getString(0) }
                sqlite.setTransactionSuccessful()
            } finally {
                sqlite.endTransaction()
            }
            val record = "P1-18-R16 requested=$mode journal_mode=$journalMode synchronous=$synchronous " +
                "cipher_version=$cipherVersion sqlcipher-android=4.19.0 lowRam=${isLowRam()}"
            Log.i("P118DatabasePragmaTest", record)
            println(record)
            assertEquals("synchronous must be FULL (2): $record", 2, synchronous)
        } finally {
            OpenLifeDatabaseFactory.close(db)
        }
    }

    private fun isLowRam(): Boolean =
        (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
}
