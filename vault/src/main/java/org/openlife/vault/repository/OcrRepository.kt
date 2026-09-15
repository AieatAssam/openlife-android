package org.openlife.vault.repository

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrEngineInput
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrOutputValidator
import org.openlife.vault.ocr.OcrRevision
import org.openlife.vault.ocr.OcrRevisionState
import org.openlife.vault.ocr.OcrRunResult
import org.openlife.vault.ocr.OcrScriptPolicy
import org.openlife.vault.ocr.OcrScriptStatus
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.ocr.OcrUnsupportedScriptException
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.toDomain
import org.openlife.vault.storage.toEntity

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
) {
    private val authenticator = ArtefactAuthenticator(keystoreWrapper)

    suspend fun runOcr(sourceId: UUID): OcrRunResult {
        val prepared = mutationQueue.acquire {
            val source = database.sourceDao().findById(sourceId.toString())?.toDomain()
                ?: return@acquire null
            if (source.state != SourceState.READY || source.byteCount == null || source.sha256 == null ||
                source.width == null || source.height == null || source.orientation == null || source.mimeType == null
            ) {
                return@acquire Capture.Failure(OcrFailureReason.SOURCE_NOT_READY)
            }
            val bytes = authenticator.decryptAndVerify(source, paths.blobFile(sourceId))
                ?: return@acquire Capture.Failure(OcrFailureReason.SOURCE_CORRUPT)
            val revision = OcrRevision(
                id = UUID.randomUUID(),
                sourceId = source.id,
                state = OcrRevisionState.RUNNING,
                engineId = engineRegistry.selected().id,
                modelVersion = engineRegistry.selected().modelVersion,
                orientation = source.orientation,
                sourceDigest = source.sha256.copyOf(),
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
                input = OcrEngineInput(bytes, source.width, source.height, source.orientation, source.mimeType),
            )
        }

        when (prepared) {
            null -> return OcrRunResult.Failed(null, OcrFailureReason.SOURCE_NOT_READY)
            is Capture.Failure -> return OcrRunResult.Failed(null, prepared.reason)
            is Capture.Ready -> Unit
        }
        prepared as Capture.Ready

        val output = try {
            withTimeoutOrNull(org.openlife.vault.ocr.OcrLimits.DEADLINE_MILLIS) {
                engineRegistry.selected().extract(prepared.input)
            } ?: run {
                markCancelled(prepared.revision.id)
                return OcrRunResult.Cancelled(prepared.revision.id)
            }
        } catch (cancelled: CancellationException) {
            markCancelled(prepared.revision.id)
            throw cancelled
        } catch (_: OcrUnsupportedScriptException) {
            markFailed(prepared.revision.id, OcrFailureReason.UNSUPPORTED_SCRIPT)
            return OcrRunResult.Failed(prepared.revision.id, OcrFailureReason.UNSUPPORTED_SCRIPT)
        } catch (_: IllegalArgumentException) {
            markFailed(prepared.revision.id, OcrFailureReason.LIMIT_EXCEEDED)
            return OcrRunResult.Failed(prepared.revision.id, OcrFailureReason.LIMIT_EXCEEDED)
        } catch (_: Exception) {
            markFailed(prepared.revision.id, OcrFailureReason.ENGINE_FAILURE)
            return OcrRunResult.Failed(prepared.revision.id, OcrFailureReason.ENGINE_FAILURE)
        }

        val spans = try {
            OcrOutputValidator.validate(output.spans)
        } catch (_: IllegalArgumentException) {
            markFailed(prepared.revision.id, OcrFailureReason.LIMIT_EXCEEDED)
            return OcrRunResult.Failed(prepared.revision.id, OcrFailureReason.LIMIT_EXCEEDED)
        }
        if (OcrScriptPolicy.classify(spans) == OcrScriptStatus.UNSUPPORTED) {
            markFailed(prepared.revision.id, OcrFailureReason.UNSUPPORTED_SCRIPT)
            return OcrRunResult.Failed(prepared.revision.id, OcrFailureReason.UNSUPPORTED_SCRIPT)
        }

        return mutationQueue.acquire {
            val current = database.ocrDao().findRevision(prepared.revision.id.toString())?.toDomain()
                ?: return@acquire OcrRunResult.Stale(prepared.revision.id)
            val source = database.sourceDao().findById(sourceId.toString())?.toDomain()
            if (current.state != OcrRevisionState.RUNNING || source?.state != SourceState.READY) {
                if (current.state == OcrRevisionState.RUNNING) {
                    database.ocrDao().updateRevision(
                        current.copy(state = OcrRevisionState.STALE, failureReason = OcrFailureReason.SOURCE_DELETED).toEntity(),
                    )
                }
                return@acquire OcrRunResult.Stale(prepared.revision.id)
            }
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

    private suspend fun markCancelled(revisionId: UUID) {
        withContext(NonCancellable) {
            mutationQueue.acquire {
                val revision = database.ocrDao().findRevision(revisionId.toString())?.toDomain() ?: return@acquire
                if (revision.state == OcrRevisionState.RUNNING) {
                    database.ocrDao().updateRevision(
                        revision.copy(state = OcrRevisionState.CANCELLED, failureReason = OcrFailureReason.CANCELLED).toEntity(),
                    )
                }
            }
        }
    }

    private suspend fun markFailed(revisionId: UUID, reason: OcrFailureReason) {
        mutationQueue.acquire {
            val revision = database.ocrDao().findRevision(revisionId.toString())?.toDomain() ?: return@acquire
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
}
