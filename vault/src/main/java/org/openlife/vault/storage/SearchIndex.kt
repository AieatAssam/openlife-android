package org.openlife.vault.storage

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * P2-08-R2/R8 (ADR-0010): the local search index lives inside the SQLCipher
 * database as two FTS5 external-content tables, so no plaintext index exists
 * outside the encrypted file:
 * - `ocr_text_fts` over `ocr_spans(text)`;
 * - `ocr_correction_fts` over `ocr_user_revisions(correctedText)`.
 *
 * They are not Room entities (Room supports only FTS3/4 entities) and are
 * outside Room's exported schema. They are maintained only by the
 * insert/update/delete triggers below, so every row that leaves a content
 * table — by a repository delete, by ON DELETE CASCADE from a source or a
 * revision, or by a vault reset — also leaves the index (VS-2). No other code
 * writes these tables (SearchIndexBoundaryTest).
 *
 * Review W-01: both tables run with the FTS5 `secure-delete` option
 * (SQLite >= 3.42; persisted in `<table>_config`), so a deleted row's terms
 * are removed from the live `<table>_data` segments at once instead of
 * waiting for a merge. The index is never created without it: if the option
 * cannot be set, no index exists and search uses the LIKE fallback.
 *
 * The index is keyed on the content tables' implicit rowid. VACUUM may
 * renumber implicit rowids, so OpenLife never runs VACUUM; any future VACUUM
 * must be followed by the 'rebuild' commands in [statements].
 *
 * Rule: one definition, used by [OpenLifeDatabase.MIGRATION_4_5], by
 * [OpenLifeDatabase.readyInvariantCallback] for a fresh database and by
 * [callback] at every open, so every path ends with the same index.
 * Idempotent.
 */
object SearchIndex {
    const val TEXT_TABLE = "ocr_text_fts"
    const val CORRECTION_TABLE = "ocr_correction_fts"
    const val TOKENIZER = "unicode61 remove_diacritics 2"

    const val SECURE_DELETE = "secure-delete"

    /** The oldest engine the index is created or kept on (PR #36 finding; see [capable]). */
    const val MIN_SQLITE_VERSION = "3.46.1"

    val tables: List<String> = listOf(TEXT_TABLE, CORRECTION_TABLE)

    val triggerNames: List<String> = tables.flatMap { table ->
        listOf("${table}_ai", "${table}_ad", "${table}_au")
    }

    /** The tables FTS5 creates for each index; with [tables], the only non-entity tables in the vault. */
    val shadowTables: List<String> = tables.flatMap { table ->
        listOf("${table}_data", "${table}_idx", "${table}_docsize", "${table}_config")
    }

    /**
     * C2-R64 (P3-02): the update triggers fire only when the indexed column
     * changes, so a column-only update (a backfill, `hasEvidenceRegion`,
     * `supersedesId`) never deletes and re-inserts an index row.
     */
    private const val TEXT_UPDATE_TRIGGER = "CREATE TRIGGER ocr_text_fts_au AFTER UPDATE OF text ON ocr_spans BEGIN " +
        "INSERT INTO ocr_text_fts(ocr_text_fts, rowid, text) VALUES ('delete', old.rowid, old.text); " +
        "INSERT INTO ocr_text_fts(rowid, text) VALUES (new.rowid, new.text); END"

    private const val CORRECTION_UPDATE_TRIGGER =
        "CREATE TRIGGER ocr_correction_fts_au AFTER UPDATE OF correctedText ON ocr_user_revisions BEGIN " +
            "INSERT INTO ocr_correction_fts(ocr_correction_fts, rowid, correctedText) " +
            "VALUES ('delete', old.rowid, old.correctedText); " +
            "INSERT INTO ocr_correction_fts(rowid, correctedText) VALUES (new.rowid, new.correctedText); END"

    private val updateTriggerNames = tables.map { "${it}_au" }

    /**
     * C2-R21 step 1: recreates only the two update triggers in their narrowed
     * form, on an existing index, before any backfill UPDATE runs. Without an
     * index there is nothing to narrow; [ensure] creates the whole index later.
     */
    fun narrowUpdateTriggers(db: SupportSQLiteDatabase) {
        if (count(db, "type = 'table' AND name IN (${quoted(tables)})") != tables.size) return
        db.execSQL("DROP TRIGGER IF EXISTS ocr_text_fts_au")
        db.execSQL(TEXT_UPDATE_TRIGGER)
        db.execSQL("DROP TRIGGER IF EXISTS ocr_correction_fts_au")
        db.execSQL(CORRECTION_UPDATE_TRIGGER)
    }

