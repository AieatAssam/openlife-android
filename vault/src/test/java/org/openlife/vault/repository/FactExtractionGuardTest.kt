package org.openlife.vault.repository

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.facts.ExtractionOutcome
import org.openlife.vault.facts.ExtractionTrigger
import org.openlife.vault.facts.FactExtractionResult
import org.openlife.vault.facts.FactLimits
import org.openlife.vault.facts.StaleReason
import org.openlife.vault.facts.extract.ExtractedKind
import org.openlife.vault.search.SearchFixture
import org.openlife.vault.storage.FactHost
import org.openlife.vault.storage.FactRows
import org.openlife.vault.storage.HostFactDao
import org.openlife.vault.storage.HostSqlite

/**
 * P3-04 review fixes on host SQLite: duplicate detection over the whole chain (C2-R32), continuation by equal text and
 * the global pairing order (C2-R33), the NonCancellable commit and the failure paths (C2-R29, C2 §7.6), the
 * extractIfDue guard (C2-R30), the epoch protocol (C2-R31, C2-R63) and the per-kind cap (C2-R57).
 */
class FactExtractionGuardTest {
    private val host = HostSqlite()
    private val rows = FactRows(host)
    private val dao = HostFactDao(host)
    private var now = 90_000L
    private var ids = 0
    private lateinit var fixture: SearchFixture

    @After
    fun close() = host.close()

    private fun core(gate: ExtractionGate = TransactionalHostGate(host), deadlineMillis: Long = FactLimits.DEADLINE_MILLIS) =
        FactExtractionCore(
            dao = dao,
            gate = gate,
            clock = { now++ },
            newId = { "id-${ids++}" },
            deadlineMillis = deadlineMillis,
        )

    /** Source `a` with a READY current reading `a-r` (`a-s1` "Due 26.11.2026", `a-s2` "Total EUR 12.50"). */
    private fun groundedReading() {
        fixture = FactHost.fresh(host)
        rows.readySourceWithReading(fixture, "a")
        grounded("a-r")
    }

    private fun grounded(revisionId: String) = host.exec(
        "UPDATE ocr_spans SET hasEvidenceRegion = 1, scriptStatus = 'LATIN' WHERE revisionId = ?",
        revisionId,
    )

    private fun extract(core: FactExtractionCore = core(), trigger: ExtractionTrigger = ExtractionTrigger.USER) =
        runBlocking { core.extract("a", trigger) }

    private fun finished(result: FactExtractionResult): FactExtractionResult.Finished {
        assertTrue("$result", result is FactExtractionResult.Finished)
        return result as FactExtractionResult.Finished
    }

    private fun count(sql: String): Long = host.long(sql)

    private fun factCount() = count("SELECT COUNT(*) FROM facts WHERE sourceId = 'a'")

    private fun runRowCount() = count("SELECT COUNT(*) FROM fact_extraction_runs")

    /** The DATE fact's current revision, its status and its ordinal, for the tie-break check. */
    private fun currentDateRevisions(): List<List<Any?>> = host.query(
        "SELECT f.ordinal, r.status FROM facts f JOIN fact_revisions r ON r.id = f.currentRevisionId " +
            "WHERE f.sourceId = 'a' AND f.kind = 'DATE' ORDER BY f.ordinal",
    )

    /** Makes `a-r` STALE through the production marker, as a newer reading does. */
    private fun staleOldReading() = runBlocking {
        FactStaleMarker(dao, clock = { now++ }).markForReading("a-r", StaleReason.READING_REPLACED)
    }

    /** A new current reading `a-r2` with the given spans. */
    private fun newReading(id: String, spans: List<String>, move: Boolean = true) {
        fixture.revision(id, "a")
        spans.forEachIndexed { index, text -> fixture.span("$id-s${index + 1}", id, index, text) }
        grounded(id)
        if (move) rows.moveCurrentReading("a", id)
    }

    // P-02 (C2-R32 step 1): a clarified fact is not duplicated by a re-run on the unchanged reading.
    @Test
    fun rerunAfterClarifyCreatesNothing() {
        groundedReading()
        finished(extract(trigger = ExtractionTrigger.AUTO))
        val date = host.query("SELECT id, currentRevisionId FROM facts WHERE sourceId = 'a' AND kind = 'DATE'").single()
        // The user clarifies the DATE fact: a USER revision supersedes the EXTRACTED one and becomes current.
        rows.revision("u1", date[0] as String, origin = "USER", status = "CONFIRMED", supersedes = date[1] as String)
        rows.current(date[0] as String, "u1", expected = date[1] as String)
        val revisionsBefore = count("SELECT COUNT(*) FROM fact_revisions")

        val rerun = finished(extract())

        assertEquals("nothing new is created: $rerun", 0, rerun.createdRevisionCount)
        assertEquals(revisionsBefore, count("SELECT COUNT(*) FROM fact_revisions"))
        assertEquals(2L, factCount())
    }

