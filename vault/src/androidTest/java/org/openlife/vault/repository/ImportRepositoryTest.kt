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
        assertEquals(SourceState.STAGED, row.state) // not READY until an explicit Save (Stage 4)
        assertEquals(expectedDigest.toList(), row.sha256!!.toList())
        assertTrue(paths.stageFile(prepared.sourceId).exists())
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
}
