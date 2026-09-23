package org.openlife.vault.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The repository-owned serial mutation queue (design §11), with read leases.
 *
 * - [withReadLease]: many holders at once. Viewers use it, and so does
 *   import preparation, which only creates rows and files for a fresh UUID.
 *   A plaintext read therefore never makes an import "busy" (P1-15, F-29).
 * - [withMutation] / [tryMutation]: exclusive. Save, cancel, deletion,
 *   recovery, OCR bookkeeping and reset. A mutation waits for active read
 *   leases to finish, so deletion can never race plaintext delivery.
 *
 * A mutation holds [gate] for its whole run; a reader only passes through
 * [gate] to register. A waiting mutation therefore blocks new readers, and
 * a steady stream of reads cannot starve deletion or reset. [gate] is fair
 * (FIFO), so waiting mutations run in arrival order.
 *
 * The one-import-at-a-time rule is not this lock's job: see [ImportSlot].
 *
 * Never start a mutation while the same flow holds a read lease: the
 * mutation waits for that lease and the flow never releases it. Work done
 * under a read lease writes only through DAOs directly (for example the
 * idempotent CORRUPT mark in SourceViewRepository), never through here.
 */
class MutationQueue {
    private val gate = Mutex()
    private val readerLock = Any()
    private var activeReaders = 0
    private val readersIdle = MutableStateFlow(true)

    suspend fun <T> withReadLease(block: suspend () -> T): T {
        gate.withLock {
            synchronized(readerLock) {
                activeReaders++
                readersIdle.value = false
            }
        }
        try {
            return block()
        } finally {
            synchronized(readerLock) {
                activeReaders--
                if (activeReaders == 0) readersIdle.value = true
            }
        }
    }

    suspend fun <T> withMutation(block: suspend () -> T): T = gate.withLock {
        readersIdle.first { it }
        block()
    }

    /**
     * Runs [block] exclusively, or returns null at once if another mutation
     * holds the queue. Active read leases are waited for, not reported busy.
     */
    suspend fun <T> tryMutation(block: suspend () -> T): T? {
        if (!gate.tryLock()) return null
        return try {
            readersIdle.first { it }
            block()
        } finally {
            gate.unlock()
        }
    }
}
