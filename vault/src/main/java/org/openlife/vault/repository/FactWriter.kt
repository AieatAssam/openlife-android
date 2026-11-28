package org.openlife.vault.repository

import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactLimits
import org.openlife.vault.facts.FactOrigin
import org.openlife.vault.facts.FactRefusal
import org.openlife.vault.facts.FactStatus
import org.openlife.vault.facts.FactStatusRules
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.ReviewDecision
import org.openlife.vault.facts.UserMethod
import org.openlife.vault.facts.UserValueShape
import org.openlife.vault.facts.extract.UserValueNormaliser
import org.openlife.vault.facts.extract.UserValueResult
import org.openlife.vault.storage.FactDao
import org.openlife.vault.storage.FactRevisionEntity
import org.openlife.vault.storage.decodeStored
import java.util.UUID

/** The outcome of one [FactWriter] call, in stored ids. */
internal sealed interface FactWrite {
    data class Written(val factId: String, val revisionId: String) : FactWrite

    data class Refused(val reason: FactRefusal) : FactWrite
}

/**
 * The transactional bodies of FactRepository's writes (C2 §5, C2-R36-R38,
 * C2-R62). The caller holds the mutation and runs each call in one
 * transaction. A refusal is decided before anything is written; a lost
 * compare-and-set after a write throws, so the transaction rolls back
 * (C2-R23). The C2 triggers check the same rules a second time.
 */
