package org.openlife.app.test

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.openlife.app.ui.facts.FactsGateway
import org.openlife.vault.facts.ExtractionCheck
import org.openlife.vault.facts.Fact
import org.openlife.vault.facts.FactEvidence
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactOrigin
import org.openlife.vault.facts.FactRevision
import org.openlife.vault.facts.FactStatus
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.FactWithLatestRevision
import org.openlife.vault.facts.FactWriteResult
import org.openlife.vault.facts.ReviewDecision
import java.util.UUID

/**
 * P3-05 test double for the facts port. It serves fixed rows and records every write, so a test can assert which
 * call the UI made. It does not prove persistence; the repository tests in the vault module do that.
 */
class FakeFactsGateway(
    private val rows: List<FactWithLatestRevision> = emptyList(),
    private val evidence: List<FactEvidence> = emptyList(),
    private val readingId: UUID? = null,
    private val candidates: Map<UUID, FactValue> = emptyMap(),
) : FactsGateway {
    val reviews = mutableListOf<Pair<UUID, ReviewDecision>>()
    val clarifications = mutableListOf<Pair<UUID, FactValue>>()
    val additions = mutableListOf<Triple<UUID, FactKind, FactValue>>()
    val corrections = mutableListOf<Pair<UUID, FactValue>>()
    val findAgainSources = mutableListOf<UUID>()

    override fun facts(sourceId: UUID): Flow<List<FactWithLatestRevision>> = flowOf(rows)

    override suspend fun extractionCheck(sourceId: UUID): ExtractionCheck = ExtractionCheck.Completed

    override suspend fun currentReadingId(sourceId: UUID): UUID? = readingId

    override suspend fun evidenceOf(revisionId: UUID): List<FactEvidence> = evidence

    override suspend fun candidatesOf(revisionId: UUID): FactValue? = candidates[revisionId]

    override suspend fun review(revisionId: UUID, decision: ReviewDecision): FactWriteResult {
        reviews += revisionId to decision
        return FactWriteResult.Failed
    }

    override suspend fun clarify(revisionId: UUID, chosen: FactValue): FactWriteResult {
        clarifications += revisionId to chosen
        return FactWriteResult.Failed
    }

    override suspend fun addUserFact(sourceId: UUID, kind: FactKind, value: FactValue): FactWriteResult {
        additions += Triple(sourceId, kind, value)
        return FactWriteResult.Failed
    }

    override suspend fun correct(revisionId: UUID, value: FactValue): FactWriteResult {
        corrections += revisionId to value
        return FactWriteResult.Failed
    }

    override suspend fun findAgain(sourceId: UUID): Boolean {
        findAgainSources += sourceId
        return true
    }

    companion object {
        /** One synthetic fact row with one revision; the value text is synthetic. */
        fun row(
            sourceId: UUID,
            kind: FactKind,
            origin: FactOrigin,
            status: FactStatus,
            value: FactValue,
        ): FactWithLatestRevision {
            val factId = UUID.randomUUID()
            val revisionId = UUID.randomUUID()
            return FactWithLatestRevision(
                fact = Fact(factId, sourceId, kind, 0, revisionId, 1L),
                revision = FactRevision(
                    id = revisionId,
                    factId = factId,
                    revisionNo = 1,
                    origin = origin,
                    kind = kind,
                    value = value,
                    rawText = null,
                    status = status,
                    staleReason = null,
                    staleAt = null,
                    methodId = "test",
                    methodVersion = null,
                    locale = null,
                    createdAt = 1L,
                    supersedesRevisionId = null,
                ),
            )
        }
    }
}
