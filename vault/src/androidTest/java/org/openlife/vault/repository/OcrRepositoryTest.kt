package org.openlife.vault.repository

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

    private fun nullOrRevision(result: OcrRunResult): UUID? =
        (result as? OcrRunResult.Failed)?.revisionId

    private suspend fun prepareAndSave(): UUID {
        val prepared = importRepository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE,
        ) as PrepareResult.Prepared
        importRepository.saveImport(prepared.sourceId)
        return prepared.sourceId
    }

    private fun repository(engine: OcrEngine) = OcrRepository(
        paths = paths,
        database = db,
        keystoreWrapper = wrapper,
        engineRegistry = OcrEngineRegistry(listOf(engine), engine.id),
        mutationQueue = mutationQueue,
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
