package org.openlife.vault.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.facts.ExtractionOutcome
import org.openlife.vault.facts.ExtractionTrigger
import org.openlife.vault.facts.FactExtractionResult
import org.openlife.vault.facts.StaleReason
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.ocr.OcrEngine
import org.openlife.vault.ocr.OcrEngineInput
import org.openlife.vault.ocr.OcrEngineOutput
import org.openlife.vault.ocr.OcrEngineRegistry
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRunResult
import org.openlife.vault.ocr.OcrSpanDraft
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.OpenLifeDatabaseFactory
import org.openlife.vault.storage.VaultBootstrapResult
import org.openlife.vault.storage.VaultBootstrapper
import org.openlife.vault.storage.VaultPaths

/**
 * P3-04 review fixes on the device (P-01, P-04, P-05, P-07 hooks and the C2-R40 stale rows), through the real OCR
 * repository, the mutation queue and the C2 triggers. Each test is written before its fix and recorded in
 * docs/verification/P3-04-extraction-pipeline.md.
 */
@RunWith(AndroidJUnit4::class)
class OcrFactHooksTest {
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
    fun lateFinisherDoesNotStaleCurrentFacts(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val release = CompletableDeferred<Unit>()
        val slow = ocr(FixedEngine { release.await(); spans("Due 01.01.2026") })
        val late = async(Dispatchers.Default) { slow.runOcr(sourceId) }
        awaitRevisionCount(1)

        val fast = ocr(FixedEngine { spans("Due 26.11.2026", "Total EUR 12.50") }, onReady = extractionHook())
        val fastRevision = (fast.runOcr(sourceId) as OcrRunResult.Completed).revisionId
        assertEquals(2, scalar("SELECT COUNT(*) FROM facts WHERE sourceId = '$sourceId'"))

        release.complete(Unit)
        late.await()

        assertEquals("the late finisher does not move the pointer", fastRevision.toString(), currentReading(sourceId))
        assertEquals(
            "facts of the current reading stay current",
            0L,
            scalar("SELECT COUNT(*) FROM fact_revisions WHERE status = 'STALE'"),
        )
    }

    @Test
    fun undoRejectBumpsEpoch(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val repo = ocr(FixedEngine { spans("Due 26.11.2026") })
        val result = repo.runOcr(sourceId) as OcrRunResult.Completed
        val span = result.spans.single().id
        assertTrue(repo.rejectSpan(span))
        val afterReject = epoch(sourceId)

        assertTrue(repo.undoRejectSpan(span))

        assertTrue("undoing a rejection bumps the fact-input epoch", epoch(sourceId) > afterReject)
        assertTrue(repo.setReviewState(result.revisionId, OcrReviewState.REJECTED))
        val afterReadingReject = epoch(sourceId)
        assertTrue(repo.setReviewState(result.revisionId, OcrReviewState.UNREVIEWED))
        assertTrue("leaving REJECTED bumps the epoch", epoch(sourceId) > afterReadingReject)
    }

    @Test
    fun onReadyFailureStillCompletesOcr(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val repo = ocr(FixedEngine { spans("Due 26.11.2026") }, onReady = { throw IllegalStateException("hook failed") })

        val result = repo.runOcr(sourceId)

        assertTrue("$result", result is OcrRunResult.Completed)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM ocr_spans"))
    }

    @Test
    fun autoRunAfterReadyCreatesFactsOnce(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val repo = ocr(FixedEngine { spans("Due 26.11.2026", "Total EUR 12.50") }, onReady = extractionHook())

        repo.runOcr(sourceId)
        assertEquals(2L, scalar("SELECT COUNT(*) FROM facts WHERE sourceId = '$sourceId'"))

        assertNull("the automatic run is not due a second time", extraction().extractIfDue(sourceId))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM fact_revisions"))
    }

