package org.openlife.vault.storage

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Observable reads behind the viewer's persisted OCR state (P2-01-R1). */
@Dao
interface OcrViewDao {
    /**
     * P2-01-R1: newest first. startedAt can tie when two runs start in the same
     * millisecond, so the insertion order (rowid) breaks the tie.
     */
    @Query("SELECT * FROM ocr_revisions WHERE sourceId = :sourceId ORDER BY startedAt DESC, rowid DESC")
    fun observeRevisionsNewestFirst(sourceId: String): Flow<List<OcrRevisionEntity>>

    @Query("SELECT * FROM ocr_spans WHERE revisionId = :revisionId ORDER BY ordinal ASC")
    fun observeSpans(revisionId: String): Flow<List<OcrSpanEntity>>

    /** Oldest first, insertion order breaking ties, so the last correction per span is the latest. */
    @Query("SELECT * FROM ocr_user_revisions WHERE revisionId = :revisionId ORDER BY createdAt ASC, rowid ASC")
    fun observeUserRevisions(revisionId: String): Flow<List<OcrUserRevisionEntity>>
}
