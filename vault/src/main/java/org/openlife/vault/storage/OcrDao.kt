package org.openlife.vault.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OcrDao {
    @Insert
    suspend fun insertRevision(revision: OcrRevisionEntity)

    @Insert
    suspend fun insertSpans(spans: List<OcrSpanEntity>)

    @Insert
    suspend fun insertUserRevision(revision: OcrUserRevisionEntity)

    @Update
    suspend fun updateRevision(revision: OcrRevisionEntity)

    @Query("SELECT * FROM ocr_revisions WHERE id = :id")
    suspend fun findRevision(id: String): OcrRevisionEntity?

    @Query("SELECT * FROM ocr_revisions WHERE sourceId = :sourceId ORDER BY startedAt ASC")
    suspend fun findRevisionsForSource(sourceId: String): List<OcrRevisionEntity>

    @Query("SELECT * FROM ocr_revisions WHERE sourceId = :sourceId ORDER BY startedAt ASC")
    fun observeRevisionsForSource(sourceId: String): Flow<List<OcrRevisionEntity>>

    @Query("SELECT * FROM ocr_spans WHERE revisionId = :revisionId ORDER BY ordinal ASC")
    suspend fun findSpans(revisionId: String): List<OcrSpanEntity>

    @Query("SELECT * FROM ocr_spans WHERE id = :spanId")
    suspend fun findSpan(spanId: String): OcrSpanEntity?

    @Query("SELECT * FROM ocr_user_revisions WHERE revisionId = :revisionId ORDER BY createdAt ASC")
    suspend fun findUserRevisions(revisionId: String): List<OcrUserRevisionEntity>

    @Query("DELETE FROM ocr_revisions WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    @Query("UPDATE ocr_revisions SET state = 'STALE', failureReason = 'PROCESS_RESTART' WHERE state = 'RUNNING'")
    suspend fun markRunningStale(): Int

    @Query("SELECT COUNT(*) FROM ocr_revisions")
    suspend fun countRevisions(): Int

    @Query("SELECT COUNT(*) FROM ocr_spans")
    suspend fun countSpans(): Int

    @Query("SELECT COUNT(*) FROM ocr_user_revisions")
    suspend fun countUserRevisions(): Int
}
