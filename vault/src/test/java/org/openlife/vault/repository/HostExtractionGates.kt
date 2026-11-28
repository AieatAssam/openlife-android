package org.openlife.vault.repository

import org.openlife.vault.storage.HostSqlite

/** The host stand-in for MutationQueue and the database transaction: BEGIN, COMMIT or ROLLBACK around [block]. */
internal open class TransactionalHostGate(protected val host: HostSqlite) : ExtractionGate {
    override suspend fun <T> read(block: suspend () -> T): T = block()

    override suspend fun <T> commit(block: suspend () -> T): T = transaction(block)

    protected suspend fun <T> transaction(block: suspend () -> T): T {
        host.exec("BEGIN")
        try {
            val result = block()
            host.exec("COMMIT")
            return result
        } catch (failure: Throwable) {
            host.exec("ROLLBACK")
            throw failure
        }
    }
}

/** Runs [beforeCommit] inside the commit, before the transaction, to simulate a writer that races the run. */
internal class HookedGate(host: HostSqlite, private val beforeCommit: suspend () -> Unit) :
    TransactionalHostGate(host) {
    override suspend fun <T> commit(block: suspend () -> T): T {
        beforeCommit()
        return transaction(block)
    }
}

/** Fails commit number [failing] (1-based) with a synthetic error before any write, and runs the rest normally. */
internal class FaultyGate(host: HostSqlite, private val failing: (Int) -> Boolean) : TransactionalHostGate(host) {
    private var calls = 0

    override suspend fun <T> commit(block: suspend () -> T): T {
        calls++
        if (failing(calls)) throw IllegalStateException("synthetic commit failure")
        return transaction(block)
    }
}
