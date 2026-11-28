package org.openlife.vault.repository

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.facts.StaleReason
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrEngineInput
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRevision
import org.openlife.vault.ocr.OcrRevisionState
import org.openlife.vault.ocr.OcrRunResult
import org.openlife.vault.ocr.OcrRunStateMachine
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.ocr.OcrSpanDraft
import org.openlife.vault.ocr.OcrSpanReviewAction
import org.openlife.vault.ocr.OcrSpanReviewRules
import org.openlife.vault.ocr.OcrSpanView
import org.openlife.vault.ocr.OcrUserRevision
import org.openlife.vault.ocr.OcrView
import org.openlife.vault.ocr.withinSource
import org.openlife.vault.storage.OcrDao
import org.openlife.vault.storage.OcrRevisionEntity
import org.openlife.vault.storage.OcrSpanEntity
import org.openlife.vault.storage.OcrSpanReviewEntity
import org.openlife.vault.storage.OcrUserRevisionEntity
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.toDomain
import org.openlife.vault.storage.toEntity
import java.util.UUID

/**
 * Owns C1's state machine. Authenticated source bytes are captured under the
 * mutation queue, OCR runs outside the queue so deletion can proceed, and the
 * final commit is serialized and guarded by a RUNNING/READY recheck.
 */
@Suppress("TooManyFunctions") // the OCR lifecycle: run, completion, review and the fact hooks (C2-R40)
class OcrRepository(
    private val paths: VaultPaths,
    private val database: OpenLifeDatabase,
    keystoreWrapper: KeystoreWrapper,
    private val engineRegistry: org.openlife.vault.ocr.OcrEngineRegistry,
    private val mutationQueue: MutationQueue,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val ocrDao: OcrDao = database.ocrDao(),
    private val deadlineMillis: Long = org.openlife.vault.ocr.OcrLimits.DEADLINE_MILLIS,
    /**
     * P3-04-R4: called with the Source after its reading is READY, outside the mutation queue, so the extraction
     * run can take its own locks. Null (the default) runs nothing.
     */
    private val onReady: (suspend (UUID) -> Unit)? = null,
) {
    private val authenticator = ArtefactAuthenticator(keystoreWrapper)

    suspend fun runOcr(sourceId: UUID): OcrRunResult = withContext(ioDispatcher) {
        val prepared = capture(sourceId)
        if (prepared is Capture.Failure) return@withContext OcrRunResult.Failed(null, prepared.reason)
        prepared as Capture.Ready
        try {
            when (val decision = OcrRunStateMachine.decide(extract(prepared))) {
                is OcrRunStateMachine.Decision.Persist ->
                    persist(sourceId, prepared.revision, decision.spans).also { result ->
                        if (result is OcrRunResult.Completed) notifyReady(sourceId)
                    }

                is OcrRunStateMachine.Decision.Terminal -> {
                    markTerminal(mutationQueue, ocrDao, prepared.revision.id, decision.state, decision.reason)
                    OcrRunStateMachine.resultFor(prepared.revision.id, decision)
                }
            }
        } finally {
            // P2-02-R5: the captured plaintext is not kept past the run.
            prepared.input.close()
        }
    }

    /**
     * P3-04-R4 and P-05: the OCR result is already committed, so a failing hook does not turn it into a failure. A
     * cancellation still propagates.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun notifyReady(sourceId: UUID) {
        try {
            onReady?.invoke(sourceId)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // The OCR revision is READY and its spans are stored; the automatic run stays due for a later attempt.
        }
    }

    private suspend fun capture(sourceId: UUID): Capture = mutationQueue.withMutation {
        val source = database.sourceDao().findById(sourceId.toString())?.toDomain()
        if (source == null || !source.isReadyForOcr()) {
            return@withMutation Capture.Failure(OcrFailureReason.SOURCE_NOT_READY)
        }
        val check = authenticator.check(source, paths.blobFile(sourceId))
        ocrCaptureFailureFor(check)?.let { return@withMutation Capture.Failure(it) }
        val bytes = (check as ArtefactCheck.Verified).plaintext
        val input = try {
            OcrEngineInput(bytes, source.width!!, source.height!!, source.orientation!!, source.mimeType!!)
        } catch (_: IllegalArgumentException) {
            bytes.fill(0)
            return@withMutation Capture.Failure(OcrFailureReason.LIMIT_EXCEEDED)
        }
        val engine = engineRegistry.selected()
        val revision = OcrRevision(
            id = UUID.randomUUID(),
            sourceId = source.id,
            state = OcrRevisionState.RUNNING,
            engineId = engine.id,
            modelVersion = engine.modelVersion,
            orientation = source.orientation,
            sourceDigest = source.sha256!!.copyOf(),
            startedAt = clock(),
            extractedAt = null,
            reviewState = OcrReviewState.UNREVIEWED,
            failureReason = null,
            charCount = 0,
            spanCount = 0,
        )
        try {
            ocrDao.insertRevision(revision.toEntity())
            Capture.Ready(revision, input)
        } catch (cancelled: CancellationException) {
            // P2-02-R1: a run cancelled once its RUNNING row may exist must not
            // leave it RUNNING. Marked inside this mutation, not through
            // markTerminal, which would wait for this same mutation.
            withContext(NonCancellable) {
                markTerminalLocked(ocrDao, revision.id, OcrRevisionState.CANCELLED, OcrFailureReason.CANCELLED)
            }
            input.close()
            throw cancelled
        }
    }

    /**
     * Runs the engine under the one OCR deadline (P2-02-R2). A timeout from any
     * layer, including one raised inside the engine while this run is still
     * active, is [OcrRunStateMachine.EngineOutcome.TimedOut]. A cancellation of
     * this run marks the revision CANCELLED and propagates.
     */
    // The engine is the boundary with third-party code: anything it throws must
    // end as a typed failure on the revision, never escape the run.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun extract(prepared: Capture.Ready): OcrRunStateMachine.EngineOutcome = try {
        withTimeoutOrNull(deadlineMillis) { engineRegistry.selected().extract(prepared.input) }
            ?.let { OcrRunStateMachine.EngineOutcome.Output(it.spans) }
            ?: OcrRunStateMachine.EngineOutcome.TimedOut
    } catch (cancelled: CancellationException) {
        if (currentCoroutineContext().isActive) {
            OcrRunStateMachine.EngineOutcome.TimedOut
        } else {
            markTerminal(
                mutationQueue,
                ocrDao,
                prepared.revision.id,
                OcrRevisionState.CANCELLED,
                OcrFailureReason.CANCELLED,
            )
            throw cancelled
        }
    } catch (error: Exception) {
        OcrRunStateMachine.EngineOutcome.Failed(OcrRunStateMachine.reasonFor(error))
    }

    /**
     * The final commit (P2-02-R1): READY and its spans in one transaction that
     * cancellation cannot interrupt. A failure rolls both back and ends the
     * revision FAILED, so there is never a READY revision without its spans.
     */
    private suspend fun persist(sourceId: UUID, revision: OcrRevision, spans: List<OcrSpanDraft>): OcrRunResult =
        withContext(NonCancellable) {
            mutationQueue.withMutation {
                val current = ocrDao.findRevision(revision.id.toString())?.toDomain()
                val source = database.sourceDao().findById(sourceId.toString())?.toDomain()
                val stillWanted = current?.state == OcrRevisionState.RUNNING && source?.state == SourceState.READY
                if (current == null || !stillWanted) {
                    markTerminalLocked(ocrDao, revision.id, OcrRevisionState.STALE, OcrFailureReason.SOURCE_DELETED)
                    return@withMutation OcrRunResult.Stale(revision.id)
                }
                val completed = current.copy(
                    state = OcrRevisionState.READY,
                    extractedAt = clock(),
                    charCount = spans.sumOf { it.text.length },
                    spanCount = spans.size,
                )
                // A READY source has its size; without one no region can be checked, so none is kept.
                val sourceWidth = source?.width ?: 0
                val sourceHeight = source?.height ?: 0
                val persistedSpans = spans.mapIndexed { ordinal, span ->
                    OcrSpan(
                        id = UUID.randomUUID(),
                        revisionId = completed.id,
                        ordinal = ordinal,
                        text = span.text,
                        confidence = span.confidence,
                        coordinateSystem = OcrCoordinateSystem.SOURCE_PIXELS,
                        // P2-06-R17: a box past the source edge is not evidence.
                        evidenceRegion = span.region?.withinSource(sourceWidth, sourceHeight),
                    )
                }
                try {
                    // Compare-and-set (P1-18-R13): spans are written only if this
                    // run still owns a RUNNING revision; a lost race writes nothing.
                    val committed = database.withTransaction {
                        // P3-04-R5: the previous reading's facts go STALE in this transaction, with an epoch bump.
                        val previousReading = database.factDao().readingOfSource(sourceId.toString())?.id
                        val moved = ocrDao.completeRunning(
                            completed.id.toString(),
                            checkNotNull(completed.extractedAt),
                            completed.charCount,
                            completed.spanCount,
                        )
                        if (moved == 1) {
                            ocrDao.insertSpans(persistedSpans.map { it.toEntity() })
                            // C2-R14: the pointer moves only to a later-started reading,
                            // so a late finisher stays history (F-69).
                            val pointerMoved = ocrDao.moveCurrentReading(
                                sourceId.toString(),
                                completed.id.toString(),
                            ) == 1
                            // P-01: a late finisher that did not take the pointer leaves the current facts alone.
                            if (pointerMoved && previousReading != null && previousReading != completed.id.toString()) {
                                FactStaleMarker(database.factDao(), clock)
                                    .markForReading(previousReading, StaleReason.READING_REPLACED)
                            }
                        }
                        moved == 1
                    }
                    if (committed) {
                        OcrRunResult.Completed(completed.id, persistedSpans)
                    } else {
                        OcrRunResult.Stale(revision.id)
                    }
                } catch (_: Exception) {
                    markTerminalLocked(ocrDao, revision.id, OcrRevisionState.FAILED, OcrFailureReason.STORAGE_FAILURE)
                    OcrRunResult.Failed(revision.id, OcrFailureReason.STORAGE_FAILURE)
                }
            }
        }

    /**
     * P2-01-R1: the latest revision for [sourceId] in any state, with its
     * spans, their latest corrections, a whole-revision correction, and the
     * earlier revisions as history. Emits null when the source has no
     * revision, including after the source is deleted. Reads only; the caller
     * decides whether content may be shown (the app gates it behind its lock).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeOcrView(sourceId: UUID): Flow<OcrView?> {
        val views = database.ocrViewDao()
        return views.observeRevisionsNewestFirst(sourceId.toString())
            .flatMapLatest { revisions ->
                val latest = revisions.firstOrNull() ?: return@flatMapLatest flowOf(null)
                combine(
                    views.observeSpans(latest.id),
                    views.observeUserRevisions(latest.id),
                    database.ocrSpanReviewDao().observeForRevision(latest.id),
                ) { spans, corrections, reviews ->
                    ocrViewOf(latest, revisions.drop(1), spans, corrections, reviews)
                }
            }
            .flowOn(ioDispatcher)
    }

    suspend fun findRevision(revisionId: UUID): OcrRevision? =
        database.ocrDao().findRevision(revisionId.toString())?.toDomain()

    /**
     * P3-05-R3 (C2-R39): the reading [revisionId] names, not the source's latest. An earlier reading has no
     * history of its own here; it is shown as it was recorded. Emits null when the reading does not exist.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeOcrViewOfRevision(sourceId: UUID, revisionId: UUID): Flow<OcrView?> {
        val views = database.ocrViewDao()
        val id = revisionId.toString()
        return flow { emit(database.ocrDao().findRevisionOfSource(id, sourceId.toString())) }
            .flatMapLatest { revision ->
                if (revision == null) {
                    flowOf(null)
                } else {
                    combine(
                        views.observeSpans(id),
                        views.observeUserRevisions(id),
                        database.ocrSpanReviewDao().observeForRevision(id),
                    ) { spans, corrections, reviews ->
                        ocrViewOf(revision, emptyList(), spans, corrections, reviews)
                    }
                }
            }
            .flowOn(ioDispatcher)
    }

    suspend fun addCorrection(revisionId: UUID, spanId: UUID?, correctedText: String): Boolean =
        withContext(ioDispatcher) {
            mutationQueue.withMutation {
                val revision = database.ocrDao().findRevision(revisionId.toString())?.toDomain()
                    ?: return@withMutation false
                if (revision.state != OcrRevisionState.READY) return@withMutation false
                if (spanId != null) {
                    val span = database.ocrDao().findSpan(spanId.toString()) ?: return@withMutation false
                    if (span.revisionId != revisionId.toString()) return@withMutation false
                }
                database.withTransaction {
                    // C2-R15: name the current head explicitly; the trigger refuses anything else.
                    val head = database.ocrDao().currentCorrection(revisionId.toString(), spanId?.toString())
                    database.ocrDao().insertUserRevision(
                        OcrUserRevision(
                            id = UUID.randomUUID(),
                            revisionId = revisionId,
                            spanId = spanId,
                            correctedText = correctedText,
                            createdAt = clock(),
                            supersedesId = head?.let(UUID::fromString),
                        ).toEntity(),
                    )
                    // P3-04-R5: a span correction stales that span's facts; a whole-reading one stales the reading.
                    val marker = FactStaleMarker(database.factDao(), clock)
                    if (spanId != null) {
                        marker.markForSpan(spanId.toString(), StaleReason.SPAN_CORRECTED)
                    } else {
                        marker.markForReading(revisionId.toString(), StaleReason.READING_CORRECTED)
                    }
                }
                true
            }
        }

    /** Appends a REJECTED review for one span; refused if it is already rejected. The span text is unchanged. */
    suspend fun rejectSpan(spanId: UUID): Boolean = appendSpanReview(spanId, OcrSpanReviewAction.REJECT)

    /** Appends a review restoring the state before the span was rejected (P2-06-R13); rows are never updated. */
    suspend fun undoRejectSpan(spanId: UUID): Boolean = appendSpanReview(spanId, OcrSpanReviewAction.UNDO_REJECT)

    private suspend fun appendSpanReview(spanId: UUID, action: OcrSpanReviewAction): Boolean =
        withContext(ioDispatcher) {
            mutationQueue.withMutation {
                database.withTransaction {
                    val span = database.ocrDao().findSpan(spanId.toString()) ?: return@withTransaction false
                    val revision = database.ocrDao().findRevision(span.revisionId)?.toDomain()
                        ?: return@withTransaction false
                    val reviews = database.ocrSpanReviewDao().findForSpan(span.id).map { it.toDomain() }
                    val next = OcrSpanReviewRules.next(reviews, revision.state, action) ?: return@withTransaction false
                    database.ocrSpanReviewDao().append(UUID.randomUUID().toString(), span.id, next.name, clock())
                    // P3-04-R5: rejecting a span stales its facts; undoing a rejection never un-stales (C2 §9).
                    if (action == OcrSpanReviewAction.REJECT) {
                        FactStaleMarker(database.factDao(), clock).markForSpan(span.id, StaleReason.SPAN_REJECTED)
                    } else {
                        // P-04: an undo changes the fact inputs too, so an in-flight run is superseded.
                        database.factDao().sourceOfSpan(span.id)?.let { database.factDao().bumpFactInputEpoch(it) }
                    }
                    true
                }
            }
        }

    suspend fun setReviewState(revisionId: UUID, reviewState: OcrReviewState): Boolean = withContext(ioDispatcher) {
        check(reviewState != OcrReviewState.UNRECOGNISED) { "an unrecognised review state is never written" }
        mutationQueue.withMutation {
            // READY-only compare-and-set; a missing or non-READY revision changes nothing.
            database.withTransaction {
                val wasRejected = database.ocrDao().findRevision(revisionId.toString())?.toDomain()
                    ?.reviewState == OcrReviewState.REJECTED
                val moved = database.ocrDao().setReviewState(revisionId.toString(), reviewState.name) == 1
                // P3-04-R5: a rejected reading stales its facts in the same transaction.
                if (moved && reviewState == OcrReviewState.REJECTED) {
                    FactStaleMarker(database.factDao(), clock)
                        .markForReading(revisionId.toString(), StaleReason.READING_REJECTED)
                } else if (moved && wasRejected) {
                    // P-04: leaving REJECTED changes the fact inputs, so an in-flight run is superseded.
                    database.factDao().sourceOfReading(revisionId.toString())?.let {
                        database.factDao().bumpFactInputEpoch(it)
                    }
                }
                moved
            }
        }
    }

    private sealed interface Capture {
        data class Ready(val revision: OcrRevision, val input: OcrEngineInput) : Capture
        data class Failure(val reason: OcrFailureReason) : Capture
    }
}

