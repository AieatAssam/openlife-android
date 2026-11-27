package org.openlife.vault.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.openlife.vault.facts.ExtractionTrigger
import org.openlife.vault.facts.FactExtractionResult
import org.openlife.vault.storage.OpenLifeDatabase
import java.util.UUID

/**
 * P3-04-R1: facts are extracted from the current OCR reading, under the C2-R31 epoch protocol. Stub until GREEN.
 */
class FactExtractionRepository(
    private val database: OpenLifeDatabase,
    private val mutationQueue: MutationQueue,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val deadlineMillis: Long = org.openlife.vault.facts.FactLimits.DEADLINE_MILLIS,
) {
    suspend fun extract(sourceId: UUID, trigger: ExtractionTrigger = ExtractionTrigger.USER): FactExtractionResult =
        TODO("P3-04 GREEN")

    suspend fun extractIfDue(sourceId: UUID): FactExtractionResult? = TODO("P3-04 GREEN")
}
