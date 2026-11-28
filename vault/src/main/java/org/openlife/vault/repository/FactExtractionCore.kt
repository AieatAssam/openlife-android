package org.openlife.vault.repository

import kotlinx.coroutines.CancellationException
import org.openlife.vault.facts.ExtractionOutcome
import org.openlife.vault.facts.ExtractionTrigger
import org.openlife.vault.facts.FactExtractionResult
import org.openlife.vault.facts.FactLimits
import org.openlife.vault.facts.FactOrigin
import org.openlife.vault.facts.FactStatusRules
import org.openlife.vault.facts.SpanEligibility
import org.openlife.vault.facts.SpanEligibilityInput
import org.openlife.vault.facts.extract.CanonicalJson
import org.openlife.vault.facts.extract.ExtractedCandidate
import org.openlife.vault.facts.extract.ExtractedKind
import org.openlife.vault.facts.extract.ExtractionDeadline
import org.openlife.vault.facts.extract.ExtractionSpan
import org.openlife.vault.facts.extract.FactExtractor
import org.openlife.vault.storage.FactDao
import org.openlife.vault.storage.FactPointer
import org.openlife.vault.storage.HistoryRow
import org.openlife.vault.storage.ReadingRow
import org.openlife.vault.storage.SpanRow

/**
 * The lock boundary of C2-R31. [read] runs the snapshot read under a read lease; [commit] runs the commit under
 * `withMutation` and one transaction. The host tests supply a transactional HostSqlite gate.
 */
internal interface ExtractionGate {
    suspend fun <T> read(block: suspend () -> T): T

    suspend fun <T> commit(block: suspend () -> T): T
}

/**
 * P3-04: the extraction run (R1-R6), host-testable over [FactDao].
 *
 * Protocol (C2-R31): snapshot under a read lease; match and extract outside any lock; commit under the gate with a
 * fingerprint re-check. A fingerprint mismatch gives [FactExtractionResult.Superseded] and writes nothing. Terminal
 * outcomes other than SUPERSEDED write one run row and no facts (C2 §7.6).
 */
