package org.openlife.vault.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.SourceState
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.OpenLifeDatabaseFactory
import org.openlife.vault.storage.VaultBootstrapResult
import org.openlife.vault.storage.VaultBootstrapper
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.toDomain
import org.openlife.vault.storage.toEntity

/**
 * Instrumented coverage of design §12's deletion contract
 * (docs/capabilities/C0.md C0-R24), and the C0-14 test row.
 */
@RunWith(AndroidJUnit4::class)
class DeletionRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var alias: String
    private lateinit var paths: VaultPaths
    private lateinit var db: OpenLifeDatabase
    private lateinit var importRepository: ImportRepository
    private lateinit var deletionRepository: DeletionRepository
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
        val wrapper = KeystoreWrapper(alias)
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
        deletionRepository = DeletionRepository(paths, db, mutationQueue)
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

    private suspend fun prepareAndSave(): UUID {
        val prepared = importRepository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE
        ) as PrepareResult.Prepared
        importRepository.saveImport(prepared.sourceId)
        return prepared.sourceId
    }

    @Test
    fun deletingAReadySourceRemovesTheRowAndBlob(): Unit = runBlocking {
        val id = prepareAndSave()
        assertTrue(paths.blobFile(id).exists())

        val result = deletionRepository.deleteSource(id)

        assertEquals(DeleteResult.Deleted, result)
        assertEquals(null, db.sourceDao().findById(id.toString()))
        assertTrue(!paths.blobFile(id).exists())
    }

    @Test
    fun deletingAnUnknownSourceReturnsNotFound(): Unit = runBlocking {
        val result = deletionRepository.deleteSource(UUID.randomUUID())
        assertEquals(DeleteResult.NotFound, result)
    }

    @Test
    fun deletingAStagedSourceReturnsNotFound(): Unit = runBlocking {
        val prepared = importRepository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE
        ) as PrepareResult.Prepared

        val result = deletionRepository.deleteSource(prepared.sourceId)

        assertEquals(DeleteResult.NotFound, result)
        // A STAGED row is not this operation's concern - it must survive untouched.
        assertTrue(paths.stageFile(prepared.sourceId).exists())
    }

    @Test
    fun deletingACorruptSourceRemovesItToo(): Unit = runBlocking {
        val id = prepareAndSave()
        val entity = db.sourceDao().findById(id.toString())!!
        db.sourceDao().update(entity.toDomain().copy(state = SourceState.CORRUPT).toEntity())

        val result = deletionRepository.deleteSource(id)

        assertEquals(DeleteResult.Deleted, result)
        assertEquals(null, db.sourceDao().findById(id.toString()))
    }

    @Test
    fun retryingAnAlreadyDeletingSourceFinishesTheJob(): Unit = runBlocking {
        // Simulates a prior deletion attempt that committed DELETING and
        // removed the blob, then crashed before removing the row (design
        // §12: "A filesystem failure leaves DELETING durable... for retry").
        val id = prepareAndSave()
        val entity = db.sourceDao().findById(id.toString())!!
        db.sourceDao().update(entity.toDomain().copy(state = SourceState.DELETING).toEntity())
        paths.blobFile(id).delete()

        val result = deletionRepository.deleteSource(id)

        assertEquals(DeleteResult.Deleted, result)
        assertEquals(null, db.sourceDao().findById(id.toString()))
    }

    @Test
    fun deletionNeverReportsSuccessWhileAFileStillExists(): Unit = runBlocking {
        // A file that refuses to delete (simulated by replacing it with a
        // read-only directory of the same name, which File.delete() cannot
        // remove non-empty, and by construction is never treated as
        // "deleted" here) must not be reported as Deleted, and the row must
        // stay DELETING for a future retry rather than disappearing or
        // silently reporting success.
        val id = prepareAndSave()
        val blob = paths.blobFile(id)
        blob.delete()
        blob.mkdir() // a directory can't be removed by File.delete() while non-empty
        java.io.File(blob, "occupied").writeText("x")

        val result = deletionRepository.deleteSource(id)

        assertEquals(DeleteResult.Failed, result)
        val row = db.sourceDao().findById(id.toString())
        assertTrue(row != null)
        assertEquals(SourceState.DELETING, row!!.toDomain().state)

        // Cleanup so tearDown's deleteRecursively can proceed normally.
        java.io.File(blob, "occupied").delete()
        blob.delete()
    }

    @Test
    fun aFailureAfterTheFirstDeleteKeepsDeletingStateForRetry(): Unit = runBlocking {
        val id = prepareAndSave()
        val blob = paths.blobFile(id)
        val fault = FailOnceOnBlobDelete(blob)
        val faultInjectingRepository = DeletionRepository(paths, db, mutationQueue, fault)

        assertEquals(DeleteResult.Failed, faultInjectingRepository.deleteSource(id))
        assertTrue("the blob must remain owned after a failed cleanup", blob.exists())
        assertEquals(SourceState.DELETING, db.sourceDao().findById(id.toString())!!.toDomain().state)

        assertEquals(DeleteResult.Deleted, faultInjectingRepository.deleteSource(id))
        assertEquals(null, db.sourceDao().findById(id.toString()))
        assertTrue(!blob.exists())
    }

    private class FailOnceOnBlobDelete(private val blob: File) : ArtefactFileOps {
        private var failed = false

        override fun writeAndSync(file: File, bytes: ByteArray) =
            ArtefactFileOps.Default.writeAndSync(file, bytes)

        override fun rename(stage: File, blob: File): Boolean =
            ArtefactFileOps.Default.rename(stage, blob)

        override fun syncDirectory(directory: File) =
            ArtefactFileOps.Default.syncDirectory(directory)

        override fun deleteIfExists(file: File): Boolean {
            if (!failed && file == blob) {
                failed = true
                return false
            }
            return ArtefactFileOps.Default.deleteIfExists(file)
        }
    }
}
