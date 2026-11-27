package org.openlife.vault.repository

import org.openlife.vault.facts.ExtractionTrigger
import org.openlife.vault.facts.FactExtractionResult
import org.openlife.vault.storage.FactDao

/**
 * The lock boundary of C2-R31. [read] runs the snapshot read under a read lease; [commit] runs the commit under
 * `withMutation` and one transaction. The host tests supply a transactional HostSqlite gate.
 */
internal interface ExtractionGate {
    suspend fun <T> read(block: suspend () -> T): T

    suspend fun <T> commit(block: suspend () -> T): T
}

/** P3-04: the extraction run (R1-R6), host-testable over [FactDao]. Stub until GREEN. */
internal class FactExtractionCore(
    private val dao: FactDao,
    private val gate: ExtractionGate,
    private val clock: () -> Long,
    private val newId: () -> String,
    private val deadlineMillis: Long,
) {
    suspend fun extract(sourceId: String, trigger: ExtractionTrigger): FactExtractionResult = TODO("P3-04 GREEN")

    /** C2-R30: runs only when the Source's current reading is due; null when nothing was due. */
    suspend fun extractIfDue(sourceId: String): FactExtractionResult? = TODO("P3-04 GREEN")
}
