package org.openlife.vault.storage

import org.openlife.vault.ocr.OcrScriptPolicy
import org.openlife.vault.ocr.OcrScriptStatus

/**
 * VS-3 (P3-02-R7) SQL for the current-reading pointer (C2-R14) and the
 * correction supersession chain (C2-R15), as constants so the host tests run
 * the statements Room runs.
 */
object OcrPointerSql {
    /**
     * Run in the transaction that commits a reading READY: moves the pointer
     * only when there is no current reading or this one started later, by
     * `(startedAt, rowid)`. A late finisher that started earlier changes 0 rows.
     */
    const val MOVE_CURRENT_READING = """
        UPDATE sources SET currentOcrRevisionId = :revisionId
        WHERE id = :sourceId
          AND EXISTS (SELECT 1 FROM ocr_revisions WHERE id = :revisionId AND sourceId = :sourceId AND state = 'READY')
          AND (currentOcrRevisionId IS NULL OR EXISTS (
            SELECT 1 FROM ocr_revisions n JOIN ocr_revisions c ON c.id = sources.currentOcrRevisionId
            WHERE n.id = :revisionId
              AND (n.startedAt > c.startedAt OR (n.startedAt = c.startedAt AND n.rowid > c.rowid))))
        """

    /** The head of a span's (or, with a null span, a reading's) correction chain; none if uncorrected. */
    const val CURRENT_CORRECTION = """
        SELECT c.id FROM ocr_user_revisions c
        WHERE c.revisionId = :revisionId AND c.spanId IS :spanId
          AND NOT EXISTS (SELECT 1 FROM ocr_user_revisions n WHERE n.supersedesId = c.id)
        """
}

/** C2-R17: the stored `ocr_spans.scriptStatus` of a span's own text. */
fun spanScriptStatusOf(text: String): String = when (OcrScriptPolicy.classify(text)) {
    OcrScriptStatus.LATIN -> SpanScriptStatus.LATIN.name
    OcrScriptStatus.UNSUPPORTED -> SpanScriptStatus.UNSUPPORTED.name
}