internal class FactExtractionCore(
    private val dao: FactDao,
    private val gate: ExtractionGate,
    private val clock: () -> Long,
    private val newId: () -> String,
    private val deadlineMillis: Long,
) {
    /** Thrown by the cooperative deadline (C2-R29). Carries no document content. */
    private class DeadlineExceeded : RuntimeException()

    private data class Fingerprint(
        val sourceState: String?,
        val readingId: String?,
        val readingState: String?,
        val readingReview: String?,
        val wholeCorrected: Boolean?,
        val epoch: Long?,
        val pointers: Set<FactPointer>,
    )

    private sealed interface Snapshot {
        data class Terminal(val outcome: ExtractionOutcome, val reading: ReadingRow?) : Snapshot

        class Ready(
            val reading: ReadingRow,
            val spans: List<ExtractionSpan>,
            val boxes: Map<String, SpanRow>,
            val history: List<HistoryRow>,
            val currentCount: Int,
            val fingerprint: Fingerprint,
        ) : Snapshot
    }

    private sealed interface Plan {
        data class Terminal(val outcome: ExtractionOutcome) : Plan

        class Write(
            val creates: List<ExtractedCandidate>,
            val continues: List<Pair<ExtractedCandidate, HistoryRow>>,
            val candidateCount: Int,
            val skipped: Set<ExtractedKind>,
        ) : Plan
    }

    private data class RunSummary(
        val outcome: ExtractionOutcome,
        val candidateCount: Int?,
        val createdRevisionCount: Int?,
        val skipped: Set<ExtractedKind>,
    )

    suspend fun extract(sourceId: String, trigger: ExtractionTrigger): FactExtractionResult {
        val started = clock()
        return when (val snapshot = gate.read { snapshot(sourceId) }) {
            is Snapshot.Terminal -> terminal(sourceId, snapshot.reading, trigger, started, snapshot.outcome)

            is Snapshot.Ready -> when (val plan = plan(snapshot, started)) {
                is Plan.Terminal -> terminal(sourceId, snapshot.reading, trigger, started, plan.outcome)
                is Plan.Write -> commit(sourceId, snapshot, plan, trigger, started)
            }
        }
    }

    /** C2-R30: runs only when the Source's current reading has no run row and no extracted fact exists yet. */
    suspend fun extractIfDue(sourceId: String): FactExtractionResult? {
        val due = gate.read {
            val reading = dao.readingOfSource(sourceId) ?: return@read false
            dao.sourceState(sourceId) == READY &&
                dao.runCountForReading(reading.id) == 0 &&
                dao.extractedCountOfSource(sourceId) == 0
        }
        return if (due) extract(sourceId, ExtractionTrigger.AUTO) else null
    }

    private suspend fun snapshot(sourceId: String): Snapshot {
        val state = dao.sourceState(sourceId) ?: return Snapshot.Terminal(ExtractionOutcome.SOURCE_NOT_READY, null)
        val reading = dao.readingOfSource(sourceId)
            ?: return Snapshot.Terminal(ExtractionOutcome.NO_CURRENT_READING, null)
        if (state != READY) return Snapshot.Terminal(ExtractionOutcome.SOURCE_NOT_READY, reading)
        if (reading.reviewState == REJECTED) return Snapshot.Terminal(ExtractionOutcome.READING_REJECTED, reading)
        if (reading.wholeCorrected) {
            return Snapshot.Terminal(ExtractionOutcome.READING_CORRECTED_AS_A_WHOLE, reading)
        }
        val spans = mutableListOf<ExtractionSpan>()
        val boxes = mutableMapOf<String, SpanRow>()
        for (row in dao.spanRowsOfReading(reading.id)) {
            val input = SpanEligibilityInput(
                readingState = reading.state,
                readingReviewState = reading.reviewState,
                readingHasWholeCorrection = reading.wholeCorrected,
                hasEvidenceRegion = row.hasEvidenceRegion,
                latestSpanReview = row.latestReview,
                storedScriptStatus = row.scriptStatus,
                currentCorrectionText = row.correctionText,
            )
            if (SpanEligibility.isEligible(input)) {
                spans += ExtractionSpan(row.spanId, row.correctionId, row.ordinal, row.correctionText ?: row.text)
                boxes[row.spanId] = row
            }
        }
        return Snapshot.Ready(
            reading = reading,
            spans = spans,
            boxes = boxes,
            history = dao.revisionHistoryOfSource(sourceId),
            currentCount = dao.countCurrentRevisions(sourceId),
            fingerprint = fingerprint(sourceId),
        )
    }

    private suspend fun fingerprint(sourceId: String): Fingerprint {
        val reading = dao.readingOfSource(sourceId)
        return Fingerprint(
            sourceState = dao.sourceState(sourceId),
            readingId = reading?.id,
            readingState = reading?.state,
            readingReview = reading?.reviewState,
            wholeCorrected = reading?.wholeCorrected,
            epoch = dao.sourceEpoch(sourceId),
            pointers = dao.factPointers(sourceId).toSet(),
        )
    }

    /**
     * Matching runs outside the lock. The deadline is checked before and during extraction (C2-R29); the
     * exception is this run's own control flow, and the outcome it becomes is TIMEOUT.
     */
    @Suppress("SwallowedException")
    private fun plan(snapshot: Snapshot.Ready, started: Long): Plan {
        val deadline = ExtractionDeadline {
            if (clock() - started >= deadlineMillis) throw DeadlineExceeded()
        }
        val result = try {
            deadline.check()
            FactExtractor.extract(snapshot.spans, deadline)
        } catch (exceeded: DeadlineExceeded) {
            return Plan.Terminal(ExtractionOutcome.TIMEOUT)
        }
        // C2-R57: a kind over 64 candidates is skipped; the other kinds still commit.
        val skipped = result.candidates.groupBy { it.kind }
            .filterValues { it.size > MAX_CANDIDATES_PER_KIND }
            .keys
        val kept = result.candidates.filter { it.kind !in skipped }

        val existing = snapshot.history
            .filter { it.isCurrent && it.origin == FactOrigin.EXTRACTED.name && it.status != STALE }
            .map { Triple(it.kind, it.canonicalText, it.ocrSpanId) }
            .toSet()
        val stale = snapshot.history
            .filter { it.isCurrent && it.origin == FactOrigin.EXTRACTED.name && it.status == STALE }
            .toMutableList()

        val creates = mutableListOf<ExtractedCandidate>()
        val continues = mutableListOf<Pair<ExtractedCandidate, HistoryRow>>()
        for (candidate in kept) {
            val kind = mapKind(candidate.kind).name
            if (Triple(kind, canonicalTextOf(candidate), candidate.spanId) in existing) continue
            val own = snapshot.boxes[candidate.spanId]?.let { boxOf(it.left, it.top, it.right, it.bottom) }
            val pair = own?.let { box ->
                stale.filter { it.kind == kind }
                    .map { it to overlapRatio(box, it.evidenceBox()) }
                    .filter { it.second > 0.0 }
                    .maxByOrNull { it.second }
                    ?.first
            }
            if (pair != null) {
                stale.remove(pair)
                continues += candidate to pair
            } else {
                creates += candidate
            }
        }
        if (snapshot.currentCount + creates.size > FactLimits.MAX_CURRENT_REVISIONS) {
            return Plan.Terminal(ExtractionOutcome.LIMIT_EXCEEDED)
        }
        return Plan.Write(creates, continues, result.candidates.size, skipped)
    }

    /** Every write of the run happens here, or none does. A thrown storage error rolls the whole run back. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun commit(
        sourceId: String,
        snapshot: Snapshot.Ready,
        plan: Plan.Write,
        trigger: ExtractionTrigger,
        started: Long,
    ): FactExtractionResult {
        val now = clock()
        return try {
            gate.commit {
                if (fingerprint(sourceId) != snapshot.fingerprint) {
                    FactExtractionResult.Superseded
                } else {
                    for (candidate in plan.creates) writeNew(sourceId, candidate, now)
                    for ((candidate, previous) in plan.continues) writeContinuation(sourceId, candidate, previous, now)
                    val created = plan.creates.size + plan.continues.size
                    writeRun(
                        sourceId,
                        snapshot.reading.id,
                        trigger,
                        started,
                        RunSummary(ExtractionOutcome.COMPLETED, plan.candidateCount, created, plan.skipped),
                    )
                    FactExtractionResult.Finished(
                        ExtractionOutcome.COMPLETED,
                        plan.candidateCount,
                        created,
                        plan.skipped,
                    )
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // The transaction rolled back, so no fact row survives. Only the run row is recorded.
            val summary = RunSummary(ExtractionOutcome.STORAGE_FAILURE, null, null, emptySet())
            gate.commit { writeRun(sourceId, snapshot.reading.id, trigger, started, summary) }
            FactExtractionResult.Finished(ExtractionOutcome.STORAGE_FAILURE, 0, 0, emptySet())
        }
    }

    private suspend fun terminal(
        sourceId: String,
        reading: ReadingRow?,
        trigger: ExtractionTrigger,
        started: Long,
        outcome: ExtractionOutcome,
    ): FactExtractionResult.Finished {
        // With no reading there is no ocr_revision_id for a run row (C2 §7.6), so nothing is recorded.
        if (reading != null) {
            val summary = RunSummary(outcome, null, null, emptySet())
            gate.commit { writeRun(sourceId, reading.id, trigger, started, summary) }
        }
        return FactExtractionResult.Finished(outcome, 0, 0, emptySet())
    }

    private suspend fun writeNew(sourceId: String, candidate: ExtractedCandidate, now: Long) {
        val factId = newId()
        dao.insertFact(factId, sourceId, mapKind(candidate.kind).name, now)
        val revisionId = writeRevision(sourceId, factId, candidate, now, supersedes = null)
        dao.moveCurrentRevision(factId, null, revisionId)
    }

    private suspend fun writeContinuation(
        sourceId: String,
        candidate: ExtractedCandidate,
        previous: HistoryRow,
        now: Long,
    ) {
        val revisionId = writeRevision(sourceId, previous.factId, candidate, now, supersedes = previous.revisionId)
        check(dao.moveCurrentRevision(previous.factId, previous.revisionId, revisionId) == 1) { POINTER_MOVED }
    }

    private suspend fun writeRevision(
        sourceId: String,
        factId: String,
        candidate: ExtractedCandidate,
        now: Long,
        supersedes: String?,
    ): String {
        val revisionId = newId()
        val fromCorrection = candidate.correctionId != null ||
            candidate.currencyContext.any { it.correctionId != null && it.codeStart != null }
        val methodId = candidate.ruleId + if (fromCorrection) CORRECTION_SUFFIX else ""
        dao.insertRevision(
            id = revisionId,
            factId = factId,
            origin = FactOrigin.EXTRACTED.name,
            kind = mapKind(candidate.kind).name,
            valueJson = CanonicalJson.of(candidate.canonicalValue, candidate.ambiguity),
            canonicalText = canonicalTextOf(candidate),
            rawText = candidate.rawText,
            status = FactStatusRules.initial(FactOrigin.EXTRACTED, candidate.needsClarification).name,
            methodId = methodId,
            methodVersion = candidate.ruleVersion,
            locale = candidate.locale,
            createdAt = now,
            supersedesRevisionId = supersedes,
        )
        dao.insertEvidence(
            id = newId(),
            revisionId = revisionId,
            role = VALUE_ROLE,
            sourceId = sourceId,
            ocrSpanId = candidate.spanId,
            ocrUserRevisionId = candidate.correctionId,
            textStart = candidate.startOffset,
            textEnd = candidate.endOffset,
        )
        // Only context codes with stored offsets become evidence (C2 §4.7).
        for (context in candidate.currencyContext.filter { it.codeStart != null && it.codeEnd != null }) {
            dao.insertEvidence(
                id = newId(),
                revisionId = revisionId,
                role = CURRENCY_ROLE,
                sourceId = sourceId,
                ocrSpanId = context.spanId,
                ocrUserRevisionId = context.correctionId,
                textStart = checkNotNull(context.codeStart),
                textEnd = checkNotNull(context.codeEnd),
            )
        }
        return revisionId
    }

    private suspend fun writeRun(
        sourceId: String,
        ocrRevisionId: String,
        trigger: ExtractionTrigger,
        started: Long,
        summary: RunSummary,
    ) {
        dao.insertRun(
            id = newId(),
            sourceId = sourceId,
            ocrRevisionId = ocrRevisionId,
            trigger = trigger.name,
            rulesVersion = FactExtractor.RULES_VERSION,
            startedAt = started,
            finishedAt = clock(),
            outcome = summary.outcome.name,
            skippedKinds = summary.skipped.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name },
            candidateCount = summary.candidateCount,
            createdRevisionCount = summary.createdRevisionCount,
            skippedLineCount = null,
        )
    }

    private companion object {
        const val READY = "READY"
        const val REJECTED = "REJECTED"
        const val STALE = "STALE"
        const val VALUE_ROLE = "VALUE"
        const val CURRENCY_ROLE = "CURRENCY_CONTEXT"
        const val CORRECTION_SUFFIX = "+correction"
        const val MAX_CANDIDATES_PER_KIND = 64
        const val POINTER_MOVED = "current revision moved during the commit"
    }
}
