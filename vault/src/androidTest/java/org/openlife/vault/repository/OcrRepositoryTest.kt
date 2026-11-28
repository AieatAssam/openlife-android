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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.openlife.vault.model.SourceState
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

    /**
     * P1-18-R8 (V-05): a transient Keystore failure while OCR captures the
     * source is retryable, not SOURCE_CORRUPT. Nothing about the source is
     * changed and a retry once Keystore recovers succeeds.
     */
    @Test
    fun transientAuthenticationFailureIsRetryableNotSourceCorrupt(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val flaky = org.openlife.vault.testing.DoFinalFaultWrapper(alias)
        flaky.failure = { org.openlife.vault.testing.KeystoreFaults.doFinalFailure() }
        val ocr = repository(TestEngine { OcrEngineOutput(emptyList()) }, keystore = flaky)

        val failed = ocr.runOcr(sourceId)

        assertEquals(OcrRunResult.Failed(null, OcrFailureReason.SOURCE_TEMPORARILY_UNAVAILABLE), failed)
        assertEquals(0, db.ocrDao().countRevisions())
        assertEquals(SourceState.READY, db.sourceDao().findById(sourceId.toString())!!.toDomain().state)

        flaky.failure = null
        assertTrue("a retry runs once Keystore recovers", ocr.runOcr(sourceId) is OcrRunResult.Completed)
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

        assertEquals("hello", db.ocrDao().findSpans(result.revisionId.toString()).single().text)
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
                // P1-20-R8: simulates an engine that settles late after cancel; the delay is the behaviour under test.
                Thread.sleep(200)
                events += "task settled"
                listener?.invoke(Result.failure(java.util.concurrent.CancellationException("engine stopped")))
            }.start()
        }
    }

    /** P2-01-R1: the viewer's view of a source is the latest revision with its spans and corrections. */
    @Test
    fun observeOcrViewEmitsLatestRevisionSpansAndCorrections(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val repository = repository(
            TestEngine { OcrEngineOutput(listOf(OcrSpanDraft("first", null, null), OcrSpanDraft("second", null, null))) },
        )
        // Back to back: both runs may start in the same millisecond, so "latest" needs a tie-break.
        val older = repository.runOcr(sourceId) as OcrRunResult.Completed
        val latest = repository.runOcr(sourceId) as OcrRunResult.Completed
        val spanId = latest.spans.first().id
        assertTrue(repository.addCorrection(latest.revisionId, spanId, "corrected one"))
        assertTrue(repository.addCorrection(latest.revisionId, spanId, "corrected two"))
        assertTrue(repository.addCorrection(latest.revisionId, null, "whole revision note"))
        assertTrue(repository.setReviewState(latest.revisionId, org.openlife.vault.ocr.OcrReviewState.ACCEPTED))

        val view = kotlinx.coroutines.withTimeout(VIEW_TIMEOUT_MS) {
            repository.observeOcrView(sourceId).first { view ->
                view != null && view.revision.reviewState == org.openlife.vault.ocr.OcrReviewState.ACCEPTED &&
                    view.revisionCorrection != null
            }
        }!!

        assertEquals(latest.revisionId, view.revision.id)
        assertEquals(listOf("first", "second"), view.spans.map { it.span.text })
        assertEquals("the latest correction wins", "corrected two", view.spans[0].correction?.correctedText)
        assertEquals(null, view.spans[1].correction)
        assertEquals("whole revision note", view.revisionCorrection?.correctedText)
        assertEquals("earlier revisions are history", listOf(older.revisionId), view.history.map { it.id })
    }

    @Test
    fun observeOcrViewEmitsNullAfterSourceDeletion(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val repository = repository(TestEngine { OcrEngineOutput(listOf(OcrSpanDraft("gone soon", null, null))) })
        assertTrue(repository.runOcr(sourceId) is OcrRunResult.Completed)
        kotlinx.coroutines.withTimeout(VIEW_TIMEOUT_MS) { repository.observeOcrView(sourceId).first { it != null } }

        assertTrue(DeletionRepository(paths, db, mutationQueue).deleteSource(sourceId) is DeleteResult.Deleted)

        kotlinx.coroutines.withTimeout(VIEW_TIMEOUT_MS) { repository.observeOcrView(sourceId).first { it == null } }
    }

    /**
     * P3-05 review U-01: a reading id from a route is looked up with its Source. A reading of another Source is
     * never returned, even though the id exists; an id that does not exist is never returned either.
     */
    @Test
    fun observeOcrViewOfRevisionRefusesAReadingOfAnotherSource(): Unit = runBlocking {
        val sourceA = prepareAndSave(colour = 0xFF0000FF.toInt())
        val sourceB = prepareAndSave(colour = 0xFF00FF00.toInt())
        val repository = repository(TestEngine { OcrEngineOutput(listOf(OcrSpanDraft("reading of B", null, null))) })
        val readingOfB = repository.runOcr(sourceB) as OcrRunResult.Completed
        kotlinx.coroutines.withTimeout(VIEW_TIMEOUT_MS) { repository.observeOcrView(sourceB).first { it != null } }

        assertEquals(
            "the owning Source gets its reading",
            readingOfB.revisionId,
            repository.observeOcrViewOfRevision(sourceB, readingOfB.revisionId).first()?.revision?.id,
        )
        assertNull("another Source gets nothing", repository.observeOcrViewOfRevision(sourceA, readingOfB.revisionId).first())
        assertNull("an unknown reading gets nothing", repository.observeOcrViewOfRevision(sourceA, UUID.randomUUID()).first())
    }

    /**
     * P2-06-R13/R14 (E-06, E-07): reject and undo only append review rows, never
     * touch the span text, are refused unless the revision is READY or the
     * span is in the right state, and reach the viewer through the flow.
     */
    @Test
    fun rejectSpanKeepsTextRequiresReadyAndIsObservedThroughTheFlow(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val repository = repository(TestEngine { OcrEngineOutput(listOf(OcrSpanDraft("hello", null, null))) })
        val result = repository.runOcr(sourceId) as OcrRunResult.Completed
        val spanId = result.spans.single().id

        assertTrue(repository.rejectSpan(spanId))
        assertFalse("a rejected span cannot be rejected twice", repository.rejectSpan(spanId))
        val rejected = kotlinx.coroutines.withTimeout(VIEW_TIMEOUT_MS) {
            repository.observeOcrView(sourceId).first { it?.spans?.single()?.review != null }
        }!!
        assertEquals(OcrReviewState.REJECTED, rejected.spans.single().review?.reviewState)
        assertEquals("hello", rejected.spans.single().span.text)

        assertTrue(repository.undoRejectSpan(spanId))
        assertFalse("nothing left to undo", repository.undoRejectSpan(spanId))
        val undone = kotlinx.coroutines.withTimeout(VIEW_TIMEOUT_MS) {
            repository.observeOcrView(sourceId).first {
                it?.spans?.single()?.review?.reviewState == OcrReviewState.UNREVIEWED
            }
        }!!
        assertEquals("hello", undone.spans.single().span.text)
        assertEquals("hello", db.ocrDao().findSpans(result.revisionId.toString()).single().text)
        assertEquals("rows are appended, never updated", 2, db.ocrSpanReviewDao().count())

        // A span on a revision that is not READY cannot be reviewed.
        val staleRevision = UUID.randomUUID()
        val staleSpan = UUID.randomUUID()
        db.ocrDao().insertRevision(
            db.ocrDao().findRevision(result.revisionId.toString())!!
                .copy(id = staleRevision.toString(), state = OcrRevisionState.STALE.name),
        )
        db.ocrDao().insertSpans(
            listOf(
                db.ocrDao().findSpans(result.revisionId.toString()).single()
                    .copy(id = staleSpan.toString(), revisionId = staleRevision.toString()),
            ),
        )
        assertFalse(repository.rejectSpan(staleSpan))
        assertFalse(repository.undoRejectSpan(staleSpan))
        assertFalse("an unknown span is refused", repository.rejectSpan(UUID.randomUUID()))
        assertEquals(2, db.ocrSpanReviewDao().count())
    }

    /** P2-06-R17 (E-10): an engine box past the source edge is stored ungrounded, never drawn outside the image. */
    @Test
    fun regionBeyondSourceBoundsIsNotPersistedAsGrounded(): Unit = runBlocking {
        val sourceId = prepareAndSave() // 64x48, NORMAL
        val repository = repository(
            TestEngine {
                OcrEngineOutput(
                    listOf(
                        OcrSpanDraft("past the edge", null, org.openlife.vault.ocr.OcrEvidenceRegion(0, 0, 65, 10)),
                        OcrSpanDraft("inside", null, org.openlife.vault.ocr.OcrEvidenceRegion(0, 0, 64, 48)),
                    ),
                )
            },
        )

        val result = repository.runOcr(sourceId) as OcrRunResult.Completed

        val stored = db.ocrDao().findSpans(result.revisionId.toString()).map { it.toDomain() }.sortedBy { it.ordinal }
        assertEquals(listOf("past the edge", "inside"), stored.map { it.text })
        assertNull(stored[0].evidenceRegion)
        assertEquals(org.openlife.vault.ocr.OcrEvidenceRegion(0, 0, 64, 48), stored[1].evidenceRegion)
    }

    private fun nullOrRevision(result: OcrRunResult): UUID? =
        (result as? OcrRunResult.Failed)?.revisionId

    private suspend fun prepareAndSave(colour: Int = 0): UUID {
        val prepared = importRepository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes(colour)), "image/jpeg", IntakeKind.SHARE,
        ) as PrepareResult.Prepared
        importRepository.saveImport(prepared.sourceId)
        return prepared.sourceId
    }

    private fun repository(
        engine: OcrEngine,
        ocrDao: org.openlife.vault.storage.OcrDao = db.ocrDao(),
        deadlineMillis: Long = org.openlife.vault.ocr.OcrLimits.DEADLINE_MILLIS,
        keystore: KeystoreWrapper = wrapper,
    ) = OcrRepository(
        paths = paths,
        database = db,
        keystoreWrapper = keystore,
        engineRegistry = OcrEngineRegistry(listOf(engine), engine.id),
        mutationQueue = mutationQueue,
        ocrDao = ocrDao,
        deadlineMillis = deadlineMillis,
    )

    private fun syntheticJpegBytes(colour: Int = 0): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(colour)
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

    private companion object {
        const val VIEW_TIMEOUT_MS = 10_000L
    }
}
