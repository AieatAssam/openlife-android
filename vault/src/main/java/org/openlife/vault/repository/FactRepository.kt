package org.openlife.vault.repository

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.openlife.vault.facts.ExtractionCheck
import org.openlife.vault.facts.ExtractionTrigger
import org.openlife.vault.facts.FactEvidence
import org.openlife.vault.facts.FactExtractionResult
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.FactWithLatestRevision
import org.openlife.vault.facts.FactWriteResult
import org.openlife.vault.facts.ReviewDecision
import org.openlife.vault.facts.StaleReason
import org.openlife.vault.facts.toExtractionCheck
import org.openlife.vault.facts.toFactEvidence
import org.openlife.vault.facts.toReviewDecision
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.toDomain
import java.util.UUID

/**
 * P3-02-R5: the fact list and the user-side fact writes (C2 §5, §8, §9).
 * Every write runs under the mutation queue, on [ioDispatcher], in one
 * transaction (C2-R52); a refusal writes nothing. A storage failure rolls the
 * transaction back and returns [FactWriteResult.Failed] (review M-06); the
 * two stale entry points still throw on a storage failure, after their
 * transaction has rolled back; P3-04, their caller, decides what that means. Nothing here logs (C2-R50).
 *
 * The stale hooks of §9 are wired into the OCR events by P3-04 (C2-R40);
 * until then [markStaleForSpan] and [markStaleForRevision] are the entry
 * points. A caller already inside a mutation uses [FactStaleMarker].
 */
@Suppress("TooManyFunctions") // one entry point per fact operation (C2-R29..R40); a split would scatter the gate
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

    /** The evidence rows of [revisionId] in stored order (C2-R34); empty for a USER revision. */
    suspend fun evidenceOf(revisionId: UUID): List<FactEvidence> = withContext(ioDispatcher) {
        database.factDao().evidenceOfRevision(revisionId.toString()).map { it.toFactEvidence() }
    }

    /** The latest review decision of [revisionId], or null if it was never reviewed. */
    suspend fun latestDecision(revisionId: UUID): ReviewDecision? = withContext(ioDispatcher) {
        database.factDao().latestDecision(revisionId.toString()).toReviewDecision()
    }

    /** P3-05-R1: the reading [sourceId] currently shows, or null when it has none. */
    suspend fun currentReadingId(sourceId: UUID): UUID? = withContext(ioDispatcher) {
        database.factDao().currentReadingId(sourceId.toString())?.let(UUID::fromString)
    }

    /** P3-05-R4 "Find facts again": one USER-triggered extraction of the current reading, under the C2-R30 protocol. */
    suspend fun findAgain(sourceId: UUID): FactExtractionResult =
        FactExtractionRepository(database, mutationQueue).extract(sourceId, ExtractionTrigger.USER)

    /** What the facts panel can say about the extraction of [sourceId]'s current reading (C2-R39). */

    suspend fun extractionCheck(sourceId: UUID): ExtractionCheck = withContext(ioDispatcher) {
        database.factDao().extractionOutcomeOfCurrentReading(sourceId.toString()).toExtractionCheck()
    }

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
    suspend fun markStaleForSpan(spanId: UUID, reason: StaleReason): Int =
        locked { marker().markForSpan(spanId.toString(), reason) }

    /** C2 §9 for a whole reading; returns how many revisions became STALE. */
    suspend fun markStaleForRevision(ocrRevisionId: UUID, reason: StaleReason): Int =
        locked { marker().markForReading(ocrRevisionId.toString(), reason) }

    private fun marker() = FactStaleMarker(database.factDao(), clock)

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(ioDispatcher) {
        mutationQueue.withMutation { database.withTransaction { block() } }
    }

    private suspend fun write(block: suspend (FactWriter) -> FactWrite): FactWriteResult = captureWriteFailure {
        locked { written(block(FactWriter(database.factDao(), clock))) }
    }

    private fun written(result: FactWrite): FactWriteResult = when (result) {
        is FactWrite.Written -> FactWriteResult.Written(
            UUID.fromString(result.factId),
            UUID.fromString(result.revisionId),
        )

        is FactWrite.Refused -> FactWriteResult.Refused(result.reason)
    }
}

/** M-06: a write's storage failure as [FactWriteResult.Failed]; cancellation is rethrown. */
@Suppress("TooGenericExceptionCaught") // any storage failure rolls back and is reported without its message
internal suspend fun captureWriteFailure(block: suspend () -> FactWriteResult): FactWriteResult = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    FactWriteResult.Failed
}
