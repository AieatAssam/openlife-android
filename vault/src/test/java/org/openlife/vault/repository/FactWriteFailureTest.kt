package org.openlife.vault.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.facts.FactWriteResult
import org.openlife.vault.storage.HostSqlite

/**
 * P3-02 review M-06: a FactRepository write that fails in storage returns a
 * typed [FactWriteResult.Failed], as DeletionRepository returns
 * DeleteResult.Failed, and never hands a raw SQLite or `check()` exception
 * (whose message could carry SQL) to the caller. Cancellation is rethrown.
 */
class FactWriteFailureTest {
    @Test
    fun storageFailuresBecomeATypedResultAndCancellationIsRethrown() = runBlocking {
        val ok = FactWriteResult.Refused(org.openlife.vault.facts.FactRefusal.NOT_ALLOWED)
        assertEquals(ok, captureWriteFailure { ok })
        assertEquals(FactWriteResult.Failed, captureWriteFailure { throw IllegalStateException("lost race") })
        assertEquals(FactWriteResult.Failed, captureWriteFailure { throw HostSqlite.Failure("C2-R20: ...") })
        val cancelled = runCatching { captureWriteFailure { throw CancellationException("stop") } }
        assertTrue("${cancelled.exceptionOrNull()}", cancelled.exceptionOrNull() is CancellationException)
        assertEquals("Failed", FactWriteResult.Failed.toString())
    }
}
