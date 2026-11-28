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
import org.openlife.vault.search.SearchFact
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
 * LIKE fallback otherwise, with the same results type. Input in a script the
 * word index cannot match as typed (text without spaces, Indic vowel signs;
 * review W-02) is searched with the LIKE path even when the index exists. Read-only: it never
 * writes the index (the triggers do) and never logs the query or a result.
 * The flow re-emits when the content tables change.
 */
class SearchRepository(private val dao: SearchDao, private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    /** The app's entry point: callers outside the vault never see Room types. */
    constructor(database: OpenLifeDatabase) : this(database.searchDao())

    @Volatile
    private var hasIndex: Boolean? = null

    fun search(query: String, limit: Int = DEFAULT_LIMIT): Flow<List<SearchHit>> {
        val tokens = SearchQuery.tokens(query)
        if (tokens.isEmpty()) return flowOf(emptyList())
        val substring = SearchQuery.needsSubstringSearch(query)
        return flow {
            val indexed = hasIndex ?: (dao.countIndexTables() == INDEX_TABLES).also { hasIndex = it }
            val words = indexed && !substring
            val terms = if (words) tokens else SearchQuery.substringTerms(query)
            val statement = if (words) {
                SearchSql.fts(checkNotNull(SearchQuery.matchExpression(tokens)), limit)
            } else {
                SearchSql.like(SearchQuery.likePatterns(terms), limit)
            }
            emitAll(
                dao.observe(SimpleSQLiteQuery(statement.sql, statement.args.toTypedArray()))
                    .map { rows -> rows.mapNotNull { hitOf(it, terms, wholeWords = words) } },
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

        /** The two OCR tables and the fact table: all three must exist for the FTS5 path. */
        private const val INDEX_TABLES = 3

        /** Tolerant decoding: a row whose ids are not UUIDs (another build) is skipped, never shown half-linked. */
        internal fun hitOf(row: SearchRow, tokens: List<String>, wholeWords: Boolean = true): SearchHit? {
            val sourceId = row.sourceId.toUuidOrNull() ?: return null
            val revisionId = row.revisionId.toUuidOrNull() ?: return null
            val spanId = row.spanId?.let { it.toUuidOrNull() ?: return null }
            val fact = row.factRevisionId?.let { id ->
                SearchFact(
                    revisionId = id.toUuidOrNull() ?: return null,
                    kind = row.factKind.orEmpty(),
                    status = row.factStatus.orEmpty(),
                    origin = row.factOrigin.orEmpty(),
                )
            }
            val snippet = SearchSnippet.of(row.text, tokens, wholeWords)
            return SearchHit(
                sourceId,
                revisionId,
                spanId,
                snippet.text,
                row.isCorrection != 0,
                snippet.highlights,
                fact,
            )
        }

        private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
    }
}
