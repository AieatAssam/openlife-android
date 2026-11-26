package org.openlife.vault.storage

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * C2-R21: the additive migration to the C2 schema, as named steps run in
 * this order (ZZ-01). Steps 1-3 never re-index a span or a correction.
 */
object FactMigration {
    /** Step 1 (C2-R64): narrows the FTS update triggers, before any backfill UPDATE. */
    fun narrowFtsUpdateTriggers(db: SupportSQLiteDatabase) {
        TODO("P3-02 GREEN")
    }

    /** Step 2: the §4.7 columns. */
    fun addColumns(db: SupportSQLiteDatabase) {
        TODO("P3-02 GREEN")
    }

    /** Step 3: the §4.7 backfills, including the Kotlin scriptStatus classification (C2-R17). */
    fun backfill(db: SupportSQLiteDatabase, classify: (String) -> String = ::spanScriptStatusOf) {
        TODO("P3-02 GREEN")
    }

    /** Step 4: the §4.1-§4.3, §4.5, §4.6 and §4.8 tables and their indices. */
    fun createTables(db: SupportSQLiteDatabase) {
        TODO("P3-02 GREEN")
    }

    /** Steps 1-7 in order. */
    fun migrate(db: SupportSQLiteDatabase) {
        TODO("P3-02 GREEN")
    }
}
