package org.openlife.vault.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.storage.SourceDao
import org.openlife.vault.storage.SourceEntity
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * P1-18-R9 (V-08), review R-02: the final STAGED -> READY commit of a save.
 * A cancellation that arrives once the commit is durable must reach the
 * caller as a CancellationException; it must never become SaveResult.Failed
 * for an item that is saved. Proven on the JVM through the DAO seam.
 */
class SaveCommitTest {
    private val id = UUID.randomUUID()

    @Test
    fun cancellationAfterTheReadyCommitReachesTheCaller(): Unit = runBlocking {
        var committed = false
        val dao = TransitionDao { _, from, to ->
            assertEquals("STAGED" to "READY", from to to)
            committed = true
            throw CancellationException("the caller went away as the commit returned")
        }

        val outcome = runCatching { dao.commitReady(id) }

        assertTrue("the commit ran", committed)
        assertTrue(
            "expected CancellationException, got ${outcome.getOrNull() ?: outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is CancellationException,
        )
    }

    @Test
    fun aCommittedRowIsSavedAndALostRaceOrRejectedCommitIsFailed(): Unit = runBlocking {
        assertEquals(SaveResult.Saved(id), TransitionDao { _, _, _ -> 1 }.commitReady(id))
        assertEquals(SaveResult.Failed, TransitionDao { _, _, _ -> 0 }.commitReady(id))
        assertEquals(
            SaveResult.Failed,
            TransitionDao { _, _, _ -> throw IllegalStateException("READY invariant trigger") }.commitReady(id),
        )
    }

    private class TransitionDao(private val transition: (String, String, String) -> Int) : SourceDao {
        override suspend fun transitionState(id: String, from: String, to: String): Int = transition(id, from, to)

        override suspend fun insert(source: SourceEntity) = unused()

        override suspend fun update(source: SourceEntity) = unused()

        override suspend fun updateStagedMetadata(
            id: String,
            mimeType: String,
            byteCount: Long,
            sha256: ByteArray,
            width: Int,
            height: Int,
            orientation: String,
            artefactVersion: Int,
        ): Int = unused()

        override suspend fun setUserContentKind(id: String, kind: String?, at: Long): Int = unused()

        override suspend fun findById(id: String): SourceEntity? = unused()

        override suspend fun count(): Int = unused()

        override suspend fun deleteById(id: String) = unused()

        override suspend fun deleteStaged(id: String): Int = unused()

        override suspend fun findAll(): List<SourceEntity> = unused()

        override suspend fun findReadyDuplicate(byteCount: Long, sha256: ByteArray): SourceEntity? = unused()

        override fun observeVisibleSources(): Flow<List<SourceEntity>> = unused()

        override fun observeVisibleSource(id: String): Flow<SourceEntity?> = unused()

        private fun unused(): Nothing = throw AssertionError("not used by the commit")
    }
}
