package org.openlife.vault.search

/**
 * P2-08-R3/R5: the two query shapes. Both return only what the viewer can
 * show and select (ethos P3):
 * - the source is READY;
 * - the revision is the one the viewer shows (the latest by startedAt, then
 *   rowid — OcrViewDao's order), is READY (so never STALE) and not rejected;
 * - a span whose latest review (highest sequence) is REJECTED is left out;
 * - a correction counts only while it is the span's latest (createdAt, then
 *   rowid — the viewer's "Corrected by you").
 * The user's words arrive only as bound arguments (?1.. for the MATCH
 * expression or LIKE patterns, the last one for the limit).
 */
object SearchSql {
    const val MAX_LIMIT = 200

    data class Statement(val sql: String, val args: List<Any>)

    private const val SHOWN_REVISION =
        "src.state = 'READY' AND r.state = 'READY' AND r.reviewState <> 'REJECTED' AND r.id = (" +
            "SELECT latest.id FROM ocr_revisions latest WHERE latest.sourceId = r.sourceId " +
            "ORDER BY latest.startedAt DESC, latest.rowid DESC LIMIT 1)"

    private const val SPAN_NOT_REJECTED =
        "COALESCE((SELECT v.reviewState FROM ocr_span_reviews v WHERE v.spanId = s.id " +
            "ORDER BY v.sequence DESC LIMIT 1), '') <> 'REJECTED'"

    private const val LATEST_CORRECTION =
        "c.rowid = (SELECT newer.rowid FROM ocr_user_revisions newer WHERE newer.revisionId = c.revisionId " +
            "AND newer.spanId IS c.spanId ORDER BY newer.createdAt DESC, newer.rowid DESC LIMIT 1)"

    private const val ORDER = " ORDER BY score ASC, sourceId ASC, isCorrection ASC, ordinal ASC LIMIT "

    /** FTS5: bm25 ranking (lower is better) across span text and corrections. */
    fun fts(matchExpression: String, limit: Int): Statement {
        val sql = "SELECT sourceId, revisionId, spanId, text, isCorrection, score, ordinal FROM (" +
            "SELECT r.sourceId AS sourceId, s.revisionId AS revisionId, s.id AS spanId, s.text AS text, " +
            "0 AS isCorrection, bm25(ocr_text_fts) AS score, s.ordinal AS ordinal " +
            "FROM ocr_text_fts JOIN ocr_spans s ON s.rowid = ocr_text_fts.rowid " +
            "JOIN ocr_revisions r ON r.id = s.revisionId JOIN sources src ON src.id = r.sourceId " +
            "WHERE ocr_text_fts MATCH ?1 AND $SHOWN_REVISION AND $SPAN_NOT_REJECTED " +
            "UNION ALL " +
            "SELECT r.sourceId, c.revisionId, c.spanId, c.correctedText, 1, bm25(ocr_correction_fts), " +
            "COALESCE(s.ordinal, -1) " +
            "FROM ocr_correction_fts JOIN ocr_user_revisions c ON c.rowid = ocr_correction_fts.rowid " +
            "JOIN ocr_revisions r ON r.id = c.revisionId JOIN sources src ON src.id = r.sourceId " +
            "LEFT JOIN ocr_spans s ON s.id = c.spanId " +
            "WHERE ocr_correction_fts MATCH ?1 AND $SHOWN_REVISION AND $LATEST_CORRECTION " +
            "AND (c.spanId IS NULL OR $SPAN_NOT_REJECTED))" + ORDER + "?2"
        return Statement(sql, listOf(matchExpression, bounded(limit)))
    }

    /**
     * Fallback when the SQLite build has no FTS5 (R5): every pattern must
     * match `lower(text)` (SQLite's lower() folds ASCII only; no diacritic
     * folding, no ranking).
     */
    fun like(patterns: List<String>, limit: Int): Statement {
        require(patterns.isNotEmpty())
        fun matches(column: String) = patterns.indices.joinToString(" AND ") { index ->
            "lower($column) LIKE ?${index + 1} ESCAPE '\\'"
        }
        val sql = "SELECT sourceId, revisionId, spanId, text, isCorrection, score, ordinal FROM (" +
            "SELECT r.sourceId AS sourceId, s.revisionId AS revisionId, s.id AS spanId, s.text AS text, " +
            "0 AS isCorrection, 0.0 AS score, s.ordinal AS ordinal " +
            "FROM ocr_spans s JOIN ocr_revisions r ON r.id = s.revisionId JOIN sources src ON src.id = r.sourceId " +
            "WHERE ${matches("s.text")} AND $SHOWN_REVISION AND $SPAN_NOT_REJECTED " +
            "UNION ALL " +
            "SELECT r.sourceId, c.revisionId, c.spanId, c.correctedText, 1, 0.0, COALESCE(s.ordinal, -1) " +
            "FROM ocr_user_revisions c JOIN ocr_revisions r ON r.id = c.revisionId " +
            "JOIN sources src ON src.id = r.sourceId LEFT JOIN ocr_spans s ON s.id = c.spanId " +
            "WHERE ${matches("c.correctedText")} AND $SHOWN_REVISION AND $LATEST_CORRECTION " +
            "AND (c.spanId IS NULL OR $SPAN_NOT_REJECTED))" + ORDER + "?${patterns.size + 1}"
        return Statement(sql, patterns + bounded(limit))
    }

    private fun bounded(limit: Int) = limit.coerceIn(1, MAX_LIMIT)
}
