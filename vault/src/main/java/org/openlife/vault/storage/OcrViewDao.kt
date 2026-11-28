package org.openlife.vault.storage

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Observable reads behind the viewer's persisted OCR state (P2-01-R1). */
@Dao
interface OcrViewDao {
    /**
     * P2-01-R1: newest first by rowid, the same order that moves the current
     * reading (C2-R14, amendment 2026-11-28, S-01). startedAt is not used.
     */
    @Query("SELECT * FROM ocr_revisions WHERE sourceId = :sourceId ORDER BY rowid DESC")
    fun observeRevisionsNewestFirst(sourceId: String): Flow<List<OcrRevisionEntity>>

    @Query("SELECT * FROM ocr_spans WHERE revisionId = :revisionId ORDER BY ordinal ASC")
    fun observeSpans(revisionId: String): Flow<List<OcrSpanEntity>>

    /** Oldest first, insertion order breaking ties, so the last correction per span is the latest. */
    @Query("SELECT * FROM ocr_user_revisions WHERE revisionId = :revisionId ORDER BY createdAt ASC, rowid ASC")
    fun observeUserRevisions(revisionId: String): Flow<List<OcrUserRevisionEntity>>
}
