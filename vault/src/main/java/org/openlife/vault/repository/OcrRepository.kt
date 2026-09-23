package org.openlife.vault.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrEngineInput
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrOutputValidator
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRevision
import org.openlife.vault.ocr.OcrRevisionState
import org.openlife.vault.ocr.OcrRunResult
import org.openlife.vault.ocr.OcrScriptPolicy
import org.openlife.vault.ocr.OcrScriptStatus
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.ocr.OcrSpanDraft
import org.openlife.vault.ocr.OcrUnsupportedScriptException
import org.openlife.vault.ocr.OcrUserRevision
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
) {
    private val authenticator = ArtefactAuthenticator(keystoreWrapper)

    suspend fun runOcr(sourceId: UUID): OcrRunResult = withContext(ioDispatcher) {
        val prepared = capture(sourceId)
        if (prepared is Capture.Failure) return@withContext OcrRunResult.Failed(null, prepared.reason)
        prepared as Capture.Ready

        when (val extracted = extract(prepared)) {
            is Extraction.Finished -> extracted.result
            is Extraction.Ready -> persist(sourceId, prepared.revision, extracted.spans)
        }
    }

    private suspend fun capture(sourceId: UUID): Capture = mutationQueue.withMutation {
        val source = database.sourceDao().findById(sourceId.toString())?.toDomain()
        if (source == null) {
            Capture.Failure(OcrFailureReason.SOURCE_NOT_READY)
        } else if (!source.isReadyForOcr()) {
            Capture.Failure(OcrFailureReason.SOURCE_NOT_READY)
        } else {
            val bytes = authenticator.decryptAndVerify(source, paths.blobFile(sourceId))
            if (bytes == null) {
                Capture.Failure(OcrFailureReason.SOURCE_CORRUPT)
            } else {
                val engine = engineRegistry.selected()
                val revision = OcrRevision(
                    id = UUID.randomUUID(),
                    sourceId = source.id,
                    state = OcrRevisionState.RUNNING,
                    engineId = engine.id,
                    modelVersion = engine.modelVersion,
                    orientation = source.orientation!!,
                    sourceDigest = source.sha256!!.copyOf(),
                    startedAt = clock(),
                    extractedAt = null,
                    reviewState = org.openlife.vault.ocr.OcrReviewState.UNREVIEWED,
                    failureReason = null,
                    charCount = 0,
                    spanCount = 0,
                )
                database.ocrDao().insertRevision(revision.toEntity())
                Capture.Ready(
                    revision = revision,
                    input = OcrEngineInput(
                        bytes,
                        source.width!!,
                        source.height!!,
                        source.orientation,
                        source.mimeType!!,
                    ),
                )
            }
        }
    }

    private fun org.openlife.vault.model.Source.isReadyForOcr(): Boolean = state == SourceState.READY &&
        listOf(byteCount, sha256, width, height, orientation, mimeType).all { it != null }

    private suspend fun extract(prepared: Capture.Ready): Extraction {
        val output = try {
            withTimeoutOrNull(org.openlife.vault.ocr.OcrLimits.DEADLINE_MILLIS) {
                engineRegistry.selected().extract(prepared.input)
            }
        } catch (cancelled: CancellationException) {
            markCancelled(prepared.revision.id)
            throw cancelled
        } catch (_: OcrUnsupportedScriptException) {
            markFailed(prepared.revision.id, OcrFailureReason.UNSUPPORTED_SCRIPT)
            return Extraction.Finished(
                OcrRunResult.Failed(prepared.revision.id, OcrFailureReason.UNSUPPORTED_SCRIPT),
            )
        } catch (_: IllegalArgumentException) {
            markFailed(prepared.revision.id, OcrFailureReason.LIMIT_EXCEEDED)
            return Extraction.Finished(
                OcrRunResult.Failed(prepared.revision.id, OcrFailureReason.LIMIT_EXCEEDED),
            )
        } catch (_: Exception) {
            markFailed(prepared.revision.id, OcrFailureReason.ENGINE_FAILURE)
            return Extraction.Finished(
                OcrRunResult.Failed(prepared.revision.id, OcrFailureReason.ENGINE_FAILURE),
            )
        }
        if (output == null) {
            markCancelled(prepared.revision.id)
            return Extraction.Finished(
                OcrRunResult.Cancelled(prepared.revision.id),
            )
        }

        val spans = try {
            OcrOutputValidator.validate(output.spans)
        } catch (_: IllegalArgumentException) {
            markFailed(prepared.revision.id, OcrFailureReason.LIMIT_EXCEEDED)
            return Extraction.Finished(
                OcrRunResult.Failed(prepared.revision.id, OcrFailureReason.LIMIT_EXCEEDED),
            )
        }
        if (OcrScriptPolicy.classify(spans) == OcrScriptStatus.UNSUPPORTED) {
            markFailed(prepared.revision.id, OcrFailureReason.UNSUPPORTED_SCRIPT)
            return Extraction.Finished(
                OcrRunResult.Failed(prepared.revision.id, OcrFailureReason.UNSUPPORTED_SCRIPT),
            )
        }
        return Extraction.Ready(spans)
    }

    private suspend fun persist(sourceId: UUID, revision: OcrRevision, spans: List<OcrSpanDraft>): OcrRunResult =
        mutationQueue.withMutation {
            val current = database.ocrDao().findRevision(revision.id.toString())?.toDomain()
            val source = database.sourceDao().findById(sourceId.toString())?.toDomain()
            if (current == null || current.state != OcrRevisionState.RUNNING || source?.state != SourceState.READY) {
                if (current?.state == OcrRevisionState.RUNNING) {
                    database.ocrDao().updateRevision(
                        current.copy(
                            state = OcrRevisionState.STALE,
                            failureReason = OcrFailureReason.SOURCE_DELETED,
                        ).toEntity(),
                    )
                }
                OcrRunResult.Stale(revision.id)
            } else {
                val completed = current.copy(
                    state = OcrRevisionState.READY,
                    extractedAt = clock(),
                    charCount = spans.sumOf { it.text.length },
                    spanCount = spans.size,
                )
                database.ocrDao().updateRevision(completed.toEntity())
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
                database.ocrDao().insertSpans(persistedSpans.map { it.toEntity() })
                OcrRunResult.Completed(completed.id, persistedSpans)
            }
        }

    suspend fun findRevision(revisionId: UUID): OcrRevision? =
        database.ocrDao().findRevision(revisionId.toString())?.toDomain()

    suspend fun findSpans(revisionId: UUID): List<OcrSpan> =
        database.ocrDao().findSpans(revisionId.toString()).map { it.toDomain() }

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

    private suspend fun markCancelled(revisionId: UUID) {
        withContext(NonCancellable) {
            mutationQueue.withMutation {
                val revision = database.ocrDao().findRevision(revisionId.toString())?.toDomain() ?: return@withMutation
                if (revision.state == OcrRevisionState.RUNNING) {
                    database.ocrDao().updateRevision(
                        revision.copy(
                            state = OcrRevisionState.CANCELLED,
                            failureReason = OcrFailureReason.CANCELLED,
                        ).toEntity(),
                    )
                }
            }
        }
    }

    private suspend fun markFailed(revisionId: UUID, reason: OcrFailureReason) {
        mutationQueue.withMutation {
            val revision = database.ocrDao().findRevision(revisionId.toString())?.toDomain() ?: return@withMutation
            if (revision.state == OcrRevisionState.RUNNING) {
                database.ocrDao().updateRevision(
                    revision.copy(state = OcrRevisionState.FAILED, failureReason = reason).toEntity(),
                )
            }
        }
    }

    private sealed interface Capture {
        data class Ready(val revision: OcrRevision, val input: OcrEngineInput) : Capture
        data class Failure(val reason: OcrFailureReason) : Capture
    }

    private sealed interface Extraction {
        data class Ready(val spans: List<OcrSpanDraft>) : Extraction
        data class Finished(val result: OcrRunResult) : Extraction
    }
}
