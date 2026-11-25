package org.openlife.vault.storage

import androidx.sqlite.db.SupportSQLiteDatabase

/** P2-08-R2/R8 (ADR-0010): the FTS5 index inside the SQLCipher database. */
object SearchIndex {
    const val TEXT_TABLE = "ocr_text_fts"
    const val CORRECTION_TABLE = "ocr_correction_fts"
    const val TOKENIZER = "unicode61 remove_diacritics 2"

    val triggerNames: List<String> = emptyList()

    fun statements(): List<String> = TODO("P2-08 RED")

    fun create(db: SupportSQLiteDatabase): Boolean = TODO("P2-08 RED")
}
