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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openlife.vault.crypto.KeystoreWrapper
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
import org.openlife.vault.ocr.OcrSpanView
import org.openlife.vault.ocr.OcrUserRevision
import org.openlife.vault.ocr.OcrView
import org.openlife.vault.storage.OcrDao
import org.openlife.vault.storage.OcrRevisionEntity
import org.openlife.vault.storage.OcrSpanEntity
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
) {
    private val authenticator = ArtefactAuthenticator(keystoreWrapper)

    suspend fun runOcr(sourceId: UUID): OcrRunResult = withContext(ioDispatcher) {
        val prepared = capture(sourceId)
        if (prepared is Capture.Failure) return@withContext OcrRunResult.Failed(null, prepared.reason)
        prepared as Capture.Ready
        try {
            when (val decision = OcrRunStateMachine.decide(extract(prepared))) {
                is OcrRunStateMachine.Decision.Persist -> persist(sourceId, prepared.revision, decision.spans)

                is OcrRunStateMachine.Decision.Terminal -> {
                    markTerminal(prepared.revision.id, decision.state, decision.reason)
                    OcrRunStateMachine.resultFor(prepared.revision.id, decision)
                }
            }
        } finally {
            // P2-02-R5: the captured plaintext is not kept past the run.
            prepared.input.close()
        }
    }

    private suspend fun capture(sourceId: UUID): Capture = mutationQueue.withMutation {
        val source = database.sourceDao().findById(sourceId.toString())?.toDomain()
        if (source == null || !source.isReadyForOcr()) {
            return@withMutation Capture.Failure(OcrFailureReason.SOURCE_NOT_READY)
        }
        val bytes = authenticator.decryptAndVerify(source, paths.blobFile(sourceId))
            ?: return@withMutation Capture.Failure(OcrFailureReason.SOURCE_CORRUPT)
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
                markTerminalLocked(revision.id, OcrRevisionState.CANCELLED, OcrFailureReason.CANCELLED)
            }
            input.close()
            throw cancelled
        }
    }

    private fun org.openlife.vault.model.Source.isReadyForOcr(): Boolean = state == SourceState.READY &&
        listOf(byteCount, sha256, width, height, orientation, mimeType).all { it != null }

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
            markTerminal(prepared.revision.id, OcrRevisionState.CANCELLED, OcrFailureReason.CANCELLED)
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
                    markTerminalLocked(revision.id, OcrRevisionState.STALE, OcrFailureReason.SOURCE_DELETED)
                    return@withMutation OcrRunResult.Stale(revision.id)
                }
                val completed = current.copy(
                    state = OcrRevisionState.READY,
                    extractedAt = clock(),
                    charCount = spans.sumOf { it.text.length },
                    spanCount = spans.size,
                )
                val persistedSpans = spans.mapIndexed { ordinal, span ->
                    OcrSpan(
                        id = UUID.randomUUID(),
                        revisionId = completed.id,
                        ordinal = ordinal,
                        text = span.text,
                        confidence = span.confidence,
                        coordinateSystem = OcrCoordinateSystem.SOURCE_PIXELS,
                        evidenceRegion = span.region,
                    )
                }
                try {
                    database.withTransaction {
                        ocrDao.updateRevision(completed.toEntity())
                        ocrDao.insertSpans(persistedSpans.map { it.toEntity() })
                    }
                    OcrRunResult.Completed(completed.id, persistedSpans)
                } catch (_: Exception) {
                    markTerminalLocked(revision.id, OcrRevisionState.FAILED, OcrFailureReason.STORAGE_FAILURE)
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
                combine(views.observeSpans(latest.id), views.observeUserRevisions(latest.id)) { spans, corrections ->
                    ocrViewOf(latest, revisions.drop(1), spans, corrections)
                }
            }
            .flowOn(ioDispatcher)
    }

    suspend fun findRevision(revisionId: UUID): OcrRevision? =
        database.ocrDao().findRevision(revisionId.toString())?.toDomain()

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
                database.ocrDao().insertUserRevision(
                    OcrUserRevision(
                        id = UUID.randomUUID(),
                        revisionId = revisionId,
                        spanId = spanId,
                        correctedText = correctedText,
                        createdAt = clock(),
                    ).toEntity(),
                )
                true
            }
        }

    suspend fun setReviewState(revisionId: UUID, reviewState: OcrReviewState): Boolean = withContext(ioDispatcher) {
        mutationQueue.withMutation {
            val revision = database.ocrDao().findRevision(revisionId.toString())?.toDomain()
                ?: return@withMutation false
            if (revision.state != OcrRevisionState.READY) return@withMutation false
            database.ocrDao().updateRevision(revision.copy(reviewState = reviewState).toEntity())
            true
        }
    }

    /** Ends a RUNNING revision; NonCancellable so a cancelled run still records how it ended. */
    private suspend fun markTerminal(revisionId: UUID, state: OcrRevisionState, reason: OcrFailureReason) {
        withContext(NonCancellable) {
            mutationQueue.withMutation { markTerminalLocked(revisionId, state, reason) }
        }
    }

    /** Callers must already hold the mutation. */
    private suspend fun markTerminalLocked(revisionId: UUID, state: OcrRevisionState, reason: OcrFailureReason) {
        val revision = ocrDao.findRevision(revisionId.toString())?.toDomain() ?: return
        if (revision.state == OcrRevisionState.RUNNING) {
            ocrDao.updateRevision(revision.copy(state = state, failureReason = reason).toEntity())
        }
    }

    private sealed interface Capture {
        data class Ready(val revision: OcrRevision, val input: OcrEngineInput) : Capture
        data class Failure(val reason: OcrFailureReason) : Capture
    }
}

private fun ocrViewOf(
    latest: OcrRevisionEntity,
    earlier: List<OcrRevisionEntity>,
    spans: List<OcrSpanEntity>,
    corrections: List<OcrUserRevisionEntity>,
): OcrView {
    // Corrections arrive oldest first, so the last one per span is the latest.
    val latestBySpan = corrections.associateBy { it.spanId }
    return OcrView(
        revision = latest.toDomain(),
        spans = spans.map { span -> OcrSpanView(span.toDomain(), latestBySpan[span.id]?.toDomain()) },
        revisionCorrection = latestBySpan[null]?.toDomain(),
        history = earlier.map { it.toDomain() },
    )
}