/** Ends a RUNNING revision; NonCancellable so a cancelled run still records how it ended. */
private suspend fun markTerminal(
    mutationQueue: MutationQueue,
    ocrDao: OcrDao,
    revisionId: UUID,
    state: OcrRevisionState,
    reason: OcrFailureReason,
) {
    withContext(NonCancellable) {
        mutationQueue.withMutation { markTerminalLocked(ocrDao, revisionId, state, reason) }
    }
}

/** Callers must already hold the mutation. */
private suspend fun markTerminalLocked(
    ocrDao: OcrDao,
    revisionId: UUID,
    state: OcrRevisionState,
    reason: OcrFailureReason,
) {
    // RUNNING-only compare-and-set (P1-18); a revision that already ended keeps how it ended.
    ocrDao.finishRunning(revisionId.toString(), state.name, reason.name)
}

private fun org.openlife.vault.model.Source.isReadyForOcr(): Boolean = state == SourceState.READY &&
    listOf(byteCount, sha256, width, height, orientation, mimeType).all { it != null }

internal fun ocrViewOf(
    latest: OcrRevisionEntity,
    earlier: List<OcrRevisionEntity>,
    spans: List<OcrSpanEntity>,
    corrections: List<OcrUserRevisionEntity>,
    reviews: List<OcrSpanReviewEntity>,
): OcrView {
    // C2-R15: a span's current correction is the head of its supersession
    // chain (the one no other correction supersedes), never the clock.
    // A span's current review is its highest sequence, never the clock (P2-06-R12).
    val superseded = corrections.mapNotNullTo(mutableSetOf()) { it.supersedesId }
    val latestBySpan = corrections.filterNot { it.id in superseded }.associateBy { it.spanId }
    val latestReviewBySpan = reviews.groupBy { it.spanId }.mapValues { (_, rows) -> rows.maxBy { it.sequence } }
    return OcrView(
        revision = latest.toDomain(),
        spans = spans.map { span ->
            OcrSpanView(
                span.toDomain(),
                latestBySpan[span.id]?.toDomain(),
                latestReviewBySpan[span.id]?.toDomain(),
            )
        },
        revisionCorrection = latestBySpan[null]?.toDomain(),
        history = earlier.map { it.toDomain() },
    )
}

/**
 * Why OCR cannot capture a source, or null when it authenticated (P1-18-R8,
 * V-05). Only evidence about the stored bytes is SOURCE_CORRUPT; a Keystore or
 * I/O hiccup is retryable.
 */
internal fun ocrCaptureFailureFor(check: ArtefactCheck): OcrFailureReason? = when (check) {
    is ArtefactCheck.Verified -> null
    ArtefactCheck.Corrupt, ArtefactCheck.Missing -> OcrFailureReason.SOURCE_CORRUPT
    ArtefactCheck.Transient -> OcrFailureReason.SOURCE_TEMPORARILY_UNAVAILABLE
}