    // P-03 (C2-R33): a candidate with equal kind but different canonical text never continues a stale fact.
    @Test
    fun continuationRequiresEqualCanonicalText() {
        groundedReading()
        finished(extract(trigger = ExtractionTrigger.AUTO))
        staleOldReading()
        newReading("a-r2", listOf("Due 03.03.2025", "Total EUR 12.50"))

        val result = finished(extract())

        // 2025-03-03 is a new fact; the MONEY fact still continues. The stale DATE fact stays stale.
        assertEquals("$result", 2, result.createdRevisionCount)
        assertEquals("the DATE candidate is a new fact", 3L, factCount())
        assertEquals(
            "the old DATE fact is not continued",
            1L,
            count(
                "SELECT COUNT(*) FROM facts f JOIN fact_revisions r ON r.id = f.currentRevisionId " +
                    "WHERE f.sourceId = 'a' AND f.kind = 'DATE' AND r.status = 'STALE'",
            ),
        )
    }

    // C2-R33 pairing order: among equal-overlap pairs the lower fact ordinal is taken first.
    @Test
    fun continuationTieBreakIsOverlapThenFactOrdinal() {
        groundedReading()
        fixture.span("a-r-s3", "a-r", 2, "Paid 26.11.2026")
        grounded("a-r")
        finished(extract(trigger = ExtractionTrigger.AUTO))
        assertEquals("two DATE facts, one per span", listOf(listOf<Any?>(1L, "UNREVIEWED"), listOf<Any?>(2L, "UNREVIEWED")), currentDateRevisions())
        staleOldReading()
        newReading("a-r2", listOf("Due 26.11.2026", "Total EUR 12.50"))

        finished(extract())

        assertEquals(
            "the candidate continues the lower ordinal; the other stays stale",
            listOf(listOf<Any?>(1L, "UNREVIEWED"), listOf<Any?>(2L, "STALE")),
            currentDateRevisions(),
        )
    }

    // P-06 (C2-R30): a rejected current reading is never due for the automatic run.
    @Test
    fun extractIfDueRefusesARejectedReading() {
        groundedReading()
        host.exec("UPDATE ocr_revisions SET reviewState = 'REJECTED' WHERE id = 'a-r'")

        assertNull("a rejected reading is not due", runBlocking { core().extractIfDue("a") })
        assertEquals(0L, runRowCount())
    }

    // P-06 (C2-R29): the commit is NonCancellable, so a cancelled run still ends with its rows committed.
    @Test
    fun commitIsNotInterruptedByCancellation() {
        groundedReading()
        var result: FactExtractionResult? = null
        runBlocking {
            lateinit var job: Job
            job = launch {
                result = core(CancellingGate(host) { job.cancel() }).extract("a", ExtractionTrigger.AUTO)
            }
            job.join()
        }
        assertNotNull("the commit ran to its end", result)
        assertEquals(ExtractionOutcome.COMPLETED, finished(result!!).outcome)
        assertEquals(2L, factCount())
    }

    // P-05: a failed commit whose fallback run row also fails still returns STORAGE_FAILURE, not an exception.
    @Test
    fun failedFallbackRunRowStillReturnsStorageFailure() {
        groundedReading()

        val result = finished(extract(core(FaultyGate(host) { true })))

        assertEquals(ExtractionOutcome.STORAGE_FAILURE, result.outcome)
        assertEquals(0L, factCount())
    }

    // P-05: a terminal outcome whose run row cannot be written still returns that outcome.
    @Test
    fun failedTerminalRunRowStillReturnsItsOutcome() {
        groundedReading()
        host.exec("UPDATE ocr_revisions SET reviewState = 'REJECTED' WHERE id = 'a-r'")

        val result = finished(extract(core(FaultyGate(host) { true })))

        assertEquals(ExtractionOutcome.READING_REJECTED, result.outcome)
    }

    // T2 (C2-R31): an epoch bump between read and commit writes nothing, and the reading stays due.
    @Test
    fun epochBumpBetweenReadAndCommitWritesNothing() {
        groundedReading()
        assertSuperseded(HookedGate(host) { dao.bumpFactInputEpoch("a") })
        assertNotNullDue("a-r")
    }

    // T2 (C2-R31): a pointer change between read and commit writes nothing.
    @Test
    fun pointerChangeBetweenReadAndCommitWritesNothing() {
        groundedReading()
        newReading("a-r2", listOf("Due 03.03.2025", "Total EUR 12.50"), move = false)
        assertSuperseded(HookedGate(host) { rows.moveCurrentReading("a", "a-r2") })
        assertNotNullDue("a-r2")
    }

    // T2 (C2-R31): the Source leaving READY between read and commit writes nothing.
    @Test
    fun deletingSourceBetweenReadAndCommitWritesNothing() {
        groundedReading()
        assertSuperseded(HookedGate(host) { rows.setState("a", "DELETING") })
    }

