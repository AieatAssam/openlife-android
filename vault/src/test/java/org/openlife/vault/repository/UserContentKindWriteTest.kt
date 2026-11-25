package org.openlife.vault.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.crypto.PlatformExceptions
import org.openlife.vault.model.UserContentKind
import org.openlife.vault.storage.SourceDao
import org.openlife.vault.storage.SourceEntity
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * P2-07-R3 and review T-03 on the JVM through the DAO seam: the label write
 * passes the stored name and the time, reports a lost compare-and-set as
 * false, turns a database error (a closed or failing database) into false
 * instead of an exception, and lets cancellation through.
 */
class UserContentKindWriteTest {
    private val id = UUID(0, 7)

    @Test
    fun theStoredNameAndTimeAreWrittenAndOneRowIsSaved(): Unit = runBlocking {
        var written: Triple<String, String?, Long>? = null
        val dao = LabelDao { rowId, kind, at ->
            written = Triple(rowId, kind, at)
            1
        }
        assertTrue(dao.writeUserContentKind(id, UserContentKind.COMMUNICATION, 42))
        assertEquals(Triple(id.toString(), "COMMUNICATION", 42L), written)
    }

    @Test
    fun notSureWritesNoKindWithItsTime(): Unit = runBlocking {
        var written: Pair<String?, Long>? = null
        val dao = LabelDao { _, kind, at ->
            written = kind to at
            1
        }
        assertTrue(dao.writeUserContentKind(id, null, 9))
        assertNull(written!!.first)
        assertEquals(9L, written!!.second)
    }

    @Test
    fun aRowThatIsNotReadyIsNotSaved(): Unit = runBlocking {
        assertFalse(LabelDao { _, _, _ -> 0 }.writeUserContentKind(id, UserContentKind.NOT_COMMUNICATION, 1))
    }

    @Test
    fun aDatabaseErrorIsNotSavedRatherThanThrown(): Unit = runBlocking {
        val sqlite = PlatformExceptions.sqliteException()
        assertFalse(LabelDao { _, _, _ -> throw sqlite }.writeUserContentKind(id, null, 1))
        val closed = IllegalStateException("closed database")
        assertFalse(LabelDao { _, _, _ -> throw closed }.writeUserContentKind(id, null, 1))
    }

    @Test
    fun cancellationReachesTheCaller(): Unit = runBlocking {
        val outcome = runCatching {
            LabelDao { _, _, _ -> throw CancellationException("the viewer went away") }
                .writeUserContentKind(id, UserContentKind.COMMUNICATION, 1)
        }
        assertTrue(
            "expected CancellationException, got ${outcome.getOrNull() ?: outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is CancellationException,
        )
    }

    private class LabelDao(private val label: (String, String?, Long) -> Int) : SourceDao {
        override suspend fun setUserContentKind(id: String, kind: String?, at: Long): Int = label(id, kind, at)

        override suspend fun transitionState(id: String, from: String, to: String): Int = unused()

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

        override suspend fun findById(id: String): SourceEntity? = unused()

        override suspend fun count(): Int = unused()

        override suspend fun deleteById(id: String) = unused()

        override suspend fun deleteStaged(id: String): Int = unused()

        override suspend fun findAll(): List<SourceEntity> = unused()

        override suspend fun findReadyDuplicate(byteCount: Long, sha256: ByteArray): SourceEntity? = unused()

        override fun observeVisibleSources(): Flow<List<SourceEntity>> = unused()

        private fun unused(): Nothing = throw AssertionError("not used by the label write")
    }
}
