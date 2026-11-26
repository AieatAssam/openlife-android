package org.openlife.vault.storage

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Synthetic C2 rows for instrumented tests (AGENTS.md: fixtures are
 * generated, never real). Every id is a UUID string; the reading and span
 * ids derive from the source id, so a test can name them.
 */
class FactDeviceRows(private val db: SupportSQLiteDatabase) {
    private var clock = 10_000L

    fun readySourceWithReading(sourceId: String, insertSource: Boolean = true) {
        if (insertSource) {
            db.execSQL(
                "INSERT INTO sources(id, state, importedAt, intakeKind, mimeType, byteCount, sha256, width, height, " +
                    "orientation, wrappedDek, artefactVersion) VALUES (?, 'READY', ?, 'SHARE', 'image/png', 10, " +
                    "X'01', 10, 10, 'NORMAL', X'02', 1)",
                arrayOf<Any?>(sourceId, clock++),
            )
        }
        db.execSQL(
            "INSERT INTO ocr_revisions(id, sourceId, state, engineId, modelVersion, orientation, sourceDigest, " +
                "startedAt, extractedAt, reviewState, failureReason, charCount, spanCount) VALUES (?, ?, 'READY', " +
                "'test', '1', 'NORMAL', X'01', ?, ?, 'UNREVIEWED', NULL, 0, 2)",
            arrayOf<Any?>(readingId(sourceId), sourceId, clock, clock++),
        )
        listOf("Due 26.11.2026", "Total EUR 12.50").forEachIndexed { index, text ->
            db.execSQL(
                "INSERT INTO ocr_spans(id, revisionId, ordinal, text, confidence, coordinateSystem, `left`, top, " +
                    "`right`, bottom, hasEvidenceRegion, scriptStatus) VALUES (?, ?, ?, ?, NULL, 'SOURCE_PIXELS', " +
                    "1, 1, 5, 5, 1, 'LATIN')",
                arrayOf<Any?>(spanId(sourceId, index + 1), readingId(sourceId), index, text),
            )
        }
        db.execSQL("UPDATE sources SET currentOcrRevisionId = ? WHERE id = ?", arrayOf<Any?>(readingId(sourceId), sourceId))
    }

    /** Fact [factId] with EXTRACTED revision [revisionId], current, with VALUE evidence on `<source>-s1`. */
    fun extractedFact(sourceId: String, factId: String, revisionId: String) {
        db.execSQL(
            FactSql.INSERT_FACT.positional(),
            arrayOf<Any?>(factId, sourceId, "DATE", clock++, sourceId, "DATE"),
        )
        db.execSQL(
            "INSERT INTO fact_revisions(id, factId, revisionNo, origin, kind, valueJson, canonicalText, rawText, " +
                "status, staleReason, staleAt, confidence, methodId, methodVersion, locale, createdAt, " +
                "supersedesRevisionId) VALUES (?, ?, 1, 'EXTRACTED', 'DATE', '{\"date\":\"2026-11-26\"}', " +
                "'2026-11-26', '26.11.2026', 'UNREVIEWED', NULL, NULL, NULL, 'date:numeric', '1', 'en-GB', ?, NULL)",
            arrayOf<Any?>(revisionId, factId, clock++),
        )
        db.execSQL("UPDATE facts SET currentRevisionId = ? WHERE id = ?", arrayOf<Any?>(revisionId, factId))
        db.execSQL(
            "INSERT INTO fact_evidence(id, revisionId, ordinal, role, sourceId, ocrRevisionId, ocrSpanId, " +
                "ocrUserRevisionId, textStart, textEnd, regionLeft, regionTop, regionRight, regionBottom, " +
                "regionCoordinateSystem) VALUES (?, ?, 0, 'VALUE', ?, ?, ?, NULL, 4, 14, 1, 1, 5, 5, 'SOURCE_PIXELS')",
            arrayOf<Any?>(java.util.UUID.randomUUID().toString(), revisionId, sourceId, readingId(sourceId), spanId(sourceId, 1)),
        )
    }

    fun run(sourceId: String) {
        db.execSQL(
            "INSERT INTO fact_extraction_runs(id, sourceId, ocrRevisionId, `trigger`, rulesVersion, startedAt, " +
                "finishedAt, outcome, skippedKinds, candidateCount, createdRevisionCount, skippedLineCount) " +
                "VALUES (?, ?, ?, 'AUTO', '1', ?, ?, 'COMPLETED', NULL, 1, 1, 0)",
            arrayOf<Any?>("$sourceId-run", sourceId, readingId(sourceId), clock, clock++),
        )
    }

    fun count(table: String): Int = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    fun text(sql: String): String? = db.query(sql).use { if (it.moveToFirst()) it.getString(0) else null }

    fun readingId(sourceId: String): String = derived(sourceId, "r")

    fun spanId(sourceId: String, number: Int): String = derived(sourceId, "s$number")

    private fun derived(sourceId: String, suffix: String) =
        java.util.UUID.nameUUIDFromBytes("$sourceId-$suffix".toByteArray()).toString()

    private fun String.positional(): String = replace(Regex(":[A-Za-z]+"), "?")
}
