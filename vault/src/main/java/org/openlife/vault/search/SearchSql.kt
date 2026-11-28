package org.openlife.vault.search

/**
 * P2-08-R3/R5 and P3-07-R1: the query shapes. All return only what the
 * viewer can show and select (ethos P3):
 * - the source is READY;
 * - OCR text: the revision is the Source's current reading (sources.
 *   currentOcrRevisionId, C2-R14), is READY (so never STALE) and not rejected;
 * - a span whose latest review (highest sequence) is REJECTED is left out;
 * - a correction counts only while it is the span's latest (createdAt, then
 *   rowid — the viewer's "Corrected by you");
 * - a fact (P3-07, C2-R49): the revision is its fact's current revision, its
 *   status is UNREVIEWED, CONFIRMED or CLARIFICATION_NEEDED, and the Source
 *   has a current reading; an EXTRACTED revision also needs a VALUE row that
 *   names that reading. A USER revision is matched by its fact's Source.
 * The user's words arrive only as bound arguments (?1.. for the MATCH
 * expression or LIKE patterns, the last one for the limit).
 */
object SearchSql {
    const val MAX_LIMIT = 200

    data class Statement(val sql: String, val args: List<Any>)

    private const val SHOWN_REVISION =
        "src.state = 'READY' AND r.state = 'READY' AND r.reviewState <> 'REJECTED' " +
            "AND r.id = src.currentOcrRevisionId"

    private const val SHOWN_FACT =
        "src.state = 'READY' AND src.currentOcrRevisionId IS NOT NULL AND f.currentRevisionId = r.id " +
            "AND r.status IN ('UNREVIEWED', 'CONFIRMED', 'CLARIFICATION_NEEDED') " +
            "AND (r.origin = 'USER' OR EXISTS (SELECT 1 FROM fact_evidence e WHERE e.revisionId = r.id " +
            "AND e.role = 'VALUE' AND e.ocrRevisionId = src.currentOcrRevisionId))"

    private const val FACT_SPAN =
        "(SELECT e.ocrSpanId FROM fact_evidence e WHERE e.revisionId = r.id AND e.role = 'VALUE' LIMIT 1)"

    private const val SPAN_NOT_REJECTED =
        "COALESCE((SELECT v.reviewState FROM ocr_span_reviews v WHERE v.spanId = s.id " +
            "ORDER BY v.sequence DESC LIMIT 1), '') <> 'REJECTED'"

    private const val LATEST_CORRECTION =
        "c.rowid = (SELECT newer.rowid FROM ocr_user_revisions newer WHERE newer.revisionId = c.revisionId " +
            "AND newer.spanId IS c.spanId ORDER BY newer.createdAt DESC, newer.rowid DESC LIMIT 1)"

    /** The OCR branches leave the fact columns NULL, so the three shapes union cleanly. */
    private const val NO_FACT = "NULL AS factRevisionId, NULL AS factKind, NULL AS factStatus, NULL AS factOrigin"

    private const val OUTER =
        "SELECT sourceId, revisionId, spanId, text, isCorrection, score, ordinal, " +
            "factRevisionId, factKind, factStatus, factOrigin FROM ("

    private const val ORDER = " ORDER BY score ASC, sourceId ASC, isCorrection ASC, ordinal ASC LIMIT "