    @Test
    fun staleTrigger_READING_REPLACED(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        ocr(FixedEngine { spans("Due 26.11.2026", "Total EUR 12.50") }).runOcr(sourceId)
        extraction().extract(sourceId, ExtractionTrigger.AUTO)

        ocr(FixedEngine { spans("Due 03.03.2025") }).runOcr(sourceId)

        assertStale(StaleReason.READING_REPLACED, 2)
    }

    @Test
    fun staleTrigger_READING_REJECTED(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val result = ocr(FixedEngine { spans("Due 26.11.2026", "Total EUR 12.50") }).runOcr(sourceId) as OcrRunResult.Completed
        extraction().extract(sourceId, ExtractionTrigger.AUTO)

        assertTrue(ocr(FixedEngine { spans() }).setReviewState(result.revisionId, OcrReviewState.REJECTED))

        assertStale(StaleReason.READING_REJECTED, 2)
    }

    @Test
    fun staleTrigger_SPAN_CORRECTED(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val repo = ocr(FixedEngine { spans("Due 26.11.2026", "Total EUR 12.50") })
        val result = repo.runOcr(sourceId) as OcrRunResult.Completed
        extraction().extract(sourceId, ExtractionTrigger.AUTO)
        val dateSpan = result.spans.first { it.text.startsWith("Due") }.id

        assertTrue(repo.addCorrection(result.revisionId, dateSpan, "Due 03.03.2025"))

        assertStale(StaleReason.SPAN_CORRECTED, 1)
    }

    @Test
    fun staleTrigger_READING_CORRECTED(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val repo = ocr(FixedEngine { spans("Due 26.11.2026", "Total EUR 12.50") })
        val result = repo.runOcr(sourceId) as OcrRunResult.Completed
        extraction().extract(sourceId, ExtractionTrigger.AUTO)

        assertTrue(repo.addCorrection(result.revisionId, null, "whole reading corrected"))

        assertStale(StaleReason.READING_CORRECTED, 2)
    }

    @Test
    fun staleTrigger_SPAN_REJECTED(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val repo = ocr(FixedEngine { spans("Due 26.11.2026", "Total EUR 12.50") })
        val result = repo.runOcr(sourceId) as OcrRunResult.Completed
        extraction().extract(sourceId, ExtractionTrigger.AUTO)
        val dateSpan = result.spans.first { it.text.startsWith("Due") }.id

        assertTrue(repo.rejectSpan(dateSpan))

        assertStale(StaleReason.SPAN_REJECTED, 1)
    }

    /**
     * C2-R40 BASIS_STALE: a USER clarification of an EXTRACTED fact depends on it (the trigger creates the BASIS
     * edge). Replacing the reading stales the EXTRACTED revision directly; the USER revision is reached by BASIS.
     */
    @Test
    fun staleTrigger_BASIS_STALE(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        ocr(FixedEngine { spans("Due 26.11.2026") }).runOcr(sourceId)
        extraction().extract(sourceId, ExtractionTrigger.AUTO)
        val factId = scalarText("SELECT id FROM facts WHERE sourceId = '$sourceId'")
        val extractedRevision = scalarText("SELECT currentRevisionId FROM facts WHERE sourceId = '$sourceId'")
        db.factDao().insertRevision(
            id = "u1", factId = factId, origin = "USER",
            kind = "DATE", valueJson = "{\"date\":\"2026-03-03\"}", canonicalText = "2026-03-03", rawText = null,
            status = "CONFIRMED", methodId = "user:date", methodVersion = null, locale = null, createdAt = 1L,
            supersedesRevisionId = extractedRevision,
        )
        db.factDao().moveCurrentRevision(factId, extractedRevision, "u1")

        ocr(FixedEngine { spans("Due 03.03.2025") }).runOcr(sourceId)

        assertEquals(
            "the clarification is reached through BASIS",
            1L,
            scalar("SELECT COUNT(*) FROM fact_revisions WHERE id = 'u1' AND status = 'STALE' AND staleReason = 'BASIS_STALE'"),
        )
    }

