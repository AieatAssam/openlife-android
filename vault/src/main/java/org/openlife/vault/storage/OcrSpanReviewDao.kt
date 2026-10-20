package org.openlife.vault.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OcrSpanReviewDao {
    @Insert
    suspend fun insert(review: OcrSpanReviewEntity)

    @Query("SELECT * FROM ocr_span_reviews WHERE spanId = :spanId ORDER BY createdAt ASC, rowid ASC")
    suspend fun findForSpan(spanId: String): List<OcrSpanReviewEntity>

    @Query(
        """
        SELECT ocr_span_reviews.* FROM ocr_span_reviews
        INNER JOIN ocr_spans ON ocr_spans.id = ocr_span_reviews.spanId
        WHERE ocr_spans.revisionId = :revisionId
        ORDER BY ocr_span_reviews.createdAt ASC, ocr_span_reviews.rowid ASC
        """,
    )
    fun observeForRevision(revisionId: String): Flow<List<OcrSpanReviewEntity>>

    @Query("SELECT COUNT(*) FROM ocr_span_reviews")
    suspend fun count(): Int
}