    /** Every statement that creates the index and its triggers and indexes existing rows, in order. */
    fun statements(): List<String> = listOf(
        "CREATE VIRTUAL TABLE IF NOT EXISTS ocr_text_fts USING fts5(text, content='ocr_spans', " +
            "content_rowid='rowid', tokenize='$TOKENIZER')",
        "INSERT INTO ocr_text_fts(ocr_text_fts, rank) VALUES('$SECURE_DELETE', 1)",
        "DROP TRIGGER IF EXISTS ocr_text_fts_ai",
        "CREATE TRIGGER ocr_text_fts_ai AFTER INSERT ON ocr_spans BEGIN " +
            "INSERT INTO ocr_text_fts(rowid, text) VALUES (new.rowid, new.text); END",
        "DROP TRIGGER IF EXISTS ocr_text_fts_ad",
        "CREATE TRIGGER ocr_text_fts_ad AFTER DELETE ON ocr_spans BEGIN " +
            "INSERT INTO ocr_text_fts(ocr_text_fts, rowid, text) VALUES ('delete', old.rowid, old.text); END",
        "DROP TRIGGER IF EXISTS ocr_text_fts_au",
        TEXT_UPDATE_TRIGGER,
        "INSERT INTO ocr_text_fts(ocr_text_fts) VALUES('rebuild')",
        "CREATE VIRTUAL TABLE IF NOT EXISTS ocr_correction_fts USING fts5(correctedText, " +
            "content='ocr_user_revisions', content_rowid='rowid', tokenize='$TOKENIZER')",
        "INSERT INTO ocr_correction_fts(ocr_correction_fts, rank) VALUES('$SECURE_DELETE', 1)",
        "DROP TRIGGER IF EXISTS ocr_correction_fts_ai",
        "CREATE TRIGGER ocr_correction_fts_ai AFTER INSERT ON ocr_user_revisions BEGIN " +
            "INSERT INTO ocr_correction_fts(rowid, correctedText) VALUES (new.rowid, new.correctedText); END",
        "DROP TRIGGER IF EXISTS ocr_correction_fts_ad",
        "CREATE TRIGGER ocr_correction_fts_ad AFTER DELETE ON ocr_user_revisions BEGIN " +
            "INSERT INTO ocr_correction_fts(ocr_correction_fts, rowid, correctedText) " +
            "VALUES ('delete', old.rowid, old.correctedText); END",
        "DROP TRIGGER IF EXISTS ocr_correction_fts_au",
        CORRECTION_UPDATE_TRIGGER,
        "INSERT INTO ocr_correction_fts(ocr_correction_fts) VALUES('rebuild')",
    )

    /**
     * P2-08-R1/R5: creates the index when this SQLite build has FTS5 with
     * `secure-delete` and returns true. Otherwise, or if any statement fails,
     * it removes whatever part of the index exists and returns false; search
     * then uses the LIKE fallback. It never throws for a missing capability,
     * so neither the migration nor the open fails, and no index is ever left
     * without secure-delete (fail closed). The cause is not logged or kept:
     * SQLite messages can quote content.
     */
    fun create(db: SupportSQLiteDatabase): Boolean {
        val created = capable(db) && attempt { statements().forEach(db::execSQL) }
        if (!created) drop(db)
        return created
    }

    /**
     * Review: the capability decision is re-evaluated at every open, so a
     * failed probe (for example a transient error) costs one session its
     * index, never the vault forever. A complete index (both tables, all six
     * triggers, secure-delete on) is left untouched; anything else is
     * recreated in one transaction, which also re-indexes existing rows.
     */
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

    /**
     * Runs [ensure] at every open of the vault database. A search-only
     * failure never stops the vault from opening: the transaction in
     * [ensure] rolls back and search uses the LIKE path for this session.
     */
    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            attempt { ensure(db) }
        }
    }

    internal fun isComplete(db: SupportSQLiteDatabase): Boolean {
        if (count(db, "name IN (${quoted(tables + triggerNames)})") != tables.size + triggerNames.size) return false
        val narrowed = count(
            db,
            "type = 'trigger' AND name IN (${quoted(updateTriggerNames)}) AND sql LIKE '%UPDATE OF%'",
        )
        if (narrowed != updateTriggerNames.size) return false
        return tables.all { table ->
            db.query("SELECT v FROM ${table}_config WHERE k = '$SECURE_DELETE'").use { cursor ->
                cursor.moveToFirst() && cursor.getLong(0) == 1L
            }
        }
    }

    private fun quoted(names: List<String>): String = names.joinToString(", ") { "'$it'" }

    private fun count(db: SupportSQLiteDatabase, where: String): Int =
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE $where").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun drop(db: SupportSQLiteDatabase) {
        triggerNames.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
        tables.forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
    }

    /** FTS5 and its secure-delete option, tried on a temp table that is always removed. */
    private fun capable(db: SupportSQLiteDatabase): Boolean = attempt {
        db.execSQL("DROP TABLE IF EXISTS temp.fts5_probe")
        db.execSQL("CREATE VIRTUAL TABLE temp.fts5_probe USING fts5(x)")
        try {
            db.execSQL("INSERT INTO temp.fts5_probe(fts5_probe, rank) VALUES('$SECURE_DELETE', 1)")
        } finally {
            db.execSQL("DROP TABLE temp.fts5_probe")
        }
    }

    // "no such module: fts5" and "SQL logic error" (an unknown FTS5 option on
    // SQLite < 3.42) surface as the platform's or SQLCipher's SQLiteException,
    // a RuntimeException. The exception is dropped unread: it can quote SQL.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private inline fun attempt(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: RuntimeException) {
        false
    }
}