    /**
     * T1 (C2-R40, C2 §7.4): the reading replacement is one transaction: the pointer move, the span inserts and the
     * STALE marking. A real failure inside the marking, arranged through the schema (a test-installed trigger that
     * aborts the STALE write), must roll all three back. No production code is changed to reach this path.
     */
    @Test
    fun staleMarkFailureRollsBackPointerMoveSpansAndEpoch(): Unit = runBlocking {
        val sourceId = prepareAndSave()
        val first = ocr(FixedEngine { spans("Due 26.11.2026") }).runOcr(sourceId) as OcrRunResult.Completed
        extraction().extract(sourceId, ExtractionTrigger.AUTO)
        val epochBefore = epoch(sourceId)
        exec(
            "CREATE TRIGGER test_fail_stale BEFORE UPDATE OF status ON fact_revisions " +
                "WHEN NEW.status = 'STALE' BEGIN SELECT RAISE(ABORT, 'induced stale failure'); END",
        )

        val second = try {
            ocr(FixedEngine { spans("Due 03.03.2025") }).runOcr(sourceId)
        } finally {
            exec("DROP TRIGGER test_fail_stale")
        }

        assertTrue("$second", second is OcrRunResult.Failed)
        val failedRevision = (second as OcrRunResult.Failed).revisionId.toString()
        assertEquals("the pointer stays on the first reading", first.revisionId.toString(), currentReading(sourceId))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM ocr_spans WHERE revisionId = '$failedRevision'"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM fact_revisions WHERE status = 'STALE'"))
        assertEquals("no epoch bump survives the rollback", epochBefore, epoch(sourceId))
    }

    private fun exec(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    private suspend fun assertStale(reason: StaleReason, expected: Long) {
        assertEquals(
            "rows staled by ${reason.name}",
            expected,
            scalar("SELECT COUNT(*) FROM fact_revisions WHERE status = 'STALE' AND staleReason = '${reason.name}'"),
        )
    }

    private fun extraction() = FactExtractionRepository(db, mutationQueue)

    private fun extractionHook(): suspend (UUID) -> Unit = { id -> extraction().extractIfDue(id) }

    private fun ocr(engine: OcrEngine, onReady: (suspend (UUID) -> Unit)? = null) = OcrRepository(
        paths = paths,
        database = db,
        keystoreWrapper = wrapper,
        engineRegistry = OcrEngineRegistry(listOf(engine), engine.id),
        mutationQueue = mutationQueue,
        onReady = onReady,
    )

    private suspend fun awaitRevisionCount(count: Long) = withContext(Dispatchers.Default) {
        withTimeout(TIMEOUT_MS) {
            while (scalar("SELECT COUNT(*) FROM ocr_revisions") < count) delay(POLL_MS)
        }
    }

    private fun spans(vararg texts: String): OcrEngineOutput = OcrEngineOutput(
        texts.map { OcrSpanDraft(it, null, OcrEvidenceRegion(0, 0, 10, 10)) },
    )

    private suspend fun epoch(sourceId: UUID): Long = checkNotNull(db.factDao().sourceEpoch(sourceId.toString()))

    private fun currentReading(sourceId: UUID): String = scalarText(
        "SELECT currentOcrRevisionId FROM sources WHERE id = '$sourceId'",
    )

    private fun scalar(sql: String): Long =
        db.openHelper.readableDatabase.query(sql).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    private fun scalarText(sql: String): String =
        db.openHelper.readableDatabase.query(sql).use { cursor -> cursor.moveToFirst(); cursor.getString(0) }

    private suspend fun prepareAndSave(): UUID {
        val prepared = importRepository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE,
        ) as PrepareResult.Prepared
        importRepository.saveImport(prepared.sourceId)
        return prepared.sourceId
    }

    private fun syntheticJpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private class FixedEngine(
        private val block: suspend (OcrEngineInput) -> OcrEngineOutput,
    ) : OcrEngine {
        override val id: String = "test-engine"
        override val modelVersion: String = "test"
        override suspend fun extract(input: OcrEngineInput): OcrEngineOutput = block(input)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
