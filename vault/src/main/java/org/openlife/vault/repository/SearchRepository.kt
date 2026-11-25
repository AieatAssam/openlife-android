package org.openlife.vault.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import org.openlife.vault.search.SearchHit
import org.openlife.vault.storage.SearchDao

/** P2-08-R3/R5: local search over OCR span text and the user's corrections. */
class SearchRepository(
    private val dao: SearchDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun search(query: String, limit: Int = DEFAULT_LIMIT): Flow<List<SearchHit>> = TODO("P2-08 RED")

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}
