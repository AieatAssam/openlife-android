package org.openlife.vault.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.facts.ExtractionOutcome
import org.openlife.vault.facts.ExtractionTrigger
import org.openlife.vault.facts.FactExtractionResult
import org.openlife.vault.facts.StaleReason
import org.openlife.vault.storage.FactDeviceRows
import org.openlife.vault.storage.OpenLifeDatabase
import java.util.UUID

/**
 * P3-04 through Room on SQLCipher: FactExtractionRepository under the mutation
 * queue and the C2 triggers. FactExtractionCoreTest proves the same cases on host
 * SQLite. This class is a device gap until it runs on hardware (P3-04 notes).
 */
@RunWith(AndroidJUnit4::class)
class FactExtractionRepositoryTest {
    private lateinit var db: OpenLifeDatabase
    private lateinit var rows: FactDeviceRows
    private val source = UUID.randomUUID().toString()
    private val reading = UUID.nameUUIDFromBytes("$source-r".toByteArray()).toString()

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, OpenLifeDatabase::class.java)
            .openHelperFactory(SupportOpenHelperFactory("test-passphrase".toByteArray()))
            .addCallback(OpenLifeDatabase.readyInvariantCallback)
            .build()
        rows = FactDeviceRows(db.openHelper.writableDatabase)
        rows.readySourceWithReading(source)
    }

    @After
    fun tearDown() = db.close()

    private fun repository(deadlineMillis: Long = org.openlife.vault.facts.FactLimits.DEADLINE_MILLIS) =
        FactExtractionRepository(db, MutationQueue(), deadlineMillis = deadlineMillis)

    private fun exec(sql: String, vararg args: Any?) = db.openHelper.writableDatabase.execSQL(sql, arrayOf(*args))

    @Test
    fun extractsFactsWithEvidenceLinksFromReadySpans(): Unit = runBlocking {
        val result = repository().extract(UUID.fromString(source), ExtractionTrigger.AUTO)
        assertEquals(ExtractionOutcome.COMPLETED, (result as FactExtractionResult.Finished).outcome)
        assertEquals(2, rows.count("facts"))
        assertEquals(2, rows.count("fact_evidence"))
    }

    @Test
    fun rejectedOcrRevisionYieldsNoFacts(): Unit = runBlocking {
        exec("UPDATE ocr_revisions SET reviewState = 'REJECTED' WHERE id = ?", reading)
        val result = repository().extract(UUID.fromString(source))
        assertEquals(ExtractionOutcome.READING_REJECTED, (result as FactExtractionResult.Finished).outcome)
        assertEquals(0, rows.count("facts"))
    }

    @Test
    fun rerunIsIdempotentForUnchangedRevision(): Unit = runBlocking {
        val repo = repository()
        repo.extract(UUID.fromString(source))
        val second = repo.extract(UUID.fromString(source)) as FactExtractionResult.Finished
        assertEquals(0, second.createdRevisionCount)
        assertEquals(2, rows.count("facts"))
    }

    @Test
    fun newOcrRevisionMarksPreviousFactsStale(): Unit = runBlocking {
        val repo = repository()
        repo.extract(UUID.fromString(source))
        // The OCR commit calls FactStaleMarker in its own transaction (P3-04-R5); here it is called directly.
        FactStaleMarker(db.factDao(), clock = System::currentTimeMillis)
            .markForReading(reading, StaleReason.READING_REPLACED)
        assertEquals(
            2,
            rows.count("fact_revisions WHERE status = 'STALE' AND origin = 'EXTRACTED'"),
        )
        val again = repo.extract(UUID.fromString(source), ExtractionTrigger.USER) as FactExtractionResult.Finished
        assertEquals(2, again.createdRevisionCount)
    }

    @Test
    fun limitOverflowCommitsNothing(): Unit = runBlocking {
        val dao = db.factDao()
        for (i in 1..499) {
            dao.insertFact("f$i", source, "DATE", i.toLong())
            dao.insertRevision(
                id = "r$i", factId = "f$i", origin = "USER", kind = "DATE", valueJson = "{\"date\":\"2026-11-27\"}",
                canonicalText = "2026-11-27", rawText = null, status = "CONFIRMED", methodId = "user:date",
                methodVersion = null, locale = null, createdAt = i.toLong(), supersedesRevisionId = null,
            )
            dao.moveCurrentRevision("f$i", null, "r$i")
        }
        val result = repository().extract(UUID.fromString(source)) as FactExtractionResult.Finished
        assertEquals(ExtractionOutcome.LIMIT_EXCEEDED, result.outcome)
        assertEquals(499, rows.count("facts"))
    }

    @Test
    fun deadlineProducesFailedResultAndNoPartialRows(): Unit = runBlocking {
        val result = repository(deadlineMillis = 0).extract(UUID.fromString(source)) as FactExtractionResult.Finished
        assertEquals(ExtractionOutcome.TIMEOUT, result.outcome)
        assertEquals(0, rows.count("facts"))
        assertEquals(0, rows.count("fact_revisions"))
    }

    @Test
    fun ungroundedAndOutOfScriptSpansYieldNoFacts(): Unit = runBlocking {
        exec("UPDATE ocr_spans SET hasEvidenceRegion = 0 WHERE revisionId = ?", reading)
        val result = repository().extract(UUID.fromString(source)) as FactExtractionResult.Finished
        assertEquals(ExtractionOutcome.COMPLETED, result.outcome)
        assertEquals(0, rows.count("facts"))
    }

    @Test
    fun usesCurrentRevisionPointerAndCorrectionChain(): Unit = runBlocking {
        val span = rows.spanId(source, 2)
        exec("INSERT INTO ocr_user_revisions(id, revisionId, spanId, correctedText, createdAt, actor, supersedesId) " +
            "VALUES ('c1', ?, ?, 'Total EUR 1.00', 1, 'USER', NULL)", reading, span)
        exec("INSERT INTO ocr_user_revisions(id, revisionId, spanId, correctedText, createdAt, actor, supersedesId) " +
            "VALUES ('c2', ?, ?, 'Total EUR 20.00', 2, 'USER', 'c1')", reading, span)
        repository().extract(UUID.fromString(source))
        assertEquals(
            1,
            rows.count("fact_revisions WHERE canonicalText LIKE '%20.00%' AND methodId LIKE '%+correction'"),
        )
        assertEquals(0, rows.count("fact_revisions WHERE canonicalText LIKE '%1.00%'"))
    }
}
