package org.openlife.vault.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.openlife.vault.search.SearchHit
import org.openlife.vault.search.SearchQuery
import org.openlife.vault.search.SearchSnippet
import org.openlife.vault.search.SearchSql
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.SearchDao
import org.openlife.vault.storage.SearchRow
import java.util.UUID

/**
 * P2-08-R3/R5: local search over OCR span text and the user's corrections,
 * inside the encrypted database. Uses the FTS5 index when it exists and the
 * LIKE fallback otherwise, with the same results type. Read-only: it never
 * writes the index (the triggers do) and never logs the query or a result.
 * The flow re-emits when the content tables change.
 */
class SearchRepository(
    private val dao: SearchDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The app's entry point: callers outside the vault never see Room types. */
    constructor(database: OpenLifeDatabase) : this(database.searchDao())

    @Volatile
    private var hasIndex: Boolean? = null

    fun search(query: String, limit: Int = DEFAULT_LIMIT): Flow<List<SearchHit>> {
        val tokens = SearchQuery.tokens(query)
        if (tokens.isEmpty()) return flowOf(emptyList())
        return flow {
            val indexed = hasIndex ?: (dao.countIndexTables() == INDEX_TABLES).also { hasIndex = it }
            val statement = if (indexed) {
                SearchSql.fts(checkNotNull(SearchQuery.matchExpression(tokens)), limit)
            } else {
                SearchSql.like(SearchQuery.likePatterns(tokens), limit)
            }
            emitAll(
                dao.observe(SimpleSQLiteQuery(statement.sql, statement.args.toTypedArray()))
                    .map { rows -> rows.mapNotNull { hitOf(it, tokens) } },
            )
        }.catch { error ->
            // A failed query shows no results. The message is never logged:
            // SQLite errors can echo the query text.
            if (error is CancellationException) throw error
            emit(emptyList())
        }.flowOn(ioDispatcher)
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        private const val INDEX_TABLES = 2

        /** Tolerant decoding: a row whose ids are not UUIDs (another build) is skipped, never shown half-linked. */
        internal fun hitOf(row: SearchRow, tokens: List<String>): SearchHit? {
            val sourceId = row.sourceId.toUuidOrNull() ?: return null
            val revisionId = row.revisionId.toUuidOrNull() ?: return null
            val spanId = row.spanId?.let { it.toUuidOrNull() ?: return null }
            val snippet = SearchSnippet.of(row.text, tokens)
            return SearchHit(sourceId, revisionId, spanId, snippet.text, row.isCorrection != 0, snippet.highlights)
        }

        private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
    }
}
