package org.openlife.vault.repository

import kotlinx.coroutines.Dispatchers
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.ocr.OcrEngine
import org.openlife.vault.ocr.OcrEngineInput
import org.openlife.vault.ocr.OcrEngineOutput
import org.openlife.vault.ocr.OcrEngineRegistry
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRevisionState
import org.openlife.vault.ocr.OcrRunResult
import org.openlife.vault.ocr.OcrSpanDraft
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.OpenLifeDatabaseFactory
import org.openlife.vault.storage.VaultBootstrapResult
import org.openlife.vault.storage.VaultBootstrapper
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.toDomain

@RunWith(AndroidJUnit4::class)
class OcrRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var alias: String
    private lateinit var paths: VaultPaths
    private lateinit var wrapper: KeystoreWrapper
    private lateinit var db: OpenLifeDatabase
    private lateinit var importRepository: ImportRepository
    private lateinit var mutationQueue: MutationQueue

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
    fun ocrUsesAuthenticatedReadyBytesAndPersistsImmutableSpans(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val captured = AtomicReference<ByteArray>()
        val engine = TestEngine {
            captured.set(it.bytes.copyOf())
            OcrEngineOutput(listOf(OcrSpanDraft("hello", null, null)))
        }
        val repository = repository(engine)

        val result = repository.runOcr(sourceId)

        assertTrue(result is OcrRunResult.Completed)
        assertEquals(1, db.ocrDao().countRevisions())
        assertEquals(1, db.ocrDao().countSpans())
        assertEquals(
            SourceViewRepository(paths, db, wrapper, mutationQueue).loadReadyBytes(sourceId)!!.toList(),
            captured.get().toList(),
        )
    }

    @Test
    fun corruptSourceFailsClosedWithoutCreatingRevision(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val blob = paths.blobFile(sourceId)
        val bytes = blob.readBytes()
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 1).toByte()
        blob.writeBytes(bytes)

        val result = repository(TestEngine { OcrEngineOutput(emptyList()) }).runOcr(sourceId)

        assertEquals(OcrRunResult.Failed(null, OcrFailureReason.SOURCE_CORRUPT), result)
        assertEquals(0, db.ocrDao().countRevisions())
    }

    @Test
    fun cancellingRunningOcrLeavesAnExplicitCancelledRevision(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val started = CompletableDeferred<Unit>()
        val engine = TestEngine {
            started.complete(Unit)
            delay(Long.MAX_VALUE)
            OcrEngineOutput(emptyList())
        }
        val repository = repository(engine)
        val job: Job = launch { repository.runOcr(sourceId) }
        started.await()
        job.cancel()
        job.join()

        assertEquals(OcrRevisionState.CANCELLED, db.ocrDao().findRevisionsForSource(sourceId.toString()).single().toDomain().state)
    }

    @Test
    fun deletingSourceWhileEngineRunsMakesCommitStale(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = TestEngine {
            started.complete(Unit)
            release.await()
            OcrEngineOutput(listOf(OcrSpanDraft("late", null, null)))
        }
        val repository = repository(engine)
        val deletion = DeletionRepository(paths, db, mutationQueue)
        val run = launch { repository.runOcr(sourceId) }
        started.await()
        assertEquals(org.openlife.vault.repository.DeleteResult.Deleted, deletion.deleteSource(sourceId))
        release.complete(Unit)
        run.join()

        assertEquals(0, db.ocrDao().countRevisions())
    }

    @Test
    fun unsupportedScriptNeverBecomesReady(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val result = repository(TestEngine { OcrEngineOutput(listOf(OcrSpanDraft("hello мир", null, null))) })
            .runOcr(sourceId)

        assertEquals(OcrRunResult.Failed(nullOrRevision(result), OcrFailureReason.UNSUPPORTED_SCRIPT), result)
        assertEquals(OcrRevisionState.FAILED, db.ocrDao().findRevisionsForSource(sourceId.toString()).single().toDomain().state)
    }

    @Test
    fun correctionIsASeparateAttributedRowAndReviewDoesNotOverwriteOcr(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val result = repository(TestEngine { OcrEngineOutput(listOf(OcrSpanDraft("hello", null, null))) })
            .runOcr(sourceId) as OcrRunResult.Completed
        val spanId = result.spans.single().id
        val repository = repository(TestEngine { OcrEngineOutput(emptyList()) })

        assertTrue(repository.addCorrection(result.revisionId, spanId, "hullo"))
        assertTrue(repository.setReviewState(result.revisionId, OcrReviewState.ACCEPTED))

        assertEquals("hello", repository.findSpans(result.revisionId).single().text)
        assertEquals("hullo", db.ocrDao().findUserRevisions(result.revisionId.toString()).single().toDomain().correctedText)
        assertEquals(OcrReviewState.ACCEPTED, repository.findRevision(result.revisionId)!!.reviewState)
    }

    // --- P2-02: cancellation-safe, atomic OCR runs ---

    @Test
    fun commitFailureBetweenRevisionAndSpansLeavesNoReadyRevision(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val delegate = db.ocrDao()
        val failingSpans = object : org.openlife.vault.storage.OcrDao by delegate {
            override suspend fun insertSpans(spans: List<org.openlife.vault.storage.OcrSpanEntity>) {
                throw android.database.sqlite.SQLiteFullException("synthetic failure after READY")
            }
        }
        val repository = repository(TestEngine { OcrEngineOutput(listOf(OcrSpanDraft("hello", null, null))) }, ocrDao = failingSpans)

        val result = runCatching { repository.runOcr(sourceId) }.getOrNull()

        val revisions = db.ocrDao().findRevisionsForSource(sourceId.toString()).map { it.toDomain() }
        assertTrue("no READY revision without spans: $revisions", revisions.none { it.state == OcrRevisionState.READY })
        assertTrue("the run must report failure, not success: $result", result is OcrRunResult.Failed)
        assertEquals(OcrRevisionState.FAILED, revisions.single().state)
    }

    @Test
    fun cancellationDuringCaptureMarksRevisionCancelledNotRunning(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val inserted = CompletableDeferred<Unit>()
        val delegate = db.ocrDao()
        val suspendingAfterInsert = object : org.openlife.vault.storage.OcrDao by delegate {
            override suspend fun insertRevision(revision: org.openlife.vault.storage.OcrRevisionEntity) {
                delegate.insertRevision(revision)
                inserted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val repository = repository(TestEngine { OcrEngineOutput(emptyList()) }, ocrDao = suspendingAfterInsert)
        val job = launch(Dispatchers.Default) { repository.runOcr(sourceId) }
        inserted.await()
        job.cancel()
        job.join()

        val revision = db.ocrDao().findRevisionsForSource(sourceId.toString()).single().toDomain()
        assertEquals(OcrRevisionState.CANCELLED, revision.state)
    }

    @Test
    fun engineTimeoutIsReportedAsCancelledTimeoutAndDoesNotPropagate(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        // An engine-side timeout (any layer) must not escape as a cancellation of the caller.
        val repository = repository(TestEngine { kotlinx.coroutines.withTimeout(50) { delay(5_000) }; OcrEngineOutput(emptyList()) })

        val result = runCatching { repository.runOcr(sourceId) }
        assertTrue("timeout must not propagate: ${result.exceptionOrNull()}", result.isSuccess)
        val cancelled = result.getOrThrow()
        assertTrue("expected Cancelled: $cancelled", cancelled is OcrRunResult.Cancelled)
        assertEquals(OcrFailureReason.TIMEOUT, (cancelled as OcrRunResult.Cancelled).reason)
        val revision = db.ocrDao().findRevisionsForSource(sourceId.toString()).single().toDomain()
        assertEquals(OcrRevisionState.CANCELLED, revision.state)
        assertEquals(OcrFailureReason.TIMEOUT, revision.failureReason)
    }

    @Test
    fun repositoryDeadlineIsReportedAsTimeout(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val repository = repository(TestEngine { delay(5_000); OcrEngineOutput(emptyList()) }, deadlineMillis = 100)

        val result = repository.runOcr(sourceId)

        assertEquals(OcrFailureReason.TIMEOUT, (result as OcrRunResult.Cancelled).reason)
    }

    @Test
    fun decodeFailureIsEngineFailureNotLimitExceeded(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        // Today's adapter reports an undecodable image with IllegalArgumentException.
        val repository = repository(TestEngine { throw IllegalArgumentException("OCR source could not be decoded") })

        val result = repository.runOcr(sourceId)

        assertEquals(OcrFailureReason.ENGINE_FAILURE, (result as OcrRunResult.Failed).reason)
        val limit = repository(TestEngine { throw org.openlife.vault.ocr.OcrLimitExceededException("too many spans") })
            .runOcr(sourceId)
        assertEquals(OcrFailureReason.LIMIT_EXCEEDED, (limit as OcrRunResult.Failed).reason)
    }

    @Test
    fun inputBytesAreZeroedAfterExtraction(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val seen = AtomicReference<ByteArray>()
        val repository = repository(TestEngine {
            seen.set(it.bytes)
            OcrEngineOutput(listOf(OcrSpanDraft("hello", null, null)))
        })

        repository.runOcr(sourceId)

        assertTrue("captured plaintext must be zeroed", seen.get().all { it == 0.toByte() })
    }

    @Test
    fun cancellingTheJobCancelsTheEngineTaskBeforeBitmapRecycle(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val started = CompletableDeferred<Unit>()
        val task = SlowToSettleTask(events)
        val engine = TestEngine {
            started.complete(Unit)
            try {
                org.openlife.vault.ocr.awaitEngineTask(task)
            } finally {
                events += "bitmap recycled"
            }
        }
        val job = launch(Dispatchers.Default) { repository(engine).runOcr(sourceId) }
        started.await()
        job.cancel()
        job.join()

        assertEquals(listOf("task cancelled", "task settled", "bitmap recycled"), events.toList())
    }

    /** Settles only after cancel(), and only later, like a recogniser finishing its frame. */
    private class SlowToSettleTask(private val events: MutableList<String>) :
        org.openlife.vault.ocr.EngineTask<OcrEngineOutput> {
        @Volatile private var listener: ((Result<OcrEngineOutput>) -> Unit)? = null

        override fun onSettled(listener: (Result<OcrEngineOutput>) -> Unit) {
            this.listener = listener
        }

        override fun cancel() {
            events += "task cancelled"
            Thread {
                Thread.sleep(200)
                events += "task settled"
                listener?.invoke(Result.failure(java.util.concurrent.CancellationException("engine stopped")))
            }.start()
        }
    }

    private fun nullOrRevision(result: OcrRunResult): UUID? =
        (result as? OcrRunResult.Failed)?.revisionId

    private suspend fun prepareAndSave(): UUID {
        val prepared = importRepository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE,
        ) as PrepareResult.Prepared
        importRepository.saveImport(prepared.sourceId)
        return prepared.sourceId
    }

    private fun repository(
        engine: OcrEngine,
        ocrDao: org.openlife.vault.storage.OcrDao = db.ocrDao(),
        deadlineMillis: Long = org.openlife.vault.ocr.OcrLimits.DEADLINE_MILLIS,
    ) = OcrRepository(
        paths = paths,
        database = db,
        keystoreWrapper = wrapper,
        engineRegistry = OcrEngineRegistry(listOf(engine), engine.id),
        mutationQueue = mutationQueue,
        ocrDao = ocrDao,
        deadlineMillis = deadlineMillis,
    )

    private fun syntheticJpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private class TestEngine(
        private val block: suspend (OcrEngineInput) -> OcrEngineOutput,
    ) : OcrEngine {
        override val id: String = "test-engine"
        override val modelVersion: String = "test"
        override suspend fun extract(input: OcrEngineInput): OcrEngineOutput = block(input)
    }
}
