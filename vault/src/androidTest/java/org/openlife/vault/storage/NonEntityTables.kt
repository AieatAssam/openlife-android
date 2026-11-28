package org.openlife.vault.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals

/**
 * P2-08 review W-04: migration tests pass `validateDroppedTables = false`
 * because the FTS5 tables are not Room entities. This puts back what that
 * flag turns off: after migrating, the only tables that are not entities of
 * the exported schema (and not Room's or SQLite's own bookkeeping) are the two
 * index tables and their FTS5 shadow tables. An unexpected leftover table
 * still fails the test.
 */
object NonEntityTables {
    private val BOOKKEEPING = setOf("room_master_table", "sqlite_sequence", "android_metadata")

    fun assertOnlySearchIndex(db: SupportSQLiteDatabase, version: Int) {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val schema = assets.open("org.openlife.vault.storage.OpenLifeDatabase/$version.json")
            .bufferedReader()
            .use { it.readText() }
        val entities = JSONObject(schema).getJSONObject("database").getJSONArray("entities")
        val entityTables = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.toSet()
        val tables = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        assertEquals(
            "non-entity tables after migrating to v$version",
            (SearchIndex.tables + SearchIndex.shadowTables + factIndex(version)).toSet(),
            tables - entityTables - BOOKKEEPING,
        )
    }

    /** From v7 the fact index (P3-07) is the third non-entity table pair; v6 has none. */
    private fun factIndex(version: Int): List<String> =
        if (version >= 7) FactSearchIndex.tables + FactSearchIndex.shadowTables else emptyList()
}
