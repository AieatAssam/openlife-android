package org.openlife.vault.storage

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * P3-07-R1: the fact search index. An external-content FTS5 table over
 * `fact_revisions` (canonicalText, rawText), built the way [SearchIndex]
 * builds the OCR index: the same tokenizer, the same persisted secure-delete
 * option, the same capability gate, and fail-closed creation (no index is
 * ever left without secure-delete).
 *
 * It indexes every revision. Only insert and delete triggers maintain it:
 * a revision's text is immutable (C2-R4, c2_fact_revisions_immutable), so
 * there is no update trigger. Which revisions a search shows (status, current
 * revision, the Source's current reading) is decided at query time in
 * [org.openlife.vault.search.SearchSql], per C2-R49.
 *
 * It is not in [SearchIndex.statements] because that list runs in migration
 * 4 to 5, before fact_revisions exists (see [OpenLifeDatabase.MIGRATION_6_7]).
 */
object FactSearchIndex {
    const val TABLE = "fact_fts"
    private const val INSERT_TRIGGER = "fact_fts_ai"
    private const val DELETE_TRIGGER = "fact_fts_ad"

    val tables: List<String> = listOf(TABLE)

    /** The FTS5 shadow tables of [tables]; with them, the only non-entity tables from v7. */
    val shadowTables: List<String> = listOf("${TABLE}_data", "${TABLE}_idx", "${TABLE}_docsize", "${TABLE}_config")

    fun statements(): List<String> = listOf(
        "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING fts5(canonicalText, rawText, " +
            "content='fact_revisions', content_rowid='rowid', tokenize='${SearchIndex.TOKENIZER}')",
        "INSERT INTO $TABLE($TABLE, rank) VALUES('${SearchIndex.SECURE_DELETE}', 1)",
        "DROP TRIGGER IF EXISTS $INSERT_TRIGGER",
        "CREATE TRIGGER $INSERT_TRIGGER AFTER INSERT ON fact_revisions BEGIN " +
            "INSERT INTO $TABLE(rowid, canonicalText, rawText) VALUES (new.rowid, new.canonicalText, new.rawText); END",
        "DROP TRIGGER IF EXISTS $DELETE_TRIGGER",
        "CREATE TRIGGER $DELETE_TRIGGER AFTER DELETE ON fact_revisions BEGIN " +
            "INSERT INTO $TABLE($TABLE, rowid, canonicalText, rawText) " +
            "VALUES ('delete', old.rowid, old.canonicalText, old.rawText); END",
        "INSERT INTO $TABLE($TABLE) VALUES('rebuild')",
    )

    /** Creates the index if the engine supports it; otherwise removes any part and returns false (LIKE path). */
    fun create(db: SupportSQLiteDatabase): Boolean {
        val created = SearchIndex.capable(db) && SearchIndex.attempt { statements().forEach(db::execSQL) }
        if (!created) drop(db)
        return created
    }

    /** At every open: a complete index is left as it is; anything else is recreated in one transaction. */
    fun ensure(db: SupportSQLiteDatabase): Boolean {
        if (isComplete(db)) return true
        db.beginTransaction()
        try {
            val created = create(db)
            db.setTransactionSuccessful()
            return created
        } finally {
            db.endTransaction()
        }
    }

    private fun isComplete(db: SupportSQLiteDatabase): Boolean {
        if (!SearchIndex.isSupportedEngine(db)) return false
        val tableAndTriggers = count(
            db,
            "(type = 'table' AND name = '$TABLE') OR " +
                "(type = 'trigger' AND name IN ('$INSERT_TRIGGER', '$DELETE_TRIGGER'))",
        ) == 3
        return tableAndTriggers && db.query("SELECT v FROM ${TABLE}_config WHERE k = 'secure-delete'").use {
            it.moveToFirst() && it.getLong(0) == 1L
        }
    }

    private fun count(db: SupportSQLiteDatabase, where: String): Int =
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE $where").use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun drop(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TRIGGER IF EXISTS $INSERT_TRIGGER")
        db.execSQL("DROP TRIGGER IF EXISTS $DELETE_TRIGGER")
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
    }
}
