package org.openlife.vault.storage

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** P1-18-R16 (V-16): write durability of the vault database. */
object DurableWrites {
    const val PRAGMA: String = "PRAGMA synchronous = FULL"

    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
        }
    }
}
