package org.openlife.vault.repository

import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.FactWithLatestRevision
import org.openlife.vault.facts.FactWriteResult
import org.openlife.vault.facts.ReviewDecision
import org.openlife.vault.facts.StaleReason
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.toDomain
import java.util.UUID

/**
 * P3-02-R5: the fact list and the user-side fact writes (C2 §5, §8, §9).
 * Every write runs under the mutation queue, on [ioDispatcher], in one
 * transaction (C2-R52); a refusal writes nothing. Nothing here logs (C2-R50).
 *
 * The stale hooks of §9 are wired into the OCR events by P3-04 (C2-R40);
 * until then [markStaleForSpan] and [markStaleForRevision] are the entry
 * points, and the `…Locked` forms run inside a caller's transaction.
 */
class FactRepository(
    private val database: OpenLifeDatabase,
    private val mutationQueue: MutationQueue,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Each fact of [sourceId] with the revision `facts.currentRevisionId` names, in (kind, ordinal) order. */
    fun listFacts(sourceId: UUID): Flow<List<FactWithLatestRevision>> =
        database.factDao().observeCurrentFacts(sourceId.toString())
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    suspend fun addUserFact(sourceId: UUID, kind: FactKind, value: FactValue): FactWriteResult =
        write { it.addUserFact(sourceId.toString(), kind, value) }

    suspend fun review(revisionId: UUID, decision: ReviewDecision): FactWriteResult =
        write { it.review(revisionId.toString(), decision) }

    /** C2-R36: choosing or re-choosing a candidate. */
    suspend fun clarify(revisionId: UUID, chosen: FactValue): FactWriteResult =
        write { it.clarify(revisionId.toString(), chosen) }

    /** C2-R37. */
    suspend fun correct(revisionId: UUID, value: FactValue): FactWriteResult =
        write { it.correct(revisionId.toString(), value) }

    /** C2 §9 for one span; returns how many revisions became STALE. */
    suspend fun markStaleForSpan(spanId: UUID, reason: StaleReason): Int = locked { markStaleForSpanLocked(spanId, reason) }

    /** C2 §9 for a whole reading; returns how many revisions became STALE. */
    suspend fun markStaleForRevision(ocrRevisionId: UUID, reason: StaleReason): Int =
        locked { markStaleForRevisionLocked(ocrRevisionId, reason) }

    /** For a caller that already holds the mutation and runs a transaction (C2-R40). */
    internal suspend fun markStaleForSpanLocked(spanId: UUID, reason: StaleReason): Int =
        marker().markForSpan(spanId.toString(), reason)

    /** For a caller that already holds the mutation and runs a transaction (C2-R40). */
    internal suspend fun markStaleForRevisionLocked(ocrRevisionId: UUID, reason: StaleReason): Int =
        marker().markForReading(ocrRevisionId.toString(), reason)

    private fun marker() = FactStaleMarker(database.factDao(), clock)

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(ioDispatcher) {
        mutationQueue.withMutation { database.withTransaction { block() } }
    }

    private suspend fun write(block: suspend (FactWriter) -> FactWrite): FactWriteResult = locked {
        when (val result = block(FactWriter(database.factDao(), clock))) {
            is FactWrite.Written -> FactWriteResult.Written(UUID.fromString(result.factId), UUID.fromString(result.revisionId))
            is FactWrite.Refused -> FactWriteResult.Refused(result.reason)
        }
    }
}
