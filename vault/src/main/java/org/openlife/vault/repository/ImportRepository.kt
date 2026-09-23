package org.openlife.vault.repository

import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

private data class StageWriteRequest(val sourceId: UUID, val plaintext: ByteArray, val dek: ByteArray)

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val authenticator = ArtefactAuthenticator(keystoreWrapper)

    /** The one-import-at-a-time rule (design §12); P1-15. */
    val importSlot = ImportSlot()
    private val duplicateVerifier = DuplicateVerifier(paths, sourceDao, authenticator)

    suspend fun prepareImport(
        inputStream: InputStream,
        declaredMimeType: String,
        intakeKind: IntakeKind,
        deadline: () -> Boolean = { false },
    ): PrepareResult {
        // Busy is decided by the import slot, not by lock state (P1-15-R1).
        // Preparation only creates a row and a file for a fresh UUID, so it
        // shares a read lease with viewers; deletion, recovery, save and
        // reset stay excluded.
        if (!importSlot.tryBeginPreparing()) return PrepareResult.Busy
        var result: PrepareResult? = null
        try {
            result = mutationQueue.withReadLease {
                prepareImportLocked(inputStream, declaredMimeType, intakeKind, deadline)
            }
            return result
        } finally {
            val prepared = result as? PrepareResult.Prepared
            if (prepared != null) importSlot.preparedAs(prepared.sourceId) else importSlot.preparationEnded()
        }
    }

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
            val insertFailure = insertStagedSource(stagedSource)
            if (insertFailure != null) {
                return insertFailure
            }

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

                val stageFailure = writeEncryptedStage(StageWriteRequest(sourceId, read.bytes, dek))
                if (stageFailure != null) {
                    return stageFailure
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
                val updateFailure = updatePreparedSource(updated)
                if (updateFailure != null) {
                    return updateFailure
                }

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

    private suspend fun writeEncryptedStage(request: StageWriteRequest): PrepareResult? = try {
        val artefactEnvelope = AesGcmCodec.encrypt(
            request.plaintext,
            SecretKeySpec(request.dek, "AES"),
            EnvelopeAad.forSource(EnvelopeDomain.ARTEFACT, request.sourceId),
        )
        fileOps.writeAndSync(paths.stageFile(request.sourceId), EnvelopeCodec.encode(artefactEnvelope))
        null
    } catch (error: IOException) {
        cancelStage(request.sourceId)
        prepareFailureFor(error)
    } catch (error: ErrnoException) {
        cancelStage(request.sourceId)
        prepareFailureFor(error)
    }

    private fun prepareFailureFor(error: Throwable): PrepareResult =
        if (IoFailureClassifier.classify(error) == IoFailureClassifier.Kind.STORAGE_UNAVAILABLE) {
            PrepareResult.StorageUnavailable
        } else {
            PrepareResult.Failed
        }

    private suspend fun insertStagedSource(source: Source): PrepareResult? = try {
        sourceDao.insert(source.toEntity())
        null
    } catch (error: IOException) {
        prepareFailureFor(error)
    } catch (error: ErrnoException) {
        prepareFailureFor(error)
    } catch (error: SQLiteFullException) {
        prepareFailureFor(error)
    } catch (_: Exception) {
        PrepareResult.Failed
    }

    private suspend fun updatePreparedSource(source: Source): PrepareResult? = try {
        sourceDao.update(source.toEntity())
        null
    } catch (error: IOException) {
        prepareFailureFor(error)
    } catch (error: ErrnoException) {
        prepareFailureFor(error)
    } catch (error: SQLiteFullException) {
        prepareFailureFor(error)
    } catch (_: Exception) {
        PrepareResult.Failed
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
    suspend fun cancelStagedImport(sourceId: UUID): Boolean = withContext(ioDispatcher) {
        try {
            mutationQueue.withMutation {
                val entity = sourceDao.findById(sourceId.toString())
                if (entity == null || entity.state != SourceState.STAGED.name) return@withMutation false
                cancelStage(sourceId)
            }
        } finally {
            importSlot.release(sourceId)
        }
    }

    /**
     * Design §11 "Save": recheck the stage and duplicate policy, authenticate
     * the stage, rename it to its final blob name and fsync, then commit
     * READY — success is returned only after that commit completes. Any
     * failure along the way leaves the row STAGED for startup recovery to
     * resolve; it is never reported as saved.
     */
    suspend fun saveImport(sourceId: UUID): SaveResult = withContext(ioDispatcher) {
        try {
            mutationQueue.withMutation {
                val entity = sourceDao.findById(sourceId.toString())
                if (entity == null || entity.state != SourceState.STAGED.name) {
                    return@withMutation SaveResult.StageNotFound
                }
                val source = entity.toDomain()

                val stageFile = paths.stageFile(sourceId)
                if (!stageFile.exists()) return@withMutation SaveResult.StageNotFound

                if (!authenticator.authenticates(source, stageFile)) return@withMutation SaveResult.Failed

                when (val duplicate = duplicateVerifier.check(source, stageFile)) {
                    DuplicateCheck.None -> Unit

                    is DuplicateCheck.Found -> {
                        cancelStage(sourceId)
                        return@withMutation SaveResult.DuplicateFound(duplicate.existingId)
                    }

                    DuplicateCheck.Unverifiable -> return@withMutation SaveResult.Failed
                }

                try {
                    val blobFile = paths.blobFile(sourceId)
                    if (!fileOps.rename(stageFile, blobFile)) return@withMutation SaveResult.Failed
                    fileOps.syncDirectory(paths.artefactsDir)
                } catch (error: IOException) {
                    return@withMutation saveFailureFor(error)
                } catch (error: ErrnoException) {
                    return@withMutation saveFailureFor(error)
                }

                try {
                    sourceDao.update(source.copy(state = SourceState.READY).toEntity())
                } catch (error: IOException) {
                    return@withMutation saveFailureFor(error)
                } catch (error: ErrnoException) {
                    return@withMutation saveFailureFor(error)
                } catch (_: SQLiteFullException) {
                    // The rename already happened, but the row remains STAGED when
                    // the final commit is rejected; recovery owns both paths.
                    return@withMutation SaveResult.StorageUnavailable
                } catch (_: Exception) {
                    // The rename already happened, but the row remains STAGED when
                    // the final commit is rejected (for example by the READY
                    // invariant trigger). Recovery owns both possible artefact paths
                    // and will remove them on the next pass; never surface success.
                    return@withMutation SaveResult.Failed
                }
                SaveResult.Saved(sourceId)
            }
        } finally {
            // Whatever the outcome, this import has been decided; a stage left
            // by a failure is recovery's to remove.
            importSlot.release(sourceId)
        }
    }

    private fun saveFailureFor(error: Throwable): SaveResult =
        if (IoFailureClassifier.classify(error) == IoFailureClassifier.Kind.STORAGE_UNAVAILABLE) {
            SaveResult.StorageUnavailable
        } else {
            SaveResult.Failed
        }
}
