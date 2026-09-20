package org.openlife.vault.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.SourceState
import org.openlife.vault.model.Orientation
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.OpenLifeDatabaseFactory
import org.openlife.vault.storage.VaultBootstrapper
import org.openlife.vault.storage.VaultBootstrapResult
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.toDomain

/**
 * Instrumented because this exercises the real Keystore, the real
 * SQLCipher-backed database, and real file I/O together - covers C0-02
 * (import succeeds and STAGED metadata matches), C0-06 (rejections clean up
 * fully), and the file/row side of C0-04/C0-05's "no unintended save"
 * requirement.
 */
@RunWith(AndroidJUnit4::class)
class ImportRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var alias: String
    private lateinit var paths: VaultPaths
    private lateinit var db: OpenLifeDatabase
    private lateinit var wrapper: KeystoreWrapper
    private lateinit var repository: ImportRepository

    private fun syntheticJpegBytes(width: Int = 64, height: Int = 48): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun syntheticPngBytes(width: Int = 64, height: Int = 48): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun be32(value: Long): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )

    /** Minimal synthetic PNG header for rejection-path tests only. */
    private fun syntheticPng(width: Long, height: Long, animated: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(be32(13))
        out.write("IHDR".toByteArray(Charsets.US_ASCII))
        out.write(be32(width))
        out.write(be32(height))
        out.write(byteArrayOf(8, 6, 0, 0, 0))
        out.write(be32(0)) // synthetic CRC; parser does not decode it
        if (animated) {
            out.write(be32(8))
            out.write("acTL".toByteArray(Charsets.US_ASCII))
            out.write(be32(1))
            out.write(be32(0))
            out.write(be32(0))
        }
        out.write(be32(0))
        out.write("IDAT".toByteArray(Charsets.US_ASCII))
        out.write(be32(0))
        out.write(be32(0))
        out.write("IEND".toByteArray(Charsets.US_ASCII))
        out.write(be32(0))
        return out.toByteArray()
    }

    @Before
    fun setUp() {
        alias = "test.${UUID.randomUUID()}"
        paths = VaultPaths(context)
        paths.vaultDir.deleteRecursively()
        wrapper = KeystoreWrapper(alias)
        val ready = VaultBootstrapper.bootstrap(paths, wrapper) as VaultBootstrapResult.Ready
        db = OpenLifeDatabaseFactory.create(context, paths, ready.databaseSecret)
        repository = ImportRepository(
            paths = paths,
            database = db,
            keystoreWrapper = wrapper,
            bitmapSampler = { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null },
            mutationQueue = MutationQueue(),
        )
    }

    private fun repositoryWithFileOps(fileOps: ArtefactFileOps): ImportRepository = ImportRepository(
        paths = paths,
        database = db,
        keystoreWrapper = wrapper,
        bitmapSampler = { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null },
        mutationQueue = MutationQueue(),
        fileOps = fileOps,
    )

    @After
    fun tearDown() {
        db.close()
        paths.vaultDir.deleteRecursively()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    @Test
    fun validJpegIsPreparedAndStagedRowMatchesTheOriginal(): Unit = runBlocking {
        val original = syntheticJpegBytes()
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(original)

        val result = repository.prepareImport(ByteArrayInputStream(original), "image/jpeg", IntakeKind.SHARE)

        assertTrue(result is PrepareResult.Prepared)
        val prepared = result as PrepareResult.Prepared
        assertEquals(ImageFormat.JPEG, prepared.format)
        assertEquals(original.size.toLong(), prepared.byteCount)

        val row = db.sourceDao().findById(prepared.sourceId.toString())!!.toDomain()
        assertEquals(SourceState.STAGED, row.state) // not READY until an explicit Save
        assertEquals(expectedDigest.toList(), row.sha256!!.toList())
        assertTrue(paths.stageFile(prepared.sourceId).exists())
    }

    @Test
    fun exifOrientationIsPersistedWithoutChangingBytesOrDigest(): Unit = runBlocking {
        val original = ExifFixtures.injectOrientation(syntheticJpegBytes(80, 40), 6)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(original)

        val prepared = repository.prepareImport(
            ByteArrayInputStream(original),
            "image/jpeg",
            IntakeKind.SHARE,
        ) as PrepareResult.Prepared

        val row = db.sourceDao().findById(prepared.sourceId.toString())!!.toDomain()
        assertEquals(Orientation.ROTATE_90, row.orientation)
        assertEquals(expectedDigest.toList(), row.sha256!!.toList())
        assertEquals(original.size.toLong(), row.byteCount)
    }

    @Test
    fun validPngIsPreparedAndStagedRowMatchesTheOriginal(): Unit = runBlocking {
        val original = syntheticPngBytes()
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(original)

        val result = repository.prepareImport(
            ByteArrayInputStream(original), "image/png", IntakeKind.PHOTO_PICKER
        )

        assertTrue(result is PrepareResult.Prepared)
        val prepared = result as PrepareResult.Prepared
        assertEquals(ImageFormat.PNG, prepared.format)
        assertEquals(original.size.toLong(), prepared.byteCount)
        val row = db.sourceDao().findById(prepared.sourceId.toString())!!.toDomain()
        assertEquals(SourceState.STAGED, row.state)
        assertEquals(expectedDigest.toList(), row.sha256!!.toList())
        assertTrue(paths.stageFile(prepared.sourceId).exists())
    }

    @Test
    fun plaintextBufferIsZeroedAfterPrepare(): Unit = runBlocking {
        var releasedBuffer: ByteArray? = null
        val observingRepository = ImportRepository(
            paths = paths,
            database = db,
            keystoreWrapper = wrapper,
            bitmapSampler = { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null },
            mutationQueue = MutationQueue(),
            plaintextBufferObserver = PlaintextBufferObserver { releasedBuffer = it },
        )

        val result = observingRepository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()),
            "image/jpeg",
            IntakeKind.SHARE,
        )

        assertTrue(result is PrepareResult.Prepared)
        assertTrue("the test seam must expose the released plaintext buffer", releasedBuffer != null)
        assertTrue("plaintext buffer must be zeroed before release", releasedBuffer!!.all { it == 0.toByte() })
    }

    @Test
    fun stageFileDecryptsBackToTheExactOriginalBytes(): Unit = runBlocking {
        val original = syntheticJpegBytes()
        val prepared = repository.prepareImport(
            ByteArrayInputStream(original), "image/jpeg", IntakeKind.SHARE
        ) as PrepareResult.Prepared

        val row = db.sourceDao().findById(prepared.sourceId.toString())!!.toDomain()
        val wrapper = KeystoreWrapper(alias)
        val dek = wrapper.unwrap(
            EnvelopeCodec.decode(row.wrappedDek!!),
            org.openlife.vault.crypto.EnvelopeDomain.SOURCE_KEY,
            prepared.sourceId
        )
        val artefactEnvelope = EnvelopeCodec.decode(paths.stageFile(prepared.sourceId).readBytes())
        val decrypted = org.openlife.vault.crypto.AesGcmCodec.decrypt(
            artefactEnvelope,
            javax.crypto.spec.SecretKeySpec(dek, "AES"),
            org.openlife.vault.crypto.EnvelopeAad.forSource(
                org.openlife.vault.crypto.EnvelopeDomain.ARTEFACT,
                prepared.sourceId
            )
        )
        assertEquals(original.toList(), decrypted.toList())
    }

    @Test
    fun rejectedImportLeavesNoStagedRowOrFile(): Unit = runBlocking {
        val garbage = "not an image".toByteArray()
        val before = db.sourceDao().count()

        val result = repository.prepareImport(ByteArrayInputStream(garbage), "image/png", IntakeKind.SHARE)

        assertTrue(result is PrepareResult.Rejected)
        assertEquals(before, db.sourceDao().count())
        assertTrue(paths.artefactsDir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun forgedMimeTypeMismatchIsRejectedAndCleanedUp(): Unit = runBlocking {
        // Real JPEG bytes, declared as PNG - a forged MIME type.
        val result = repository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/png", IntakeKind.PHOTO_PICKER
        )
        assertEquals(PrepareResult.Rejected(ImageRejectionReason.DECLARED_FORMAT_MISMATCH), result)
        assertEquals(0, db.sourceDao().count())
    }

    @Test
    fun providerReadFailureReturnsFailedAndCleansStagedRow(): Unit = runBlocking {
        val failingStream = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("synthetic provider failure")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw java.io.IOException("synthetic provider failure")
        }

        val result = repository.prepareImport(failingStream, "image/jpeg", IntakeKind.SHARE)

        assertEquals(PrepareResult.Failed, result)
        assertEquals(0, db.sourceDao().count())
        assertTrue(paths.artefactsDir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun stageWriteFailureReturnsFailedAndCleansStagedRow(): Unit = runBlocking {
        val writeFailureRepository = repositoryWithFileOps(object : ArtefactFileOps {
            override fun writeAndSync(file: java.io.File, bytes: ByteArray) {
                throw java.io.IOException("synthetic stage write failure")
            }

            override fun rename(stage: java.io.File, blob: java.io.File): Boolean =
                stage.renameTo(blob)

            override fun syncDirectory(directory: java.io.File) = Unit

            override fun deleteIfExists(file: java.io.File): Boolean =
                ArtefactFileOps.Default.deleteIfExists(file)
        })

        val result = writeFailureRepository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE
        )

        assertEquals(PrepareResult.Failed, result)
        assertEquals(0, db.sourceDao().count())
        assertTrue(paths.artefactsDir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun animatedPngIsRejectedAndLeavesNoStagedState(): Unit = runBlocking {
        val result = repository.prepareImport(
            ByteArrayInputStream(syntheticPng(50, 50, animated = true)),
            "image/png",
            IntakeKind.SHARE,
        )

        assertEquals(
            PrepareResult.Rejected(ImageRejectionReason.ANIMATED_NOT_SUPPORTED),
            result,
        )
        assertEquals(0, db.sourceDao().count())
        assertTrue(paths.artefactsDir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun corruptPngIsRejectedAndLeavesNoStagedState(): Unit = runBlocking {
        val corrupt = syntheticPng(50, 50).also {
            "IHDX".toByteArray(Charsets.US_ASCII).copyInto(it, 12)
        }

        val result = repository.prepareImport(
            ByteArrayInputStream(corrupt),
            "image/png",
            IntakeKind.PHOTO_PICKER,
        )

        assertEquals(PrepareResult.Rejected(ImageRejectionReason.CORRUPT_CONTENT), result)
        assertEquals(0, db.sourceDao().count())
        assertTrue(paths.artefactsDir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun excessBytesAreRejectedAndLeaveNoStagedState(): Unit = runBlocking {
        val prefix = syntheticPngBytes(10, 10)
        val excess = object : java.io.InputStream() {
            private var prefixOffset = 0
            private var remaining = ImportLimits.MAX_ORIGINAL_BYTES + 1 - prefix.size

            override fun read(): Int {
                if (prefixOffset < prefix.size) return prefix[prefixOffset++].toInt() and 0xFF
                if (remaining == 0L) return -1
                remaining--
                return 0
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (prefixOffset < prefix.size) {
                    val count = minOf(length, prefix.size - prefixOffset)
                    prefix.copyInto(buffer, offset, prefixOffset, prefixOffset + count)
                    prefixOffset += count
                    return count
                }
                if (remaining == 0L) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                java.util.Arrays.fill(buffer, offset, offset + count, 0)
                remaining -= count
                return count
            }
        }

        val result = repository.prepareImport(excess, "image/png", IntakeKind.SHARE)

        assertEquals(PrepareResult.Rejected(ImageRejectionReason.EXCEEDS_BYTE_LIMIT), result)
        assertEquals(0, db.sourceDao().count())
        assertTrue(paths.artefactsDir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun extremePngDimensionsAreRejectedAndLeavesNoStagedState(): Unit = runBlocking {
        val result = repository.prepareImport(
            ByteArrayInputStream(
                syntheticPng(ImportLimits.MAX_LONGEST_EDGE_PIXELS.toLong() + 1, 10),
            ),
            "image/png",
            IntakeKind.SHARE,
        )

        assertEquals(
            PrepareResult.Rejected(ImageRejectionReason.EXCEEDS_DIMENSION_LIMIT),
            result,
        )
        assertEquals(0, db.sourceDao().count())
        assertTrue(paths.artefactsDir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun cancellingAStagedImportRetainsTheRowWhenStageCleanupFails(): Unit = runBlocking {
        val prepared = repository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE
        ) as PrepareResult.Prepared

        // A non-empty directory at the stage path makes File.delete() fail,
        // simulating a provider/filesystem cleanup failure without changing
        // production storage code or relying on permissions.
        val stage = paths.stageFile(prepared.sourceId)
        assertTrue(stage.delete())
        assertTrue(stage.mkdir())
        val blocker = java.io.File(stage, "occupied")
        blocker.writeText("x")
        assertTrue(blocker.exists())

        assertEquals(false, repository.cancelStagedImport(prepared.sourceId))
        assertEquals(
            SourceState.STAGED,
            db.sourceDao().findById(prepared.sourceId.toString())!!.toDomain().state,
        )

        // Once the blocker is removed, the same durable row can be retried
        // and converges to the normal cancelled state.
        assertTrue(blocker.delete())
        assertTrue(stage.delete())
        assertEquals(true, repository.cancelStagedImport(prepared.sourceId))
        assertEquals(null, db.sourceDao().findById(prepared.sourceId.toString()))
    }

    @Test
    fun secondImportWhileFirstIsInProgressIsToldBusy(): Unit = runBlocking {
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blockingStream = object : java.io.InputStream() {
            var signalled = false
            override fun read(): Int {
                if (!signalled) {
                    signalled = true
                    holding.complete(Unit)
                    runBlocking { release.await() }
                }
                return -1
            }
        }

        val firstImport = async {
            repository.prepareImport(blockingStream, "image/jpeg", IntakeKind.SHARE)
        }
        holding.await()

        val second = repository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE
        )
        assertEquals(PrepareResult.Busy, second)

        release.complete(Unit)
        firstImport.await()
    }

    // --- Save (design §11 "Save") ---

    @Test
    fun saveTransitionsStagedToReadyAndRenamesStageToBlob(): Unit = runBlocking {
        val original = syntheticJpegBytes()
        val prepared = repository.prepareImport(
            ByteArrayInputStream(original), "image/jpeg", IntakeKind.SHARE
        ) as PrepareResult.Prepared

        val result = repository.saveImport(prepared.sourceId)

        assertEquals(SaveResult.Saved(prepared.sourceId), result)
        val row = db.sourceDao().findById(prepared.sourceId.toString())!!.toDomain()
        assertEquals(SourceState.READY, row.state)
        assertTrue(!paths.stageFile(prepared.sourceId).exists())
        assertTrue(paths.blobFile(prepared.sourceId).exists())
    }

    @Test
    fun savedBlobAuthenticatesAndReproducesTheExactOriginalBytes(): Unit = runBlocking {
        val original = syntheticJpegBytes()
        val prepared = repository.prepareImport(
            ByteArrayInputStream(original), "image/jpeg", IntakeKind.SHARE
        ) as PrepareResult.Prepared
        repository.saveImport(prepared.sourceId)

        val row = db.sourceDao().findById(prepared.sourceId.toString())!!.toDomain()
        val dek = wrapper.unwrap(
            EnvelopeCodec.decode(row.wrappedDek!!),
            org.openlife.vault.crypto.EnvelopeDomain.SOURCE_KEY,
            prepared.sourceId
        )
        val envelope = EnvelopeCodec.decode(paths.blobFile(prepared.sourceId).readBytes())
        val decrypted = org.openlife.vault.crypto.AesGcmCodec.decrypt(
            envelope,
            javax.crypto.spec.SecretKeySpec(dek, "AES"),
            org.openlife.vault.crypto.EnvelopeAad.forSource(
                org.openlife.vault.crypto.EnvelopeDomain.ARTEFACT,
                prepared.sourceId
            )
        )
        assertEquals(original.toList(), decrypted.toList())
    }

    @Test
    fun savingAnExactDuplicateDiscardsTheNewStageAndPreservesTheExisting(): Unit = runBlocking {
        val original = syntheticJpegBytes()
        val first = repository.prepareImport(
            ByteArrayInputStream(original), "image/jpeg", IntakeKind.SHARE
        ) as PrepareResult.Prepared
        repository.saveImport(first.sourceId)

        val second = repository.prepareImport(
            ByteArrayInputStream(original), "image/jpeg", IntakeKind.PHOTO_PICKER
        ) as PrepareResult.Prepared

        val result = repository.saveImport(second.sourceId)

        assertEquals(SaveResult.DuplicateFound(first.sourceId), result)
        // The existing original is untouched...
        assertTrue(paths.blobFile(first.sourceId).exists())
        assertEquals(SourceState.READY, db.sourceDao().findById(first.sourceId.toString())!!.toDomain().state)
        // ...and the new staging is fully discarded, not left half-saved.
        assertEquals(null, db.sourceDao().findById(second.sourceId.toString()))
        assertTrue(!paths.stageFile(second.sourceId).exists())
        assertTrue(!paths.blobFile(second.sourceId).exists())
    }

    @Test
    fun savingAnUnknownSourceIdReturnsStageNotFound(): Unit = runBlocking {
        val result = repository.saveImport(UUID.randomUUID())
        assertEquals(SaveResult.StageNotFound, result)
    }

    @Test
    fun savingATamperedStageFailsAndLeavesTheRowStaged(): Unit = runBlocking {
        val prepared = repository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE
        ) as PrepareResult.Prepared
        val stageFile = paths.stageFile(prepared.sourceId)
        val bytes = stageFile.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        stageFile.writeBytes(bytes)

        val result = repository.saveImport(prepared.sourceId)

        assertEquals(SaveResult.Failed, result)
        assertEquals(
            SourceState.STAGED,
            db.sourceDao().findById(prepared.sourceId.toString())!!.toDomain().state
        )
    }

    @Test
    fun renameFailureReturnsFailedAndLeavesTheStageForRecovery(): Unit = runBlocking {
        val prepared = repository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE
        ) as PrepareResult.Prepared

        // A directory at the final blob path makes the platform rename fail
        // without changing permissions or filling the test device.
        val blob = paths.blobFile(prepared.sourceId)
        assertTrue(blob.mkdir())
        assertEquals(SaveResult.Failed, repository.saveImport(prepared.sourceId))
        assertEquals(
            SourceState.STAGED,
            db.sourceDao().findById(prepared.sourceId.toString())!!.toDomain().state,
        )
        assertTrue(paths.stageFile(prepared.sourceId).exists())

        // Recovery ownership remains usable after the transient destination
        // conflict is removed.
        assertTrue(blob.delete())
        assertTrue(repository.cancelStagedImport(prepared.sourceId))
        assertEquals(null, db.sourceDao().findById(prepared.sourceId.toString()))
        assertTrue(!paths.stageFile(prepared.sourceId).exists())
    }

    @Test
    fun directorySyncFailureReturnsFailedAndLeavesRenamedArtefactRecoverable(): Unit = runBlocking {
        val prepared = repository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE
        ) as PrepareResult.Prepared
        val syncFailureRepository = repositoryWithFileOps(object : ArtefactFileOps {
            override fun writeAndSync(file: java.io.File, bytes: ByteArray) =
                ArtefactFileOps.Default.writeAndSync(file, bytes)

            override fun rename(stage: java.io.File, blob: java.io.File): Boolean =
                ArtefactFileOps.Default.rename(stage, blob)

            override fun syncDirectory(directory: java.io.File) {
                throw java.io.IOException("synthetic directory sync failure")
            }

            override fun deleteIfExists(file: java.io.File): Boolean =
                ArtefactFileOps.Default.deleteIfExists(file)
        })

        assertEquals(SaveResult.Failed, syncFailureRepository.saveImport(prepared.sourceId))
        assertEquals(
            SourceState.STAGED,
            db.sourceDao().findById(prepared.sourceId.toString())!!.toDomain().state,
        )
        assertTrue(!paths.stageFile(prepared.sourceId).exists())
        assertTrue(paths.blobFile(prepared.sourceId).exists())
    }

    @Test
    fun saveCommitFailureReturnsFailedAndLeavesRenamedArtefactRecoverable(): Unit = runBlocking {
        val prepared = repository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE
        ) as PrepareResult.Prepared

        // Force the READY invariant trigger to reject the final database
        // commit after the stage has already been renamed. This models a
        // commit failure without replacing the real SQLCipher/Room stack.
        val staged = db.sourceDao().findById(prepared.sourceId.toString())!!
        db.sourceDao().update(staged.copy(mimeType = null))

        assertEquals(SaveResult.Failed, repository.saveImport(prepared.sourceId))
        assertEquals(
            SourceState.STAGED,
            db.sourceDao().findById(prepared.sourceId.toString())!!.toDomain().state,
        )
        assertTrue(!paths.stageFile(prepared.sourceId).exists())
        assertTrue(paths.blobFile(prepared.sourceId).exists())
    }
}