@Suppress("TooManyFunctions") // one method per write and read rule (C2-R23..R37); a split would scatter the gate
internal class FactWriter(
    private val dao: FactDao,
    private val clock: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** C2-R38: a new fact with one CONFIRMED USER revision and no evidence. */
    suspend fun addUserFact(sourceId: String, kind: FactKind, value: FactValue): FactWrite {
        if (!isUserValue(kind, value)) return refused(FactRefusal.INVALID_VALUE)
        if (dao.sourceState(sourceId) != READY) return refused(FactRefusal.SOURCE_NOT_READY)
        if (dao.countCurrentRevisions(sourceId) >= FactLimits.MAX_CURRENT_REVISIONS) {
            return refused(FactRefusal.LIMIT_REACHED)
        }
        val factId = newId()
        dao.insertFact(factId, sourceId, kind.name, clock())
        return writeUserRevision(factId, kind, value, UserMethod.ADD, current = null)
    }

    /** C2 §5: one review event on the current revision, by compare-and-set of its status. */
    suspend fun review(revisionId: String, decision: ReviewDecision): FactWrite {
        val target = currentTarget(revisionId) ?: return refused(FactRefusal.NOT_ALLOWED)
        if (target.sourceReady.not()) return refused(FactRefusal.SOURCE_NOT_READY)
        val revision = target.revision
        val initial = FactStatusRules.initial(revision.origin, revision.value.isCandidates)
        val next = FactStatusRules.next(revision.status, decision, initial, revision.value.isCandidates)
            ?: return refused(FactRefusal.NOT_ALLOWED)
        if (next == FactStatus.CONFIRMED && !hasValueEvidence(revision)) return refused(FactRefusal.NOT_ALLOWED)
        check(dao.setStatus(revisionId, revision.status.name, next.name) == 1) { "status changed under the mutation" }
        dao.appendReviewEvent(newId(), revisionId, decision.name, clock())
        return FactWrite.Written(target.factId, revisionId)
    }

    /** C2-R36: the candidate set a clarification may choose from, for a current revision of a ready source. */
    suspend fun candidatesOf(revisionId: String): FactValue? {
        val target = currentTarget(revisionId) ?: return null
        if (!target.sourceReady) return null
        return candidatesBasis(target.revision)?.value
    }

    /** C2-R36: choose (or re-choose) one candidate of the extracted revision, byte for byte. */
    suspend fun clarify(revisionId: String, chosen: FactValue): FactWrite {
        val target = currentTarget(revisionId) ?: return refused(FactRefusal.NOT_ALLOWED)
        if (target.sourceReady.not()) return refused(FactRefusal.SOURCE_NOT_READY)
        val basis = candidatesBasis(target.revision) ?: return refused(FactRefusal.NOT_ALLOWED)
        if (!isCandidateOf(chosen, basis.value)) return refused(FactRefusal.INVALID_VALUE)
        return writeUserRevision(target.factId, target.revision.kind, chosen, UserMethod.CLARIFY, revisionId)
    }

    /** C2-R37: a USER value supersedes the current revision; refused on a STALE one. */
    suspend fun correct(revisionId: String, value: FactValue): FactWrite {
        val target = currentTarget(revisionId) ?: return refused(FactRefusal.NOT_ALLOWED)
        if (target.sourceReady.not()) return refused(FactRefusal.SOURCE_NOT_READY)
        if (target.revision.status == FactStatus.STALE) return refused(FactRefusal.NOT_ALLOWED)
        if (!isUserValue(target.revision.kind, value)) return refused(FactRefusal.INVALID_VALUE)
        return writeUserRevision(target.factId, target.revision.kind, value, UserMethod.CORRECT, revisionId)
    }

    /** A stored revision with its enums decoded tolerantly (C2-R51); ids stay opaque strings. */
    private class Revision(entity: FactRevisionEntity) {
        val id = entity.id
        val origin = decodeStored(entity.origin, FactOrigin.UNRECOGNISED)
        val kind = decodeStored(entity.kind, FactKind.UNRECOGNISED)
        val status = decodeStored(entity.status, FactStatus.UNRECOGNISED)
        val methodId = entity.methodId
        val value = FactValue(entity.valueJson, entity.canonicalText)
        val isRecognised = origin != FactOrigin.UNRECOGNISED && kind != FactKind.UNRECOGNISED &&
            status != FactStatus.UNRECOGNISED
    }

    private class Target(val factId: String, val revision: Revision, val sourceReady: Boolean)

    /** The revision when it is its fact's current, recognised revision (C2-R23, C2-R51); otherwise null. */
    private suspend fun currentTarget(revisionId: String): Target? {
        val entity = dao.findRevision(revisionId) ?: return null
        val revision = Revision(entity).takeIf { it.isRecognised } ?: return null
        val fact = dao.findFact(entity.factId)?.takeIf { it.currentRevisionId == revisionId } ?: return null
        return Target(fact.id, revision, dao.sourceState(fact.sourceId) == READY)
    }

    /** The extracted `candidates` revision a clarification chooses from, if [revision] offers one. */
    private suspend fun candidatesBasis(revision: Revision): Revision? = when {
        revision.status == FactStatus.STALE -> null

        revision.origin == FactOrigin.EXTRACTED -> revision.takeIf { it.status == FactStatus.CLARIFICATION_NEEDED }

        revision.methodId == UserMethod.CLARIFY -> dao.basisOf(
            revision.id,
        )?.let { dao.findRevision(it) }?.let(::Revision)

        else -> null
    }?.takeIf { it.value.isCandidates }

    private fun isCandidateOf(chosen: FactValue, candidates: FactValue): Boolean {
        val entries = candidates.candidateEntries()
        val index = entries.indexOf(chosen.valueJson)
        if (index < 0) return false
        // M-08: a candidate's text is found by position, so the texts must
        // line up one to one; a text containing the separator breaks that.
        val texts = candidates.candidateTexts()
        return texts.size == entries.size && texts[index] == chosen.canonicalText
    }

    private fun isUserValue(kind: FactKind, value: FactValue): Boolean = value.isWithinBounds &&
        !value.isCandidates &&
        value.canonicalText.length <= FactLimits.MAX_USER_TEXT_CHARS &&
        UserValueShape.matches(kind, value) &&
        isNormalisedText(kind, value)

    /**
     * P3-05 review U-06: a URL, email, phone or money value is kept only when the single-value normaliser gives back
     * its canonical text and its stored form from that text. A money value must match exactly. A text value's
     * `normalized` part must match, and its `raw` part (the printed input, which the canonical text cannot restore)
     * may be any JSON string. A value that disagrees with its text, or that the normaliser would change, refuse or
     * read as another kind, is not written.
     */
    private fun isNormalisedText(kind: FactKind, value: FactValue): Boolean {
        if (kind !in NORMALISED_KINDS) return true
        val restored = (UserValueNormaliser.restore(kind, value.canonicalText) as? UserValueResult.Accepted)?.value
            ?: return false
        if (restored.canonicalText != value.canonicalText) return false
        if (kind == FactKind.MONEY) return restored.valueJson == value.valueJson
        val head = restored.valueJson.substringBefore(RAW_KEY) + RAW_KEY
        return value.valueJson.startsWith(head) && RAW_TAIL.matches(value.valueJson.removePrefix(head))
    }

    private suspend fun hasValueEvidence(revision: Revision): Boolean =
        revision.origin != FactOrigin.EXTRACTED || dao.countValueEvidence(revision.id) == 1

    /** C2-R3 insert order, then the USER revision's CONFIRM event and the pointer compare-and-set. */
    private suspend fun writeUserRevision(
        factId: String,
        kind: FactKind,
        value: FactValue,
        methodId: String,
        current: String?,
    ): FactWrite {
        val revisionId = newId()
        val now = clock()
        dao.insertRevision(
            id = revisionId,
            factId = factId,
            origin = FactOrigin.USER.name,
            kind = kind.name,
            valueJson = value.valueJson,
            canonicalText = value.canonicalText,
            rawText = null,
            status = FactStatus.CONFIRMED.name,
            methodId = methodId,
            methodVersion = null,
            locale = null,
            createdAt = now,
            supersedesRevisionId = current,
        )
        dao.appendReviewEvent(newId(), revisionId, ReviewDecision.CONFIRM.name, now)
        check(
            dao.moveCurrentRevision(factId, current, revisionId) == 1,
        ) { "current revision changed under the mutation" }
        return FactWrite.Written(factId, revisionId)
    }

    private fun refused(reason: FactRefusal) = FactWrite.Refused(reason)

    private companion object {
        const val READY = "READY"

        /** P3-05 review U-06: the kinds whose stored form the normaliser restores (see isNormalisedText). */
        val NORMALISED_KINDS = setOf(FactKind.URL, FactKind.EMAIL, FactKind.PHONE, FactKind.MONEY)
        const val RAW_KEY = ",\"raw\":\""

        /** The printed `raw` JSON string and the object's closing brace. */
        val RAW_TAIL = Regex("""(?:[^"\\]|\\.)*"\}""")
    }
}