    /**
     * C2-R31 fingerprint, the per-fact pointers: a clarification moves one fact's current revision while the Source
     * reading does not change. The reading-level fingerprint cannot see this, so only the pointer set can.
     */
    @Test
    fun factPointerChangeBetweenReadAndCommitWritesNothing() {
        groundedReading()
        finished(extract(trigger = ExtractionTrigger.AUTO))
        val runsBefore = runRowCount()
        val date = host.query("SELECT id, currentRevisionId FROM facts WHERE sourceId = 'a' AND kind = 'DATE'").single()
        val factId = date[0] as String
        val currentRevision = date[1] as String

        val result = runBlocking {
            core(
                HookedGate(host) {
                    rows.revision("u-hook", factId, origin = "USER", status = "CONFIRMED", supersedes = currentRevision)
                    rows.current(factId, "u-hook", expected = currentRevision)
                },
            ).extract("a", ExtractionTrigger.USER)
        }

        assertEquals("$result", FactExtractionResult.Superseded, result)
        assertEquals("no run row is added", runsBefore, runRowCount())
    }

    private fun assertSuperseded(gate: ExtractionGate) {
        val result = runBlocking { core(gate).extract("a", ExtractionTrigger.AUTO) }
        assertEquals("$result", FactExtractionResult.Superseded, result)
        assertEquals(0L, factCount())
        assertEquals(0L, runRowCount())
    }

    /** The automatic run is still due after a superseded attempt (C2-R30). Only the READY case is due. */
    private fun assertNotNullDue(readingId: String) {
        assertEquals(readingId, host.query("SELECT currentOcrRevisionId FROM sources WHERE id = 'a'").single().single())
        val state = host.query("SELECT state FROM sources WHERE id = 'a'").single().single()
        val runs = runRowCount()
        val facts = count("SELECT COUNT(*) FROM facts WHERE sourceId = 'a'")
        val reading = runBlocking { dao.readingOfSource("a") }
        val dueParts = "reading=${reading?.id} runsRows=${host.query("SELECT * FROM fact_extraction_runs")} " +
            "revs=${host.query("SELECT id, origin FROM fact_revisions")} extracted=${runBlocking { dao.extractedCountOfSource("a") }}"
        assertNotNull("still due: state=$state runs=$runs facts=$facts $dueParts", runBlocking { core().extractIfDue("a") })
    }

    // T3 (C2-R57): a kind over 64 candidates is skipped and reported; the other kinds commit.
    @Test
    fun kindOver64IsSkippedOthersCommit() {
        bigReading(dateCount = 65)

        val result = finished(extract(trigger = ExtractionTrigger.AUTO))

        assertEquals(setOf(ExtractedKind.DATE), result.skippedKinds)
        assertEquals(66, result.candidateCount)
        assertEquals(1, result.createdRevisionCount)
        assertEquals(listOf<Any?>("DATE", 66L), host.query("SELECT skippedKinds, candidateCount FROM fact_extraction_runs").single())
        assertEquals(0L, count("SELECT COUNT(*) FROM facts WHERE sourceId = 'a' AND kind = 'DATE'"))
        assertEquals(1L, count("SELECT COUNT(*) FROM facts WHERE sourceId = 'a' AND kind = 'EMAIL'"))
    }

    // T3 (C2-R57): exactly 64 candidates of a kind are all written.
    @Test
    fun kindAtExactly64IsCommittedInFull() {
        bigReading(dateCount = 64)

        val result = finished(extract(trigger = ExtractionTrigger.AUTO))

        assertEquals(emptySet<ExtractedKind>(), result.skippedKinds)
        assertEquals(64L, count("SELECT COUNT(*) FROM facts WHERE sourceId = 'a' AND kind = 'DATE'"))
        assertEquals(1L, count("SELECT COUNT(*) FROM facts WHERE sourceId = 'a' AND kind = 'EMAIL'"))
    }

    /** A new current reading `a-big` with [dateCount] DATE spans and one EMAIL span, all grounded and LATIN. */
    private fun bigReading(dateCount: Int) {
        groundedReading()
        fixture.revision("a-big", "a")
        for (i in 0 until dateCount) {
            fixture.span("a-big-d$i", "a-big", i, "Due %02d.%02d.2024".format(1 + i % 28, 1 + i / 28))
        }
        fixture.span("a-big-m", "a-big", dateCount, "Mail a@example.org")
        grounded("a-big")
        rows.moveCurrentReading("a", "a-big")
    }
}

/** Cancels the running job inside the commit, then suspends: a cancellable commit would stop here. */
private class CancellingGate(host: HostSqlite, private val cancel: () -> Unit) : TransactionalHostGate(host) {
    override suspend fun <T> commit(block: suspend () -> T): T {
        cancel()
        yield()
        return transaction(block)
    }
}
