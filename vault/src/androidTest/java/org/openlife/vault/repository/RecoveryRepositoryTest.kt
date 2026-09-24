package org.openlife.vault.repository

import kotlinx.coroutines.async
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRevision
import org.openlife.vault.ocr.OcrRevisionState
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
    private lateinit var queueForTest: MutationQueue
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
        queueForTest = mutationQueue
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

    /**
     * Leaves a STAGED import, as a process killed before Save would. A new
     * process starts with a free import slot, so free it here too (P1-15).
     */
    private suspend fun prepareOnly(): UUID {
        val prepared = importRepository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE
        ) as PrepareResult.Prepared
        importRepository.importSlot.release(prepared.sourceId)
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
    fun stagedRowIsRetainedWhenRecoveryCannotRemoveAnArtefact(): Unit = runBlocking {
        val id = prepareOnly()
        val stage = paths.stageFile(id)
        assertTrue(stage.delete())
        assertTrue(stage.mkdir())
        java.io.File(stage, "occupied").writeText("x")

        val report = recoveryRepository.recover()

        assertEquals(0, report.cleanedStaged)
        val row = db.sourceDao().findById(id.toString())
        assertTrue(row != null)
        assertEquals(SourceState.STAGED, row!!.toDomain().state)
        assertTrue(stage.exists())

        // The failed cleanup is recoverable: once the blocker is removed, a
        // later recovery pass can finish the abandoned import.
        assertTrue(java.io.File(stage, "occupied").delete())
        assertTrue(stage.delete())
        val retryReport = recoveryRepository.recover()
        assertEquals(1, retryReport.cleanedStaged)
        assertEquals(null, db.sourceDao().findById(id.toString()))
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
    fun runningOcrRevisionIsMarkedStaleOnRecovery(): Unit = runBlocking {
        val id = prepareAndSave()
        val revisionId = UUID.randomUUID()
        db.ocrDao().insertRevision(
            OcrRevision(
                id = revisionId,
                sourceId = id,
                state = OcrRevisionState.RUNNING,
                engineId = "test-engine",
                modelVersion = "test",
                orientation = Orientation.NORMAL,
                sourceDigest = ByteArray(32) { 4 },
                startedAt = 1,
                extractedAt = null,
                reviewState = OcrReviewState.UNREVIEWED,
                failureReason = null,
                charCount = 0,
                spanCount = 0,
            ).toEntity(),
        )

        val report = recoveryRepository.recover()

        assertEquals(1, report.markedStaleOcrRevisions)
        assertEquals(OcrRevisionState.STALE, db.ocrDao().findRevision(revisionId.toString())!!.toDomain().state)
    }

    @Test
    fun runningOcrRevisionIsMarkedStaleAfterFreshDatabaseReopen(): Unit = runBlocking {
        val id = prepareAndSave()
        val revisionId = UUID.randomUUID()
        db.ocrDao().insertRevision(
            OcrRevision(
                id = revisionId,
                sourceId = id,
                state = OcrRevisionState.RUNNING,
                engineId = "test-engine",
                modelVersion = "test",
                orientation = Orientation.NORMAL,
                sourceDigest = ByteArray(32) { 4 },
                startedAt = 1,
                extractedAt = null,
                reviewState = OcrReviewState.UNREVIEWED,
                failureReason = null,
                charCount = 0,
                spanCount = 0,
            ).toEntity(),
        )

        // Closing the database models the durable boundary at an abrupt
        // process death: the in-flight engine has no opportunity to publish a
        // result or run OcrRepository's cooperative cancellation callback.
        db.close()
        val restartedWrapper = KeystoreWrapper(alias)
        val restarted = VaultBootstrapper.bootstrap(paths, restartedWrapper) as VaultBootstrapResult.Ready
        db = OpenLifeDatabaseFactory.create(context, paths, restarted.databaseSecret)
        val report = RecoveryRepository(paths, db, restartedWrapper, MutationQueue()).recover()

        assertEquals(1, report.markedStaleOcrRevisions)
        val recovered = db.ocrDao().findRevision(revisionId.toString())!!.toDomain()
        assertEquals(OcrRevisionState.STALE, recovered.state)
        assertEquals(OcrFailureReason.PROCESS_RESTART, recovered.failureReason)
        assertEquals(0, db.ocrDao().findSpans(revisionId.toString()).size)
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

    /**
     * P1-14-R3 changed this from "startup marks CORRUPT": startup checks
     * framing only, so tampered ciphertext with intact framing stays READY
     * until first read or Settings > Verify all items (VerifyAllTest).
     */
    @Test
    fun readySourceWithTamperedCiphertextIsLeftForDeepVerification(): Unit = runBlocking {
        val id = prepareAndSave()
        val blob = paths.blobFile(id)
        val bytes = blob.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        blob.writeBytes(bytes)

        val report = recoveryRepository.recover()

        assertEquals(SourceState.READY, db.sourceDao().findById(id.toString())!!.toDomain().state)
        assertEquals(0, report.markedCorrupt)
        assertArrayEquals("the blob is untouched", bytes, blob.readBytes())
        assertTrue(
            "deep verification still catches it",
            recoveryRepository.verifyAll().markedCorrupt == 1 &&
                db.sourceDao().findById(id.toString())!!.toDomain().state == SourceState.CORRUPT,
        )
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
        // Simulates a kill mid-deletion (the
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
    fun deletingRowIsRetainedWhenRecoveryCannotRemoveAnArtefact(): Unit = runBlocking {
        val id = prepareAndSave()
        val entity = db.sourceDao().findById(id.toString())!!
        db.sourceDao().update(entity.toDomain().copy(state = SourceState.DELETING).toEntity())

        val blob = paths.blobFile(id)
        assertTrue(blob.delete())
        assertTrue(blob.mkdir())
        java.io.File(blob, "occupied").writeText("x")

        val report = recoveryRepository.recover()

        assertEquals(0, report.resumedDeletions)
        val row = db.sourceDao().findById(id.toString())
        assertTrue(row != null)
        assertEquals(SourceState.DELETING, row!!.toDomain().state)
        assertTrue(blob.exists())

        assertTrue(java.io.File(blob, "occupied").delete())
        assertTrue(blob.delete())
        val retryReport = recoveryRepository.recover()
        assertEquals(1, retryReport.resumedDeletions)
        assertEquals(null, db.sourceDao().findById(id.toString()))
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

    /** P1-15-R5 / C0-14: a deletion interrupted after marking DELETING is finished by recovery. */
    @Test
    fun deletionInterruptedAfterMarkingIsResumed(): Unit = runBlocking {
        val id = prepareAndSave()
        val blob = paths.blobFile(id)
        val interrupting = object : ArtefactFileOps by ArtefactFileOps.Default {
            override fun deleteIfExists(file: java.io.File): Boolean {
                if (file == blob) throw IllegalStateException("synthetic interruption before the blob is removed")
                return ArtefactFileOps.Default.deleteIfExists(file)
            }
        }
        val interrupted = DeletionRepository(paths, db, MutationQueue(), fileOps = interrupting)

        assertEquals(DeleteResult.Failed, interrupted.deleteSource(id))
        assertEquals(SourceState.DELETING.name, db.sourceDao().findById(id.toString())!!.state)
        assertTrue(blob.exists())

        val report = recoveryRepository.recover()

        assertEquals(1, report.resumedDeletions)
        assertEquals(null, db.sourceDao().findById(id.toString()))
        assertTrue("no artefact survives the resumed deletion", !blob.exists())
    }

    /** P1-01-R2: abandoned stages go, the active one and every other state stay. */
    @Test
    fun cleanAbandonedStagesRemovesOtherStagedRowsButKeepsTheActiveOne(): Unit = runBlocking {
        val ready = prepareAndSave()
        val active = prepareOnly()
        val abandoned = prepareOnly()
        assertTrue(paths.stageFile(abandoned).exists())

        val cleaned = recoveryRepository.cleanAbandonedStages(activeSourceId = active)

        assertEquals(1, cleaned)
        assertEquals(null, db.sourceDao().findById(abandoned.toString()))
        assertTrue("abandoned stage file removed", !paths.stageFile(abandoned).exists())
        assertEquals(SourceState.STAGED, db.sourceDao().findById(active.toString())!!.toDomain().state)
        assertTrue("active stage file kept", paths.stageFile(active).exists())
        assertEquals(SourceState.READY, db.sourceDao().findById(ready.toString())!!.toDomain().state)
        assertTrue(paths.blobFile(ready).exists())
    }

    @Test
    fun cleanAbandonedStagesLeavesRowWhenFileCannotBeRemoved(): Unit = runBlocking {
        val id = prepareOnly()
        val stage = paths.stageFile(id)
        assertTrue(stage.delete())
        assertTrue(stage.mkdir())
        java.io.File(stage, "occupied").writeText("x")

        val cleaned = recoveryRepository.cleanAbandonedStages(activeSourceId = null)

        assertEquals(0, cleaned)
        assertEquals(SourceState.STAGED, db.sourceDao().findById(id.toString())!!.toDomain().state)
        assertTrue(java.io.File(stage, "occupied").delete())
        assertTrue(stage.delete())
    }

    /** P1-01-R3: a pass that finds a mutation in progress skips (null) rather than waiting. */
    @Test
    fun cleanAbandonedStagesSkipsWhileAMutationIsInProgress(): Unit = runBlocking {
        val id = prepareOnly()
        val holding = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val queue = queueForTest
        val mutation = async(kotlinx.coroutines.Dispatchers.Default) {
            queue.withMutation { holding.complete(Unit); release.await() }
        }
        holding.await()
        try {
            assertEquals(null, recoveryRepository.cleanAbandonedStages(activeSourceId = null))
            assertEquals(SourceState.STAGED, db.sourceDao().findById(id.toString())!!.toDomain().state)
        } finally {
            release.complete(Unit)
            mutation.await()
        }
    }

    /** P1-14-R3/R4: startup is shallow; it never decrypts, so a Keystore hiccup cannot poison state. */
    @Test
    fun startupDoesNotDecryptReadyBlobsAndTransientKeystoreErrorLeavesRowReady(): Unit = runBlocking {
        val id = prepareAndSave()
        val unwraps = java.util.concurrent.atomic.AtomicInteger()
        val failing = object : org.openlife.vault.crypto.KeystoreWrapper(alias) {
            override fun unwrap(
                envelope: org.openlife.vault.crypto.Envelope,
                domain: org.openlife.vault.crypto.EnvelopeDomain,
                sourceId: UUID?,
            ): ByteArray {
                unwraps.incrementAndGet()
                throw org.openlife.vault.crypto.KeystoreUnavailableException(java.security.KeyStoreException("synthetic"))
            }
        }

        RecoveryRepository(paths, db, failing, MutationQueue()).recover()

        assertEquals("startup must not unwrap or decrypt any blob", 0, unwraps.get())
        assertEquals(SourceState.READY, db.sourceDao().findById(id.toString())!!.toDomain().state)
    }

    @Test
    fun missingOrMalformedBlobHeaderStillMarksCorrupt(): Unit = runBlocking {
        val missing = prepareAndSave()
        val malformed = prepareAndSave2()
        assertTrue(paths.blobFile(missing).delete())
        paths.blobFile(malformed).writeBytes("not an OpenLife envelope".toByteArray())

        recoveryRepository.recover()

        assertEquals(SourceState.CORRUPT, db.sourceDao().findById(missing.toString())!!.toDomain().state)
        assertEquals(SourceState.CORRUPT, db.sourceDao().findById(malformed.toString())!!.toDomain().state)
        assertTrue("a malformed blob is kept for diagnosis", paths.blobFile(malformed).exists())
    }

    /** A second, distinct saved source (the default fixture bytes would be a duplicate). */
    private suspend fun prepareAndSave2(): UUID {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        val prepared = importRepository.prepareImport(
            ByteArrayInputStream(out.toByteArray()), "image/jpeg", IntakeKind.SHARE,
        ) as PrepareResult.Prepared
        assertTrue(importRepository.saveImport(prepared.sourceId) is SaveResult.Saved)
        return prepared.sourceId
    }
}
