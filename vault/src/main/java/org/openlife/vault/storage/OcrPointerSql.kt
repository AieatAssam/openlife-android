package org.openlife.vault.storage

/**
 * VS-3 (P3-02-R7) SQL for the current-reading pointer (C2-R14) and the
 * correction supersession chain (C2-R15), as constants so the host tests run
 * the statements Room runs.
 */
object OcrPointerSql {
    const val MOVE_CURRENT_READING = "SELECT 1 WHERE :sourceId IS NULL AND :revisionId IS NULL"

    const val CURRENT_CORRECTION = "SELECT NULL WHERE :revisionId IS NULL AND :spanId IS NULL"
}

/** C2-R17: the stored `ocr_spans.scriptStatus` of a span's own text. */
fun spanScriptStatusOf(text: String): String = TODO("P3-02 GREEN")
