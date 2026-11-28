package org.openlife.vault.repository

import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openlife.vault.facts.ExtractionTrigger
import org.openlife.vault.facts.FactExtractionResult
import org.openlife.vault.storage.OpenLifeDatabase
import java.util.UUID

/**
 * P3-04-R1..R6: facts are extracted from the current OCR reading, under the C2-R31 epoch protocol. The read runs under
 * a read lease; the commit runs under `withMutation` and one database transaction.
 */
class FactExtractionRepository(
    private val database: OpenLifeDatabase,
    private val mutationQueue: MutationQueue,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val deadlineMillis: Long = org.openlife.vault.facts.FactLimits.DEADLINE_MILLIS,
) {
    private val gate = object : ExtractionGate {
        override suspend fun <T> read(block: suspend () -> T): T = mutationQueue.withReadLease { block() }

        override suspend fun <T> commit(block: suspend () -> T): T = mutationQueue.withMutation {
            database.withTransaction { block() }
        }
    }

    private fun core() = FactExtractionCore(
        dao = database.factDao(),
        gate = gate,
        clock = clock,
        newId = newId,
        deadlineMillis = deadlineMillis,
    )

    suspend fun extract(sourceId: UUID, trigger: ExtractionTrigger = ExtractionTrigger.USER): FactExtractionResult =
        withContext(ioDispatcher) { core().extract(sourceId.toString(), trigger) }

    /** C2-R30: runs only for a Source whose current reading has no run row and no extracted fact. */
    suspend fun extractIfDue(sourceId: UUID): FactExtractionResult? =
        withContext(ioDispatcher) { core().extractIfDue(sourceId.toString()) }
}
