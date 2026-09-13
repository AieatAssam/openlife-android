package org.openlife.vault.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single repository-owned serial mutation queue covering imports,
 * deletion, and recovery (design §11). Import uses [tryAcquire], which
 * never blocks: if another mutation is already in progress, C0 asks the
 * user to finish or cancel it rather than queueing a second one
 * (ImportLimits.MAX_CONCURRENT_IMPORTS = 1). Deletion and recovery use
 * [acquire], which waits its turn — those are not subject to the
 * one-at-a-time *import* limit, but still must never run concurrently with
 * an import or with each other.
 */
class MutationQueue {
    private val mutex = Mutex()

    suspend fun <T> acquire(block: suspend () -> T): T = mutex.withLock { block() }

    /** Returns null immediately if a mutation is already in progress. */
    suspend fun <T> tryAcquire(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
