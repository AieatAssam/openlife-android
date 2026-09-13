package org.openlife.vault.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
 * Instrumented coverage of design §11's startup recovery table
 * (docs/capabilities/C0.md C0-R22/C0-R23), covering the C0-09 row's
 * "no partial READY, orphaned plaintext, or resurrection" requirement for
 * every state a Source row can be interrupted in.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryRepositoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var alias: String
    private lateinit var paths: VaultPaths
    private lateinit var wrapper: KeystoreWrapper
    private lateinit var db: OpenLifeDatabase
    private lateinit var importRepository: ImportRepository
    private lateinit var recoveryRepository: RecoveryRepository

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
        val mutationQueue = MutationQueue()
        importRepository = ImportRepository(
            paths = paths,
            database = db,
            keystoreWrapper = wrapper,
            bitmapSampler = { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null },
            mutationQueue = mutationQueue,
        )
        recoveryRepository = RecoveryRepository(paths, db, wrapper, mutationQueue)
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

    private suspend fun prepareOnly(): UUID {
        val prepared = importRepository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE
        ) as PrepareResult.Prepared
        return prepared.sourceId
    }

    private suspend fun prepareAndSave(): UUID {
        val id = prepareOnly()
        importRepository.saveImport(id)
        return id
    }

    @Test
    fun interruptedStagedImportIsRemovedByRecovery(): Unit = runBlocking {
        val id = prepareOnly() // never saved - simulates a kill before Save
        assertTrue(paths.stageFile(id).exists())

        val report = recoveryRepository.recover()

        assertEquals(1, report.cleanedStaged)
        assertEquals(null, db.sourceDao().findById(id.toString()))
        assertTrue(!paths.stageFile(id).exists())
    }

    @Test
    fun validReadySourceIsConfirmedAndUntouched(): Unit = runBlocking {
        val id = prepareAndSave()

        val report = recoveryRepository.recover()

        assertEquals(1, report.confirmedReady)
        assertEquals(SourceState.READY, db.sourceDao().findById(id.toString())!!.toDomain().state)
        assertTrue(paths.blobFile(id).exists())
    }

    @Test
    fun readySourceWithMissingBlobIsMarkedCorruptNotDeleted(): Unit = runBlocking {
        val id = prepareAndSave()
        paths.blobFile(id).delete()

        val report = recoveryRepository.recover()

        assertEquals(1, report.markedCorrupt)
        val row = db.sourceDao().findById(id.toString())
        assertTrue(row != null) // retained, not deleted
        assertEquals(SourceState.CORRUPT, row!!.toDomain().state)
    }

    @Test
    fun readySourceWithTamperedBlobIsMarkedCorrupt(): Unit = runBlocking {
        val id = prepareAndSave()
        val blob = paths.blobFile(id)
        val bytes = blob.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        blob.writeBytes(bytes)

        recoveryRepository.recover()

        assertEquals(SourceState.CORRUPT, db.sourceDao().findById(id.toString())!!.toDomain().state)
        // The tampered blob is retained for diagnosis, not deleted.
        assertTrue(blob.exists())
    }

    @Test
    fun corruptRowIsRetainedAcrossRepeatedRecovery(): Unit = runBlocking {
        val id = prepareAndSave()
        paths.blobFile(id).delete()
        recoveryRepository.recover() // READY -> CORRUPT

        val secondReport = recoveryRepository.recover()

        assertEquals(0, secondReport.markedCorrupt) // already CORRUPT, not re-processed as READY
        assertEquals(SourceState.CORRUPT, db.sourceDao().findById(id.toString())!!.toDomain().state)
    }

    @Test
    fun deletingRowWithRemainingFilesIsResumedAndRemoved(): Unit = runBlocking {
        // Simulates a kill mid-deletion (Stage 5 sets this state; the
        // recovery branch must already handle it correctly regardless).
        val id = prepareAndSave()
        val entity = db.sourceDao().findById(id.toString())!!
        db.sourceDao().update(entity.toDomain().copy(state = SourceState.DELETING).toEntity())
        assertTrue(paths.blobFile(id).exists())

        val report = recoveryRepository.recover()

        assertEquals(1, report.resumedDeletions)
        assertEquals(null, db.sourceDao().findById(id.toString()))
        assertTrue(!paths.blobFile(id).exists())
    }

    @Test
    fun unreferencedArtefactFileIsRemovedOnlyAfterFullReconciliation(): Unit = runBlocking {
        paths.ensureDirectoriesExist()
        val orphanId = UUID.randomUUID()
        paths.blobFile(orphanId).writeBytes(byteArrayOf(1, 2, 3))
        // A real, valid row must still be confirmed correctly in the same pass.
        val validId = prepareAndSave()

        val report = recoveryRepository.recover()

        assertEquals(1, report.removedOrphanFiles)
        assertTrue(!paths.blobFile(orphanId).exists())
        assertEquals(SourceState.READY, db.sourceDao().findById(validId.toString())!!.toDomain().state)
    }

    @Test
    fun recoveryIsIdempotentAcrossAMixOfStates(): Unit = runBlocking {
        prepareOnly() // STAGED, to be cleaned
        prepareAndSave() // READY, to be confirmed
        val corruptId = prepareAndSave()
        paths.blobFile(corruptId).delete()

        recoveryRepository.recover()
        val secondPass = recoveryRepository.recover()

        // A second, immediate re-run finds nothing further to reconcile.
        assertEquals(RecoveryReport(confirmedReady = 1, markedCorrupt = 0), secondPass)
    }
}