    /** FTS5: bm25 ranking (lower is better) across span text, corrections and facts. */
    fun fts(matchExpression: String, limit: Int): Statement {
        val sql = OUTER +
            "SELECT r.sourceId AS sourceId, s.revisionId AS revisionId, s.id AS spanId, s.text AS text, " +
            "0 AS isCorrection, bm25(ocr_text_fts) AS score, s.ordinal AS ordinal, $NO_FACT " +
            "FROM ocr_text_fts JOIN ocr_spans s ON s.rowid = ocr_text_fts.rowid " +
            "JOIN ocr_revisions r ON r.id = s.revisionId JOIN sources src ON src.id = r.sourceId " +
            "WHERE ocr_text_fts MATCH ?1 AND $SHOWN_REVISION AND $SPAN_NOT_REJECTED " +
            "UNION ALL " +
            "SELECT r.sourceId, c.revisionId, c.spanId, c.correctedText, 1, bm25(ocr_correction_fts), " +
            "COALESCE(s.ordinal, -1), $NO_FACT " +
            "FROM ocr_correction_fts JOIN ocr_user_revisions c ON c.rowid = ocr_correction_fts.rowid " +
            "JOIN ocr_revisions r ON r.id = c.revisionId JOIN sources src ON src.id = r.sourceId " +
            "LEFT JOIN ocr_spans s ON s.id = c.spanId " +
            "WHERE ocr_correction_fts MATCH ?1 AND $SHOWN_REVISION AND $LATEST_CORRECTION " +
            "AND (c.spanId IS NULL OR $SPAN_NOT_REJECTED) " +
            "UNION ALL " +
            "SELECT f.sourceId, src.currentOcrRevisionId, $FACT_SPAN, r.canonicalText, 0, " +
            "bm25(fact_fts), f.ordinal, r.id, r.kind, r.status, r.origin " +
            "FROM fact_fts JOIN fact_revisions r ON r.rowid = fact_fts.rowid " +
            "JOIN facts f ON f.id = r.factId JOIN sources src ON src.id = f.sourceId " +
            "WHERE fact_fts MATCH ?1 AND $SHOWN_FACT)" + ORDER + "?2"
        return Statement(sql, listOf(matchExpression, bounded(limit)))
    }

    /**
     * Fallback when the SQLite build has no FTS5 (R5): every pattern must
     * match `lower(text)` (SQLite's lower() folds ASCII only; no diacritic
     * folding, no ranking). Facts are matched on their canonical text.
     */
    fun like(patterns: List<String>, limit: Int): Statement {
        require(patterns.isNotEmpty())
        fun matches(column: String) = patterns.indices.joinToString(" AND ") { index ->
            "lower($column) LIKE ?${index + 1} ESCAPE '\\'"
        }
        val sql = OUTER +
            "SELECT r.sourceId AS sourceId, s.revisionId AS revisionId, s.id AS spanId, s.text AS text, " +
            "0 AS isCorrection, 0.0 AS score, s.ordinal AS ordinal, $NO_FACT " +
            "FROM ocr_spans s JOIN ocr_revisions r ON r.id = s.revisionId JOIN sources src ON src.id = r.sourceId " +
            "WHERE ${matches("s.text")} AND $SHOWN_REVISION AND $SPAN_NOT_REJECTED " +
            "UNION ALL " +
            "SELECT r.sourceId, c.revisionId, c.spanId, c.correctedText, 1, 0.0, COALESCE(s.ordinal, -1), $NO_FACT " +
            "FROM ocr_user_revisions c JOIN ocr_revisions r ON r.id = c.revisionId " +
            "JOIN sources src ON src.id = r.sourceId LEFT JOIN ocr_spans s ON s.id = c.spanId " +
            "WHERE ${matches("c.correctedText")} AND $SHOWN_REVISION AND $LATEST_CORRECTION " +
            "AND (c.spanId IS NULL OR $SPAN_NOT_REJECTED) " +
            "UNION ALL " +
            "SELECT f.sourceId, src.currentOcrRevisionId, $FACT_SPAN, r.canonicalText, 0, 0.0, f.ordinal, " +
            "r.id, r.kind, r.status, r.origin " +
            "FROM fact_revisions r JOIN facts f ON f.id = r.factId JOIN sources src ON src.id = f.sourceId " +
            "WHERE ${matches("r.canonicalText")} AND $SHOWN_FACT)" + ORDER + "?${patterns.size + 1}"
        return Statement(sql, patterns + bounded(limit))
    }

    private fun bounded(limit: Int) = limit.coerceIn(1, MAX_LIMIT)
}
