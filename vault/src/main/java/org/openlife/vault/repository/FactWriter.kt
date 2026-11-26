package org.openlife.vault.repository

import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactRefusal
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.ReviewDecision
import org.openlife.vault.storage.FactDao
import java.util.UUID

/** The outcome of one [FactWriter] call, in stored ids. */
internal sealed interface FactWrite {
    data class Written(val factId: String, val revisionId: String) : FactWrite

    data class Refused(val reason: FactRefusal) : FactWrite
}

/**
 * The transactional bodies of FactRepository's writes (C2 §5, C2-R36-R38,
 * C2-R62). The caller holds the mutation and runs each call in one
 * transaction; a refusal writes nothing.
 */
internal class FactWriter(
    private val dao: FactDao,
    private val clock: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun addUserFact(sourceId: String, kind: FactKind, value: FactValue): FactWrite = TODO("P3-02 GREEN")

    suspend fun review(revisionId: String, decision: ReviewDecision): FactWrite = TODO("P3-02 GREEN")

    suspend fun clarify(revisionId: String, chosen: FactValue): FactWrite = TODO("P3-02 GREEN")

    suspend fun correct(revisionId: String, value: FactValue): FactWrite = TODO("P3-02 GREEN")
}
