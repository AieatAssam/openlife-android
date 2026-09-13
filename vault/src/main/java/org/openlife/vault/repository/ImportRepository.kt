package org.openlife.vault.repository

import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import org.openlife.vault.crypto.AesGcmCodec
import org.openlife.vault.crypto.EnvelopeAad
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.EnvelopeDomain
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.storage.Fsync
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.toEntity

private const val DEK_LENGTH_BYTES = 32
private const val SCHEMA_ARTEFACT_VERSION = 1

/**
 * The "Prepare and preview" half of design §11. Save (STAGED -> READY) and
 * startup recovery are Stage 4. Duplicate detection is deliberately not
 * here: design §11 places it in the Save step ("recheck the stage and
 * duplicate policy under the mutation queue"), not at prepare time.
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
) {

    suspend fun prepareImport(
        inputStream: InputStream,
        declaredMimeType: String,
        intakeKind: IntakeKind,
    ): PrepareResult =
        mutationQueue.tryAcquire { prepareImportLocked(inputStream, declaredMimeType, intakeKind) }
            ?: PrepareResult.Busy

    private suspend fun prepareImportLocked(
        inputStream: InputStream,
        declaredMimeType: String,
        intakeKind: IntakeKind,
    ): PrepareResult {
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
            database.sourceDao().insert(stagedSource.toEntity())

            val read = try {
                BoundedStreamReader.read(inputStream)
            } catch (e: IOException) {
                cancelStage(sourceId)
                return PrepareResult.Failed
            }

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
                    EnvelopeAad.forSource(EnvelopeDomain.ARTEFACT, sourceId)
                )
                Fsync.writeAndSync(paths.stageFile(sourceId), EnvelopeCodec.encode(artefactEnvelope))
            } catch (e: IOException) {
                cancelStage(sourceId)
                return PrepareResult.Failed
            }

            val updated = stagedSource.copy(
                mimeType = valid.format,
                byteCount = read.bytes.size.toLong(),
                sha256 = read.sha256,
                width = valid.width,
                height = valid.height,
                // EXIF-derived orientation is out of scope for C0's header
                // validator; every accepted Source is recorded as already
                // display-correct until a later capability reads EXIF.
                orientation = Orientation.NORMAL,
                artefactVersion = SCHEMA_ARTEFACT_VERSION,
            )
            database.sourceDao().update(updated.toEntity())

            return PrepareResult.Prepared(sourceId, valid.format, valid.width, valid.height, read.bytes.size.toLong())
        } finally {
            dek.fill(0)
        }
    }

    /** Removes a STAGED row and its possible stage file. Never touches a READY row. */
    private suspend fun cancelStage(sourceId: UUID) {
        paths.stageFile(sourceId).delete()
        database.sourceDao().deleteById(sourceId.toString())
    }
}
