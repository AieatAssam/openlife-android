package org.openlife.vault.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * OCR rows. Revision state changes are compare-and-set statements that
 * return the number of rows changed (0 = lost race, nothing written), so a
 * stale snapshot is never written back (P1-18-R13). There is no @Update.
 * One SQL statement per transition is the point, hence the function count.
 */
@Suppress("TooManyFunctions") // one compare-and-set statement per state transition (P1-18-R13)
@Dao
interface OcrDao {
    @Insert
    suspend fun insertRevision(revision: OcrRevisionEntity)

    @Insert
    suspend fun insertSpans(spans: List<OcrSpanEntity>)

    @Insert
    suspend fun insertUserRevision(revision: OcrUserRevisionEntity)

    /** RUNNING -> READY with its counts; 0 when the revision is no longer RUNNING. */
    @Query(
        """
        UPDATE ocr_revisions SET state = 'READY', extractedAt = :extractedAt, charCount = :charCount,
            spanCount = :spanCount
        WHERE id = :id AND state = 'RUNNING'
        """,
    )
    suspend fun completeRunning(id: String, extractedAt: Long, charCount: Int, spanCount: Int): Int

    /** RUNNING -> a terminal state with its reason; 0 when the revision is no longer RUNNING. */
    @Query("UPDATE ocr_revisions SET state = :state, failureReason = :reason WHERE id = :id AND state = 'RUNNING'")
    suspend fun finishRunning(id: String, state: String, reason: String): Int

    /** Review of a READY revision only; 0 when it is not READY. */
    @Query("UPDATE ocr_revisions SET reviewState = :reviewState WHERE id = :id AND state = 'READY'")
    suspend fun setReviewState(id: String, reviewState: String): Int

    @Query("SELECT * FROM ocr_revisions WHERE id = :id")
    suspend fun findRevision(id: String): OcrRevisionEntity?

    /** P3-05 review U-01: a revision only when it belongs to [sourceId]; another source's reading is not found. */
    @Query("SELECT * FROM ocr_revisions WHERE id = :id AND sourceId = :sourceId")
    suspend fun findRevisionOfSource(id: String, sourceId: String): OcrRevisionEntity?

    /** Oldest first by rowid (insertion order); startedAt is display data only (C2-R14, S-01). */
    @Query("SELECT * FROM ocr_revisions WHERE sourceId = :sourceId ORDER BY rowid ASC")
    suspend fun findRevisionsForSource(sourceId: String): List<OcrRevisionEntity>

    @Query("SELECT * FROM ocr_spans WHERE revisionId = :revisionId ORDER BY ordinal ASC")
    suspend fun findSpans(revisionId: String): List<OcrSpanEntity>

    @Query("SELECT * FROM ocr_spans WHERE id = :spanId")
    suspend fun findSpan(spanId: String): OcrSpanEntity?

    @Query("SELECT * FROM ocr_user_revisions WHERE revisionId = :revisionId ORDER BY createdAt ASC")
    suspend fun findUserRevisions(revisionId: String): List<OcrUserRevisionEntity>

    /** C2-R14: run in the transaction that commits [revisionId] READY; 0 when it does not become current. */
    @Query(OcrPointerSql.MOVE_CURRENT_READING)
    suspend fun moveCurrentReading(sourceId: String, revisionId: String): Int

    /** C2-R15: the head of the correction chain of [spanId] (null: the whole reading), or null. */
    @Query(OcrPointerSql.CURRENT_CORRECTION)
    suspend fun currentCorrection(revisionId: String, spanId: String?): String?

    @Query("UPDATE ocr_revisions SET state = 'STALE', failureReason = 'PROCESS_RESTART' WHERE state = 'RUNNING'")
    suspend fun markRunningStale(): Int

    @Query("SELECT COUNT(*) FROM ocr_revisions")
    suspend fun countRevisions(): Int

    @Query("SELECT COUNT(*) FROM ocr_spans")
    suspend fun countSpans(): Int

    @Query("SELECT COUNT(*) FROM ocr_user_revisions")
    suspend fun countUserRevisions(): Int
}
