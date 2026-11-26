package org.openlife.vault.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.FactWithLatestRevision
import org.openlife.vault.facts.FactWriteResult
import org.openlife.vault.facts.ReviewDecision
import org.openlife.vault.facts.StaleReason
import org.openlife.vault.storage.OpenLifeDatabase
import java.util.UUID

/** P3-02-R5: user-side fact writes and the fact list (C2 §8, §9). */
class FactRepository(
    private val database: OpenLifeDatabase,
    private val mutationQueue: MutationQueue,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun listFacts(sourceId: UUID): Flow<List<FactWithLatestRevision>> = TODO("P3-02 GREEN")

    suspend fun addUserFact(sourceId: UUID, kind: FactKind, value: FactValue): FactWriteResult = TODO("P3-02 GREEN")

    suspend fun review(revisionId: UUID, decision: ReviewDecision): FactWriteResult = TODO("P3-02 GREEN")

    suspend fun clarify(revisionId: UUID, chosen: FactValue): FactWriteResult = TODO("P3-02 GREEN")

    suspend fun correct(revisionId: UUID, value: FactValue): FactWriteResult = TODO("P3-02 GREEN")

    suspend fun markStaleForSpan(spanId: UUID, reason: StaleReason): Int = TODO("P3-02 GREEN")

    suspend fun markStaleForRevision(ocrRevisionId: UUID, reason: StaleReason): Int = TODO("P3-02 GREEN")
}
