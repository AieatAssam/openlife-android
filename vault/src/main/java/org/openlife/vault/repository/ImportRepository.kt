package org.openlife.vault.repository

import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.openlife.vault.crypto.AesGcmCodec
import org.openlife.vault.crypto.EnvelopeAad
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.EnvelopeDomain
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.crypto.MissingWrappingKeyException
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.SourceDao
import org.openlife.vault.storage.StateTransition
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.compareAndSetState
import org.openlife.vault.storage.toDomain
import org.openlife.vault.storage.toEntity
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.ProviderException
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
        if (!importSlot.tryBeginPreparing()) return PrepareResult.Busy
        var result: PrepareResult? = null
        var stagedId: UUID? = null
        try {
            result = prepareWithSlot(inputStream, declaredMimeType, intakeKind, deadline) { stagedId = it }
            return result
        } catch (cancelled: CancellationException) {
            // Design §11: any failure before a usable preview cancels the
            // stage, including the caller going away mid-import (P1-01).
            stagedId?.let { id -> withContext(NonCancellable) { mutationQueue.withReadLease { cancelStage(id) } } }
            throw cancelled
        } catch (_: OutOfMemoryError) {
            // CI run 45405099804: a large original can exhaust a small heap
            // while it is read or encrypted. That is an import failure, not a
            // crash, and leaves no stage behind.
            stagedId?.let { id -> withContext(NonCancellable) { mutationQueue.withReadLease { cancelStage(id) } } }
            result = PrepareResult.Failed
            return result
        } finally {
            if (result !is PrepareResult.Prepared) importSlot.preparationEnded()
        }
    }

    /**
     * The provider read, validation and sampling happen before any lease
     * (P1-18-R6): a stalled cloud provider holds nothing that viewers,
     * deletion or reset wait on. Only the STAGED row insert, the stage write
     * and the metadata update run under a read lease; they create a row and
     * a file for a fresh UUID only, so they share the lease with viewers.
     */
    private suspend fun prepareWithSlot(
        inputStream: InputStream,
        declaredMimeType: String,
        intakeKind: IntakeKind,
        deadline: () -> Boolean,
        onStaged: (UUID) -> Unit,
    ): PrepareResult {
        if (!hasEnoughStorage()) {
            return PrepareResult.Rejected(ImageRejectionReason.STORAGE_UNAVAILABLE)
        }
        val read = try {
            BoundedStreamReader.read(inputStream, deadline)
        } catch (_: IOException) {
            return PrepareResult.Failed
        }
        try {
            if (read.exceededLimit) return PrepareResult.Rejected(ImageRejectionReason.EXCEEDS_BYTE_LIMIT)
            val validation = ImageHeaderValidator.validate(declaredMimeType, read.bytes)
            if (validation is ImageValidationResult.Rejected) return PrepareResult.Rejected(validation.reason)
            val valid = validation as ImageValidationResult.Valid
            if (!bitmapSampler.canSample(read.bytes)) {
                return PrepareResult.Rejected(ImageRejectionReason.CORRUPT_CONTENT)
            }
            return mutationQueue.withReadLease {
                stageLocked(read, valid, intakeKind, onStaged).also {
                    // Inside the lease: a mutation that reads the slot (for
                    // example abandoned-stage cleanup, P1-01) then never sees
                    // this new stage without its owner.
                    if (it is PrepareResult.Prepared) importSlot.preparedAs(it.sourceId)
                }
            }
        } finally {
            read.bytes.fill(0)
            plaintextBufferObserver.onReleased(read.bytes)
        }
    }

    private suspend fun stageLocked(
        read: BoundedRead,
        valid: ImageValidationResult.Valid,
        intakeKind: IntakeKind,
        onStaged: (UUID) -> Unit,
    ): PrepareResult {
        val sourceId = UUID.randomUUID()
        val dek = ByteArray(DEK_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            val wrappedDekEnvelope = keystoreWrapper.wrapSourceKeyOrNull(dek, sourceId) ?: return PrepareResult.Failed
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
            insertStagedSource(stagedSource)?.let { return it }
            onStaged(sourceId)

            val stageFailure = writeEncryptedStage(StageWriteRequest(sourceId, read.bytes, dek))
            if (stageFailure != null) return stageFailure

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
            updatePreparedSource(updated)?.let { return it }

            return PrepareResult.Prepared(
                sourceId,
                valid.format,
                valid.width,
                valid.height,
                read.bytes.size.toLong(),
                updated.orientation ?: Orientation.NORMAL,
            )
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
    } catch (cancelled: CancellationException) {
        // The row may already be committed; never leave it behind (P1-01).
        withContext(NonCancellable) { cancelStage(source.id) }
        throw cancelled
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
     * Records the validated metadata. Any failure cancels the stage: the
     * encrypted file is already written, and design §11 says a failure before
     * a usable preview leaves no stage behind. Cancellation is rethrown, not
     * reported as a failure (the caller's cleanup handles it).
     */
    private suspend fun updatePreparedSource(source: Source): PrepareResult? {
        val failure = try {
            // Compare-and-set on STAGED (P1-18-R13): a row that is no longer
            // STAGED is never overwritten with this snapshot.
            val changed = sourceDao.updateStagedMetadata(
                id = source.id.toString(),
                mimeType = checkNotNull(source.mimeType).mimeType,
                byteCount = checkNotNull(source.byteCount),
                sha256 = checkNotNull(source.sha256),
                width = checkNotNull(source.width),
                height = checkNotNull(source.height),
                orientation = checkNotNull(source.orientation).name,
                artefactVersion = checkNotNull(source.artefactVersion),
            )
            if (changed == 1) return null
            PrepareResult.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            prepareFailureFor(error)
        } catch (error: ErrnoException) {
            prepareFailureFor(error)
        } catch (error: SQLiteFullException) {
            prepareFailureFor(error)
        } catch (_: Exception) {
            PrepareResult.Failed
        }
        cancelStage(source.id)
        return failure
    }

    /**
     * Removes a STAGED row and both possible artefact files. Never touches a
     * READY row. If either file cannot be removed, retain the row so startup
     * recovery (or an explicit retry) still owns the artefact and can try
     * cleanup again (design §11: failed cleanup is recoverable state).
     */
    private suspend fun cancelStage(sourceId: UUID): Boolean {
        if (!fileOps.deleteArtefacts(paths, sourceId)) return false
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
        var keepSlot = false
        try {
            mutationQueue.withMutation {
                val result = saveLocked(sourceId)
                // P1-18-R5: a failure that kept its stage keeps the slot, so
                // foreground cleanup leaves the stage for the user's retry and
                // no second import starts. Read under the same mutation.
                keepSlot = keepsSlotAfterSave(
                    result,
                    rowStillStaged = sourceDao.findById(sourceId.toString())?.state == SourceState.STAGED.name,
                    stageExists = paths.stageFile(sourceId).exists(),
                )
                result
            }
        } finally {
            if (!keepSlot) importSlot.release(sourceId)
        }
    }

    private suspend fun saveLocked(sourceId: UUID): SaveResult {
        val entity = sourceDao.findById(sourceId.toString())
        if (entity == null || entity.state != SourceState.STAGED.name) return SaveResult.StageNotFound
        val source = entity.toDomain()

        val stageFile = paths.stageFile(sourceId)
        if (!stageFile.exists()) return SaveResult.StageNotFound

        if (!authenticator.authenticates(source, stageFile)) return SaveResult.Failed

        when (val duplicate = duplicateVerifier.check(source, stageFile)) {
            DuplicateCheck.None -> Unit

            is DuplicateCheck.Found -> {
                cancelStage(sourceId)
                return SaveResult.DuplicateFound(duplicate.existingId)
            }

            DuplicateCheck.Unverifiable -> return SaveResult.Failed
        }

        try {
            val blobFile = paths.blobFile(sourceId)
            if (!fileOps.rename(stageFile, blobFile)) return SaveResult.Failed
            fileOps.syncDirectory(paths.artefactsDir)
        } catch (error: IOException) {
            return saveFailureFor(error)
        } catch (error: ErrnoException) {
            return saveFailureFor(error)
        }

        return try {
            // Compare-and-set (P1-18-R13). Losing it cannot happen under the
            // mutation; if it did, the row was not saved by this call.
            when (sourceDao.compareAndSetState(sourceId.toString(), SourceState.STAGED, SourceState.READY)) {
                StateTransition.APPLIED -> SaveResult.Saved(sourceId)
                StateTransition.LOST_RACE -> SaveResult.Failed
            }
        } catch (cancelled: CancellationException) {
            // P1-18-R9: cancellation reaches the caller; never reported as a
            // failure for an item that may already be saved.
            throw cancelled
        } catch (error: IOException) {
            saveFailureFor(error)
        } catch (error: ErrnoException) {
            saveFailureFor(error)
        } catch (_: SQLiteFullException) {
            // The rename already happened, but the row remains STAGED when
            // the final commit is rejected; recovery owns both paths.
            SaveResult.StorageUnavailable
        } catch (_: Exception) {
            // The rename already happened, but the row remains STAGED when
            // the final commit is rejected (for example by the READY
            // invariant trigger). Recovery owns both possible artefact paths
            // and will remove them on the next pass; never surface success.
            SaveResult.Failed
        }
    }

    private fun saveFailureFor(error: Throwable): SaveResult =
        if (IoFailureClassifier.classify(error) == IoFailureClassifier.Kind.STORAGE_UNAVAILABLE) {
            SaveResult.StorageUnavailable
        } else {
            SaveResult.Failed
        }
}

/** Null when Keystore cannot wrap, including a lost wrapping key, which is never recreated here (P1-14-R2). */
private fun KeystoreWrapper.wrapSourceKeyOrNull(dek: ByteArray, sourceId: UUID) = try {
    wrap(dek, EnvelopeDomain.SOURCE_KEY, sourceId)
} catch (_: MissingWrappingKeyException) {
    null
} catch (_: GeneralSecurityException) {
    null
} catch (_: ProviderException) {
    null
}
