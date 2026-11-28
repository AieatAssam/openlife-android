package org.openlife.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.openlife.app.ui.facts.FactsGateway
import org.openlife.vault.facts.ExtractionCheck
import org.openlife.vault.facts.FactEvidence
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.FactWithLatestRevision
import org.openlife.vault.facts.FactWriteResult
import org.openlife.vault.facts.ReviewDecision
import org.openlife.vault.repository.FactRepository
import java.util.UUID

/**
 * P3-05-R1: the facts UI's port over [FactRepository]. Reads and writes wait for content access and go through
 * the vault only when it is open; with the vault unavailable reads show nothing and writes report
 * [FactWriteResult.Failed].
 */
internal class VaultFactsGateway(private val app: OpenLifeApp) : FactsGateway {
    override fun facts(sourceId: UUID): Flow<List<FactWithLatestRevision>> = flow {
        app.appLock.awaitContentAccess()
        when (val access = app.vault()) {
            is VaultAccess.Ready -> emitAll(access.factRepository.listFacts(sourceId))
            is VaultAccess.Unavailable -> emit(emptyList())
        }
    }

    override suspend fun extractionCheck(sourceId: UUID): ExtractionCheck =
        withFacts(ExtractionCheck.NotChecked) { it.extractionCheck(sourceId) }

    override suspend fun currentReadingId(sourceId: UUID): UUID? = withFacts(null) { it.currentReadingId(sourceId) }

    override suspend fun evidenceOf(revisionId: UUID): List<FactEvidence> =
        withFacts(emptyList()) { it.evidenceOf(revisionId) }

    override suspend fun review(revisionId: UUID, decision: ReviewDecision): FactWriteResult =
        withFacts(FactWriteResult.Failed) { it.review(revisionId, decision) }

    override suspend fun clarify(revisionId: UUID, chosen: FactValue): FactWriteResult =
        withFacts(FactWriteResult.Failed) { it.clarify(revisionId, chosen) }

    override suspend fun addUserFact(sourceId: UUID, kind: FactKind, value: FactValue): FactWriteResult =
        withFacts(FactWriteResult.Failed) { it.addUserFact(sourceId, kind, value) }

    private suspend fun <T> withFacts(fallback: T, block: suspend (FactRepository) -> T): T {
        app.appLock.awaitContentAccess()
        return when (val access = app.vault()) {
            is VaultAccess.Ready -> block(access.factRepository)
            is VaultAccess.Unavailable -> fallback
        }
    }
}
