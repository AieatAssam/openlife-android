package org.openlife.vault.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.facts.FactOrigin
import org.openlife.vault.facts.FactStatus
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.FactWriteResult
import org.openlife.vault.facts.StaleReason
import org.openlife.vault.storage.FactDeviceRows
import org.openlife.vault.storage.OpenLifeDatabase
import java.util.UUID

/**
 * P3-02-R5 through Room on SQLCipher: FactRepository writes under the
 * mutation queue and the C2 triggers. FactWriterTest proves the same cases
 * on host SQLite; this class is a device gap until it runs on hardware.
 */
@RunWith(AndroidJUnit4::class)
class FactRepositoryTest {
    private lateinit var db: OpenLifeDatabase
    private lateinit var repository: FactRepository
    private lateinit var rows: FactDeviceRows
    private val source = UUID.randomUUID().toString()
    private val fact = UUID.randomUUID().toString()
    private val revision = UUID.randomUUID().toString()

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, OpenLifeDatabase::class.java)
            .openHelperFactory(SupportOpenHelperFactory("test-passphrase".toByteArray()))
            .addCallback(OpenLifeDatabase.readyInvariantCallback)
            .build()
        repository = FactRepository(db, MutationQueue())
        rows = FactDeviceRows(db.openHelper.writableDatabase)
        rows.readySourceWithReading(source)
        rows.extractedFact(source, fact, revision)
    }

    @After
    fun tearDown() = db.close()

    private val corrected = FactValue("{\"date\":\"2026-11-27\"}", "2026-11-27")

    @Test
    fun basisEdgeMustPointToAnOlderRevisionOfTheSameFact(): Unit = runBlocking {
        val written = repository.correct(UUID.fromString(revision), corrected) as FactWriteResult.Written
        val current = repository.listFacts(UUID.fromString(source)).first().single()
        assertEquals(written.revisionId, current.revision.id)
        assertEquals(FactOrigin.USER, current.revision.origin)
        assertEquals(FactStatus.CONFIRMED, current.revision.status)
        assertEquals(
            revision,
            rows.text("SELECT dependencyId FROM dependency_edges WHERE edgeKind = 'BASIS' AND dependentId = '${written.revisionId}'"),
        )
        val refused = runCatching {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO dependency_edges(edgeKind, dependentKind, dependentId, dependentSourceId, dependencyKind, " +
                    "dependencyId, dependencySourceId) VALUES ('BASIS', 'FACT_REVISION', ?, ?, 'FACT_REVISION', ?, ?)",
                arrayOf<Any?>(revision, source, written.revisionId.toString(), source),
            )
        }
        assertTrue("a basis edge to a newer revision is refused", refused.isFailure)
    }

    @Test
    fun correctionOnSpanMarksDependentRevisionsStale(): Unit = runBlocking {
        val written = repository.correct(UUID.fromString(revision), corrected) as FactWriteResult.Written
        val marked = repository.markStaleForSpan(UUID.fromString(rows.spanId(source, 1)), StaleReason.SPAN_CORRECTED)
        assertEquals(2, marked)
        assertEquals("STALE", rows.text("SELECT status FROM fact_revisions WHERE id = '$revision'"))
        assertEquals("BASIS_STALE", rows.text("SELECT staleReason FROM fact_revisions WHERE id = '${written.revisionId}'"))
        assertEquals("1", rows.text("SELECT factInputEpoch FROM sources WHERE id = '$source'"))
    }
}
