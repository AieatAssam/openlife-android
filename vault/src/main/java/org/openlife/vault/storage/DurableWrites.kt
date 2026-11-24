package org.openlife.vault.storage

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * P1-18-R16 (V-16), review R-03: write durability of the vault database.
 *
 * sqlcipher-android 4.19.0 applies `synchronous=NORMAL` to every connection
 * in `SQLiteConnection.open()` (`SQLiteGlobal.getWALSyncMode()` and
 * `getDefaultSyncMode()` are both "normal"), after any SQLiteDatabaseHook
 * runs. With NORMAL in WAL mode a commit that has returned can be rolled
 * back by power loss; a READY commit the user saw as saved could then come
 * back STAGED and recovery would delete it. Room's `onOpen` runs after that
 * open on the writer connection, which every vault write goes through, so
 * FULL is set there on every open, in WAL and in TRUNCATE (low-RAM) mode.
 * Reader connections keep NORMAL; they never commit writes.
 */
object DurableWrites {
    const val PRAGMA: String = "PRAGMA synchronous = FULL"

    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            db.execSQL(PRAGMA)
        }
    }
}
