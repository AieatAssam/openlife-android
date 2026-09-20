package org.openlife.vault.repository

import org.openlife.vault.crypto.AesGcmCodec
import org.openlife.vault.crypto.EnvelopeAad
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.EnvelopeDomain
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.SourceDao
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.toDomain
import org.openlife.vault.storage.toEntity
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

private const val DEK_LENGTH_BYTES = 32
private const val SCHEMA_ARTEFACT_VERSION = 1
private const val RESERVED_STORAGE_BYTES = 8L * 1024 * 1024
private const val MINIMUM_AVAILABLE_STORAGE_BYTES =
    2L * ImportLimits.MAX_ORIGINAL_BYTES + RESERVED_STORAGE_BYTES

fun interface PlaintextBufferObserver {
    fun onReleased(bytes: ByteArray)
}

/**
 * The "Prepare and preview" and "Save" halves of design §11
 * (STAGED -> READY). Startup recovery is [RecoveryRepository].
 *
 * Duplicate detection lives in [saveImport], not [prepareImport]: design
 * §11 places it in the Save step ("recheck the stage and duplicate policy
 * under the mutation queue"), not at prepare time.
 *
 * Platform URI access stays at the intake boundary (app module); this
 * class only ever sees an already-opened, already-validated-shape
 * [InputStream] — never a `Uri`, filename, or provider-supplied path
 * (design §7 repository layout).
 */
