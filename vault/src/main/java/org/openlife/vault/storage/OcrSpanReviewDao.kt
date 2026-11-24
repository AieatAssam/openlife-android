package org.openlife.vault.storage

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Span reviews are append-only: there is no update or delete (P2-06-R13). */
@Dao
interface OcrSpanReviewDao {
    /**
     * Appends a review with the next per-span sequence (max + 1) in one
     * statement, so the latest row never depends on the wall clock (P2-06-R12).
     */
    @Query(
        """
        INSERT INTO ocr_span_reviews(id, spanId, reviewState, createdAt, sequence)
        SELECT :id, :spanId, :reviewState, :createdAt, COALESCE(MAX(sequence), 0) + 1
        FROM ocr_span_reviews WHERE spanId = :spanId
        """,
    )
    suspend fun append(id: String, spanId: String, reviewState: String, createdAt: Long)

    @Query("SELECT * FROM ocr_span_reviews WHERE spanId = :spanId ORDER BY sequence ASC")
    suspend fun findForSpan(spanId: String): List<OcrSpanReviewEntity>

    @Query(
        """
        SELECT ocr_span_reviews.* FROM ocr_span_reviews
        INNER JOIN ocr_spans ON ocr_spans.id = ocr_span_reviews.spanId
        WHERE ocr_spans.revisionId = :revisionId
        ORDER BY ocr_span_reviews.spanId ASC, ocr_span_reviews.sequence ASC
        """,
    )
    fun observeForRevision(revisionId: String): Flow<List<OcrSpanReviewEntity>>

    @Query("SELECT COUNT(*) FROM ocr_span_reviews")
    suspend fun count(): Int
}
