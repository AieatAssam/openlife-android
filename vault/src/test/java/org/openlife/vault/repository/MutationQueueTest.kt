package org.openlife.vault.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MutationQueueTest {

    @Test
    fun tryAcquireSucceedsWhenIdle() = runTest {
        val queue = MutationQueue()
        val result = queue.tryAcquire { "done" }
        assertEquals("done", result)
    }

    @Test
    fun tryAcquireFailsWhileAnotherMutationHoldsTheQueue() = runTest {
        val queue = MutationQueue()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async {
            queue.tryAcquire {
                holding.complete(Unit)
                release.await()
            }
        }
        holding.await()

        // A second import attempt while the first is still in progress must
        // be told "busy" immediately, not queued (design §12: at most one
        // concurrent import; ask the user to finish or cancel the first).
        val second = queue.tryAcquire { "should not run" }
        assertNull(second)

        release.complete(Unit)
        first.await()
    }

    @Test
    fun acquireWaitsForAPriorMutationInsteadOfFailing() = runTest {
        val queue = MutationQueue()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async {
            queue.tryAcquire {
                holding.complete(Unit)
                release.await()
            }
        }
        holding.await()

        val second = async { queue.acquire { "deletion ran" } }
        release.complete(Unit)
        first.await()

        assertEquals("deletion ran", second.await())
    }

    @Test
    fun tryAcquireSucceedsAgainAfterThePriorMutationReleases() = runTest {
        val queue = MutationQueue()
        queue.tryAcquire { "first" }
        val second = queue.tryAcquire { "second" }
        assertEquals("second", second)
    }
}