class ImportRepository(
    private val paths: VaultPaths,
    private val database: OpenLifeDatabase,
    private val keystoreWrapper: KeystoreWrapper,
    private val bitmapSampler: BitmapSampler,
    private val mutationQueue: MutationQueue,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fileOps: ArtefactFileOps = ArtefactFileOps.Default,
    private val plaintextBufferObserver: PlaintextBufferObserver = PlaintextBufferObserver { },
    private val storageSpace: StorageSpace = StorageSpace.Default,
    private val sourceDao: SourceDao = database.sourceDao(),
) {
    private val authenticator = ArtefactAuthenticator(keystoreWrapper)

    suspend fun prepareImport(
        inputStream: InputStream,
        declaredMimeType: String,
        intakeKind: IntakeKind,
        deadline: () -> Boolean = { false },
    ): PrepareResult = mutationQueue.tryAcquire {
        prepareImportLocked(inputStream, declaredMimeType, intakeKind, deadline)
    }
        ?: PrepareResult.Busy

    private suspend fun prepareImportLocked(
        inputStream: InputStream,
        declaredMimeType: String,
        intakeKind: IntakeKind,
        deadline: () -> Boolean,
    ): PrepareResult {
        if (!hasEnoughStorage()) {
            return PrepareResult.Rejected(ImageRejectionReason.STORAGE_UNAVAILABLE)
        }

        val sourceId = UUID.randomUUID()
        val dek = ByteArray(DEK_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

        try {
            val wrappedDekEnvelope = keystoreWrapper.wrap(dek, EnvelopeDomain.SOURCE_KEY, sourceId)
            val stagedSource = Source(
                id = sourceId,
                state = SourceState.STAGED,
                importedAt = clock(),
                intakeKind = intakeKind,
                mimeType = null,
                byteCount = null,
                sha256 = null,
                width = null,
                height = null,
                orientation = null,
                wrappedDek = EnvelopeCodec.encode(wrappedDekEnvelope),
                artefactVersion = null,
            )
            // The STAGED row, with its wrapped key, is committed before any
            // file is written - this is what lets recovery find interrupted
            // work (design §11 step 2).
            sourceDao.insert(stagedSource.toEntity())

            val read = try {
                BoundedStreamReader.read(inputStream, deadline)
            } catch (_: IOException) {
                cancelStage(sourceId)
                return PrepareResult.Failed
            }

            try {
                val validation = ImageHeaderValidator.validate(declaredMimeType, read.bytes)
                if (validation is ImageValidationResult.Rejected) {
                    cancelStage(sourceId)
                    return PrepareResult.Rejected(validation.reason)
                }
                val valid = validation as ImageValidationResult.Valid

                if (!bitmapSampler.canSample(read.bytes)) {
                    cancelStage(sourceId)
                    return PrepareResult.Rejected(ImageRejectionReason.CORRUPT_CONTENT)
                }

                try {
                    val artefactEnvelope = AesGcmCodec.encrypt(
                        read.bytes,
                        SecretKeySpec(dek, "AES"),
                        EnvelopeAad.forSource(EnvelopeDomain.ARTEFACT, sourceId),
                    )
                    fileOps.writeAndSync(paths.stageFile(sourceId), EnvelopeCodec.encode(artefactEnvelope))
                } catch (error: Exception) {
                    cancelStage(sourceId)
                    return if (IoFailureClassifier.classify(error) == IoFailureClassifier.Kind.STORAGE_UNAVAILABLE) {
                        PrepareResult.StorageUnavailable
                    } else {
                        PrepareResult.Failed
                    }
                }

                val updated = stagedSource.copy(
                    mimeType = valid.format,
                    byteCount = read.bytes.size.toLong(),
                    sha256 = read.sha256,
                    width = valid.width,
                    height = valid.height,
                    orientation = if (valid.format == ImageFormat.JPEG) {
                        ExifOrientationParser.parse(read.bytes)
                    } else {
                        Orientation.NORMAL
                    },
                    artefactVersion = SCHEMA_ARTEFACT_VERSION,
                )
                sourceDao.update(updated.toEntity())

                return PrepareResult.Prepared(
                    sourceId,
                    valid.format,
                    valid.width,
                    valid.height,
                    read.bytes.size.toLong(),
                    updated.orientation ?: Orientation.NORMAL,
                )
            } finally {
                read.bytes.fill(0)
                plaintextBufferObserver.onReleased(read.bytes)
            }
        } finally {
            dek.fill(0)
        }
    }

    private fun hasEnoughStorage(): Boolean = try {
        storageSpace.availableBytes(paths.artefactsDir) >= MINIMUM_AVAILABLE_STORAGE_BYTES
    } catch (_: Exception) {
        false
    }

    /**
     * Removes a STAGED row and both possible artefact files. Never touches a
     * READY row. If either file cannot be removed, retain the row so startup
     * recovery (or an explicit retry) still owns the artefact and can try
     * cleanup again (design §11: failed cleanup is recoverable state).
     */
    private suspend fun cancelStage(sourceId: UUID): Boolean {
        val stageRemoved = fileOps.deleteIfExists(paths.stageFile(sourceId))
        val blobRemoved = fileOps.deleteIfExists(paths.blobFile(sourceId))
        if (!stageRemoved || !blobRemoved) return false
        sourceDao.deleteById(sourceId.toString())
        return true
    }

    /**
     * User-initiated Cancel from the preview screen (design §8: "no undo"
     * applies to Save, not to cancelling a not-yet-saved preview). Returns
     * false if the row is not actually STAGED (e.g. already saved or
     * already cancelled) rather than acting on a row this call shouldn't
     * touch.
     */
    suspend fun cancelStagedImport(sourceId: UUID): Boolean = mutationQueue.acquire {
        val entity = sourceDao.findById(sourceId.toString())
        if (entity == null || entity.state != SourceState.STAGED.name) return@acquire false
        cancelStage(sourceId)
    }

    /**
     * Design §11 "Save": recheck the stage and duplicate policy, authenticate
     * the stage, rename it to its final blob name and fsync, then commit
     * READY — success is returned only after that commit completes. Any
     * failure along the way leaves the row STAGED for startup recovery to
     * resolve; it is never reported as saved.
     */
    suspend fun saveImport(sourceId: UUID): SaveResult = mutationQueue.acquire {
        val entity = sourceDao.findById(sourceId.toString())
        if (entity == null || entity.state != SourceState.STAGED.name) {
            return@acquire SaveResult.StageNotFound
        }
        val source = entity.toDomain()

        val stageFile = paths.stageFile(sourceId)
        if (!stageFile.exists()) return@acquire SaveResult.StageNotFound

        if (!authenticator.authenticates(source, stageFile)) return@acquire SaveResult.Failed

        val duplicate = sourceDao.findReadyDuplicate(source.byteCount!!, source.sha256!!)
        if (duplicate != null) {
            cancelStage(sourceId)
            return@acquire SaveResult.DuplicateFound(UUID.fromString(duplicate.id))
        }

        try {
            val blobFile = paths.blobFile(sourceId)
            if (!fileOps.rename(stageFile, blobFile)) return@acquire SaveResult.Failed
            fileOps.syncDirectory(paths.artefactsDir)
        } catch (error: Exception) {
            return@acquire if (IoFailureClassifier.classify(error) == IoFailureClassifier.Kind.STORAGE_UNAVAILABLE) {
                SaveResult.StorageUnavailable
            } else {
                SaveResult.Failed
            }
        }

        try {
            sourceDao.update(source.copy(state = SourceState.READY).toEntity())
        } catch (error: Exception) {
            // The rename already happened, but the row remains STAGED when
            // the final commit is rejected (for example by the READY
            // invariant trigger). Recovery owns both possible artefact paths
            // and will remove them on the next pass; never surface success.
            return@acquire if (IoFailureClassifier.classify(error) == IoFailureClassifier.Kind.STORAGE_UNAVAILABLE) {
                SaveResult.StorageUnavailable
            } else {
                SaveResult.Failed
            }
        }
        SaveResult.Saved(sourceId)
    }
}
