package org.openlife.vault.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.OpenLifeDatabaseFactory
import org.openlife.vault.storage.VaultBootstrapResult
import org.openlife.vault.storage.VaultBootstrapper
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.toDomain

/**
 * C0-11's end-to-end leg: `AesGcmCodecTest`/`EnvelopeCodecTest` (`:vault:test`)
 * already prove the codec itself fails closed on every kind of tamper in
 * isolation. This proves the same holds through the real path a viewer
 * actually uses - a real `ImportRepository.saveImport`, a real on-disk
 * `.blob` file under `noBackupFilesDir`, a real SQLCipher row, and
 * `SourceViewRepository`/`ArtefactAuthenticator` reading it back - by
 * flipping bytes in the actual saved file (and the actual stored wrapped
 * key) rather than constructing a tampered envelope directly.
 */
@RunWith(AndroidJUnit4::class)
class EnvelopeTamperingThroughRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var alias: String
    private lateinit var paths: VaultPaths
    private lateinit var db: OpenLifeDatabase
    private lateinit var wrapper: KeystoreWrapper
    private lateinit var importRepository: ImportRepository
    private lateinit var viewRepository: SourceViewRepository
    private lateinit var mutationQueue: MutationQueue

    private fun syntheticJpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
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
        mutationQueue = MutationQueue()
        importRepository = ImportRepository(
            paths = paths,
            database = db,
            keystoreWrapper = wrapper,
            bitmapSampler = { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null },
            mutationQueue = mutationQueue,
        )
        viewRepository = SourceViewRepository(paths, db, wrapper, mutationQueue)
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

    private suspend fun saveASource(): UUID {
        val original = syntheticJpegBytes()
        val prepared = importRepository.prepareImport(
            ByteArrayInputStream(original), "image/jpeg", IntakeKind.SHARE
        ) as PrepareResult.Prepared
        val saved = importRepository.saveImport(prepared.sourceId)
        assertTrue(saved is SaveResult.Saved)
        return prepared.sourceId
    }

    @Test
    fun flippingAByteInTheOnDiskBlobFailsClosedThroughTheViewer(): Unit = runBlocking {
        val sourceId = saveASource()

        // Sanity: the untampered path actually returns plaintext, so the
        // failure below is caused by the tamper, not a broken setup.
        assertTrue(viewRepository.loadReadyBytes(sourceId) != null)

        val blobFile = paths.blobFile(sourceId)
        val bytes = blobFile.readBytes()
        // Flip a byte well past the fixed header (magic/version/nonce), in
        // the ciphertext region, so this is genuinely tampered ciphertext -
        // not an accidentally-truncated or malformed envelope.
        val tamperIndex = bytes.size - 5
        bytes[tamperIndex] = (bytes[tamperIndex].toInt() xor 0xFF).toByte()
        blobFile.writeBytes(bytes)

        assertNull(
            "a tampered on-disk blob must never authenticate, even through the real viewer path",
            viewRepository.loadReadyBytes(sourceId)
        )
    }

    @Test
    fun flippingTheStoredWrappedKeyFailsClosedThroughTheViewer(): Unit = runBlocking {
        val sourceId = saveASource()
        assertTrue(viewRepository.loadReadyBytes(sourceId) != null)

        val entity = db.sourceDao().findById(sourceId.toString())!!
        val tamperedWrappedDek = entity.wrappedDek!!.copyOf()
        val tamperIndex = tamperedWrappedDek.size - 3
        tamperedWrappedDek[tamperIndex] = (tamperedWrappedDek[tamperIndex].toInt() xor 0xFF).toByte()
        db.sourceDao().update(entity.copy(wrappedDek = tamperedWrappedDek))

        assertNull(
            "a tampered wrapped DEK must never unwrap to a usable key, even through the real viewer path",
            viewRepository.loadReadyBytes(sourceId)
        )
    }

    @Test
    fun anUntamperedSourceStillRoundTripsAfterTheAboveChecks(): Unit = runBlocking {
        // Guards against a trivially-broken loadReadyBytes always returning
        // null (which would make the two tests above pass for the wrong
        // reason).
        val sourceId = saveASource()
        val original = viewRepository.loadReadyBytes(sourceId)
        assertTrue(original != null && original.isNotEmpty())
        val row = db.sourceDao().findById(sourceId.toString())!!.toDomain()
        assertEquals(sourceId, row.id)
    }

    @Test
    fun viewerReadSharesTheMutationQueueWithDeletionWork(): Unit = runBlocking {
        val sourceId = saveASource()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val mutation = async {
            mutationQueue.withMutation {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val read = async { viewRepository.loadReadyBytes(sourceId) }
        kotlinx.coroutines.delay(50)
        assertTrue("viewer read must wait for an in-flight deletion/mutation", !read.isCompleted)

        release.complete(Unit)
        mutation.await()
        assertTrue(read.await() != null)
    }

    @Test
    fun deletionWaitsForAnActiveViewerRead(): Unit = runBlocking {
        val sourceId = saveASource()
        val deletion = DeletionRepository(paths, db, mutationQueue)
        val readerIn = CompletableDeferred<Unit>()
        val releaseReader = CompletableDeferred<Unit>()
        val reader = async(kotlinx.coroutines.Dispatchers.Default) {
            mutationQueue.withReadLease { readerIn.complete(Unit); releaseReader.await() }
        }
        readerIn.await()

        val delete = async(kotlinx.coroutines.Dispatchers.Default) { deletion.deleteSource(sourceId) }
        kotlinx.coroutines.delay(200)
        assertTrue("deletion must not overlap an in-flight plaintext read", !delete.isCompleted)
        assertTrue("the blob is still there while the read holds its lease", paths.blobFile(sourceId).exists())

        releaseReader.complete(Unit)
        reader.await()
        assertEquals(DeleteResult.Deleted, delete.await())
    }
}
