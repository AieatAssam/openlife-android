package org.openlife.vault.storage

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** One row of a search query; the column names are the SQL aliases in [SearchSql]. */
data class SearchRow(
    val sourceId: String,
    val revisionId: String,
    val spanId: String?,
    val text: String,
    val isCorrection: Int,
    val score: Double,
    val ordinal: Int,
    /** P3-07: set on a fact row only (the revision and its stored kind, status and origin). */
    val factRevisionId: String? = null,
    val factKind: String? = null,
    val factStatus: String? = null,
    val factOrigin: String? = null,
)

/**
 * P2-08-R3: Room cannot verify SQL against the FTS5 tables (they are not
 * entities), so the search is a @RawQuery. Its FTS tables change only through
 * triggers on the observed content tables, so observing those is complete.
 */
@Dao
interface SearchDao {
    @RawQuery(
        observedEntities = [
            SourceEntity::class,
            OcrRevisionEntity::class,
            OcrSpanEntity::class,
            OcrUserRevisionEntity::class,
            OcrSpanReviewEntity::class,
            FactEntity::class,
            FactRevisionEntity::class,
            FactEvidenceEntity::class,
        ],
    )
    fun observe(query: SupportSQLiteQuery): Flow<List<SearchRow>>

    @Query(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
            "AND name IN ('ocr_text_fts', 'ocr_correction_fts', 'fact_fts')",
    )
    suspend fun countIndexTables(): Int
}
