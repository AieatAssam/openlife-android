package org.openlife.vault.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MutationQueueTest {

    @Test
    fun tryMutationSucceedsWhenIdle() = runTest {
        val queue = MutationQueue()
        val result = queue.tryMutation { "done" }
        assertEquals("done", result)
    }

    @Test
    fun tryMutationFailsWhileAnotherMutationHoldsTheQueue() = runTest {
        val queue = MutationQueue()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async {
            queue.tryMutation {
                holding.complete(Unit)
                release.await()
            }
        }
        holding.await()

        // A second import attempt while the first is still in progress must
        // be told "busy" immediately, not queued (design §12: at most one
        // concurrent import; ask the user to finish or cancel the first).
        val second = queue.tryMutation { "should not run" }
        assertNull(second)

        release.complete(Unit)
        first.await()
    }

    @Test
    fun withMutationWaitsForAPriorMutationInsteadOfFailing() = runTest {
        val queue = MutationQueue()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async {
            queue.tryMutation {
                holding.complete(Unit)
                release.await()
            }
        }
        holding.await()

        val second = async { queue.withMutation { "deletion ran" } }
        release.complete(Unit)
        first.await()

        assertEquals("deletion ran", second.await())
    }

    @Test
    fun tryMutationSucceedsAgainAfterThePriorMutationReleases() = runTest {
        val queue = MutationQueue()
        queue.tryMutation { "first" }
        val second = queue.tryMutation { "second" }
        assertEquals("second", second)
    }

    // --- P1-15: read leases versus mutations (design §11) ---

    @Test
    fun concurrentReadLeasesDoNotBlockEachOther() = runTest {
        val queue = MutationQueue()
        val firstIn = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            queue.withReadLease {
                firstIn.complete(Unit)
                release.await()
            }
        }
        firstIn.await()

        val second = async { queue.withReadLease { "second read" } }
        runCurrent()
        assertTrue("a second reader must not wait for the first", second.isCompleted)

        release.complete(Unit)
        first.await()
    }

    @Test
    fun mutationWaitsForActiveReadLeasesAndBlocksNewOnes() = runTest {
        val queue = MutationQueue()
        val readerIn = CompletableDeferred<Unit>()
        val releaseReader = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val reader = async {
            queue.withReadLease {
                readerIn.complete(Unit)
                releaseReader.await()
            }
        }
        readerIn.await()

        val mutation = async { queue.withMutation { order += "mutation" } }
        runCurrent()
        assertFalse("a mutation must wait for an active read lease", mutation.isCompleted)

        // A writer is waiting: a new reader must queue behind it so a stream
        // of readers cannot starve deletion or reset.
        val lateReader = async { queue.withReadLease { order += "late read" } }
        runCurrent()
        assertFalse("a new reader must not overtake a waiting mutation", lateReader.isCompleted)

        releaseReader.complete(Unit)
        reader.await()
        mutation.await()
        lateReader.await()
        assertEquals(listOf("mutation", "late read"), order)
    }

    /**
     * P1-18-R6 (V-04): the opportunistic callers of [MutationQueue.tryMutation]
     * (foreground stage cleanup, reset) skip at once while readers are active.
     * Taking the writer gate and then waiting would block every new reader
     * behind a mutation that was only ever optional (C0-R12 "skips").
     * Replaces readLeaseDoesNotMakeTryMutationBusy, which pinned the old wait.
     */
    @Test
    fun tryMutationReturnsBusyImmediatelyWhenReadersAreActive() = runTest {
        val queue = MutationQueue()
        val readerIn = CompletableDeferred<Unit>()
        val releaseReader = CompletableDeferred<Unit>()
        val reader = async {
            queue.withReadLease {
                readerIn.complete(Unit)
                releaseReader.await()
            }
        }
        readerIn.await()

        val attempt = async { queue.tryMutation { "should not run" } }
        runCurrent()
        assertTrue("tryMutation must not wait for readers", attempt.isCompleted)
        assertNull("an active read lease makes tryMutation busy", attempt.await())

        // The writer gate was never taken: a new reader still gets in at once.
        val lateReader = async { queue.withReadLease { "late read" } }
        runCurrent()
        assertTrue("a skipped tryMutation must not block new readers", lateReader.isCompleted)

        releaseReader.complete(Unit)
        reader.await()
        assertEquals("ran", queue.tryMutation { "ran" })
    }

    @Test
    fun tryMutationIsBusyOnlyWhileAnotherMutationHoldsTheQueue() = runTest {
        val queue = MutationQueue()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            queue.withMutation {
                holding.complete(Unit)
                release.await()
            }
        }
        holding.await()

        assertNull(queue.tryMutation { "should not run" })

        release.complete(Unit)
        first.await()
        assertEquals("ran", queue.tryMutation { "ran" })
    }
}
