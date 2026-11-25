package org.openlife.vault.storage

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
 * The index is keyed on the content tables' implicit rowid. VACUUM may
 * renumber implicit rowids, so OpenLife never runs VACUUM; any future VACUUM
 * must be followed by the 'rebuild' commands in [statements].
 *
 * Rule: one definition, used by [OpenLifeDatabase.MIGRATION_4_5] and by
 * [OpenLifeDatabase.readyInvariantCallback] for a fresh database, so both
 * paths end with the same index. Idempotent.
 */
object SearchIndex {
    const val TEXT_TABLE = "ocr_text_fts"
    const val CORRECTION_TABLE = "ocr_correction_fts"
    const val TOKENIZER = "unicode61 remove_diacritics 2"

    val triggerNames: List<String> = listOf(TEXT_TABLE, CORRECTION_TABLE).flatMap { table ->
        listOf("${table}_ai", "${table}_ad", "${table}_au")
    }

    /** Every statement that creates the index and its triggers and indexes existing rows, in order. */
    fun statements(): List<String> = listOf(
        "CREATE VIRTUAL TABLE IF NOT EXISTS ocr_text_fts USING fts5(text, content='ocr_spans', " +
            "content_rowid='rowid', tokenize='$TOKENIZER')",
        "DROP TRIGGER IF EXISTS ocr_text_fts_ai",
        "CREATE TRIGGER ocr_text_fts_ai AFTER INSERT ON ocr_spans BEGIN " +
            "INSERT INTO ocr_text_fts(rowid, text) VALUES (new.rowid, new.text); END",
        "DROP TRIGGER IF EXISTS ocr_text_fts_ad",
        "CREATE TRIGGER ocr_text_fts_ad AFTER DELETE ON ocr_spans BEGIN " +
            "INSERT INTO ocr_text_fts(ocr_text_fts, rowid, text) VALUES ('delete', old.rowid, old.text); END",
        "DROP TRIGGER IF EXISTS ocr_text_fts_au",
        "CREATE TRIGGER ocr_text_fts_au AFTER UPDATE ON ocr_spans BEGIN " +
            "INSERT INTO ocr_text_fts(ocr_text_fts, rowid, text) VALUES ('delete', old.rowid, old.text); " +
            "INSERT INTO ocr_text_fts(rowid, text) VALUES (new.rowid, new.text); END",
        "INSERT INTO ocr_text_fts(ocr_text_fts) VALUES('rebuild')",
        "CREATE VIRTUAL TABLE IF NOT EXISTS ocr_correction_fts USING fts5(correctedText, " +
            "content='ocr_user_revisions', content_rowid='rowid', tokenize='$TOKENIZER')",
        "DROP TRIGGER IF EXISTS ocr_correction_fts_ai",
        "CREATE TRIGGER ocr_correction_fts_ai AFTER INSERT ON ocr_user_revisions BEGIN " +
            "INSERT INTO ocr_correction_fts(rowid, correctedText) VALUES (new.rowid, new.correctedText); END",
        "DROP TRIGGER IF EXISTS ocr_correction_fts_ad",
        "CREATE TRIGGER ocr_correction_fts_ad AFTER DELETE ON ocr_user_revisions BEGIN " +
            "INSERT INTO ocr_correction_fts(ocr_correction_fts, rowid, correctedText) " +
            "VALUES ('delete', old.rowid, old.correctedText); END",
        "DROP TRIGGER IF EXISTS ocr_correction_fts_au",
        "CREATE TRIGGER ocr_correction_fts_au AFTER UPDATE ON ocr_user_revisions BEGIN " +
            "INSERT INTO ocr_correction_fts(ocr_correction_fts, rowid, correctedText) " +
            "VALUES ('delete', old.rowid, old.correctedText); " +
            "INSERT INTO ocr_correction_fts(rowid, correctedText) VALUES (new.rowid, new.correctedText); END",
        "INSERT INTO ocr_correction_fts(ocr_correction_fts) VALUES('rebuild')",
    )

    /**
     * P2-08-R1/R5: creates the index when this SQLite build has FTS5 and
     * returns true; otherwise creates nothing (search then uses the LIKE
     * fallback) and returns false. The probe is a temp table, so a build
     * without FTS5 never fails the migration or the open.
     */
    fun create(db: SupportSQLiteDatabase): Boolean {
        if (!fts5Available(db)) return false
        statements().forEach(db::execSQL)
        return true
    }

    // "no such module: fts5" surfaces as the platform's or SQLCipher's
    // SQLiteException; both are RuntimeExceptions and nothing else is thrown here.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun fts5Available(db: SupportSQLiteDatabase): Boolean = try {
        db.execSQL("CREATE VIRTUAL TABLE temp.fts5_probe USING fts5(x)")
        db.execSQL("DROP TABLE temp.fts5_probe")
        true
    } catch (e: RuntimeException) {
        false
    }
}
