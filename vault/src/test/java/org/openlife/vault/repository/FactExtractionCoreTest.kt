package org.openlife.vault.repository

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.facts.ExtractionOutcome
import org.openlife.vault.facts.ExtractionTrigger
import org.openlife.vault.facts.FactExtractionResult
import org.openlife.vault.facts.FactLimits
import org.openlife.vault.facts.StaleReason
import org.openlife.vault.search.SearchFixture
import org.openlife.vault.storage.FactHost
import org.openlife.vault.storage.FactRows
import org.openlife.vault.storage.HostFactDao
import org.openlife.vault.storage.HostSqlite

/**
 * P3-04 on host SQLite: the eight cases of P3-04-R1..R6, through the production
 * SQL, triggers and FactStaleMarker. FactExtractionRepositoryTest runs the same
 * cases through Room and SQLCipher; that class is a device gap until it runs.
 */
class FactExtractionCoreTest {
    private val host = HostSqlite()
    private val rows = FactRows(host)
    private val dao = HostFactDao(host)
    private var now = 90_000L
    private var ids = 0
    private var fixture: SearchFixture? = null

    private fun core(deadlineMillis: Long = FactLimits.DEADLINE_MILLIS) = FactExtractionCore(
        dao = dao,
        gate = TransactionalHostGate(host),
        clock = { now++ },
        newId = { "id-${ids++}" },
        deadlineMillis = deadlineMillis,
    )

    @After
    fun close() = host.close()

    /** Source `a` with a READY, current reading `a-r`: spans `a-s1` "Due 26.11.2026" and `a-s2` "Total EUR 12.50". */
    private fun groundedReading() {
        val f = FactHost.fresh(host)
        fixture = f
        rows.readySourceWithReading(f, "a")
        // The fixture leaves the eligibility columns at their defaults; grounded and LATIN is the real case.
        host.exec("UPDATE ocr_spans SET hasEvidenceRegion = 1, scriptStatus = 'LATIN' WHERE revisionId = 'a-r'")
    }

    private fun extract(trigger: ExtractionTrigger = ExtractionTrigger.AUTO, source: String = "a") = runBlocking {
        core().extract(source, trigger)
    }

    private fun count(sql: String): Long = host.long(sql)

    private fun factCount() = count("SELECT COUNT(*) FROM facts WHERE sourceId = 'a'")

    private fun runOutcomes() = host.query("SELECT outcome FROM fact_extraction_runs").map { it.single() }

    private fun finished(result: FactExtractionResult): FactExtractionResult.Finished {
        assertTrue("$result", result is FactExtractionResult.Finished)
        return result as FactExtractionResult.Finished
    }

    @Test
    fun extractsFactsWithEvidenceLinksFromReadySpans() {
        groundedReading()
        val result = finished(extract())
        assertEquals(ExtractionOutcome.COMPLETED, result.outcome)
        assertEquals(2L, factCount())
        val evidence = host.query(
            "SELECT f.kind, e.ocrSpanId, s.text, e.textStart, e.textEnd, r.rawText, r.origin " +
                "FROM fact_evidence e JOIN fact_revisions r ON r.id = e.revisionId JOIN facts f ON f.id = r.factId " +
                "JOIN ocr_spans s ON s.id = e.ocrSpanId WHERE e.role = 'VALUE' ORDER BY f.kind",
        )
        assertEquals(setOf("DATE", "MONEY"), evidence.map { it[0] as String }.toSet())
        for (row in evidence) {
            val (start, end) = (row[3] as Long).toInt() to (row[4] as Long).toInt()
            val text = row[2] as String
            assertEquals("the evidence slices the span text to the raw text", row[5], text.substring(start, end))
            assertEquals("EXTRACTED", row[6])
        }
    }

    @Test
    fun rejectedOcrRevisionYieldsNoFacts() {
        groundedReading()
        host.exec("UPDATE ocr_revisions SET reviewState = 'REJECTED' WHERE id = 'a-r'")
        assertEquals(
            ExtractionOutcome.READING_REJECTED,
            finished(extract()).outcome,
        )
        assertEquals(0L, factCount())
        assertEquals(listOf<Any?>("READING_REJECTED"), runOutcomes())
    }

    @Test
    fun rerunIsIdempotentForUnchangedRevision() {
        groundedReading()
        finished(extract())
        val revisionsAfterFirst = count("SELECT COUNT(*) FROM fact_revisions")
        val second = finished(extract())
        assertEquals(0, second.createdRevisionCount)
        assertEquals(2L, factCount())
        assertEquals(revisionsAfterFirst, count("SELECT COUNT(*) FROM fact_revisions"))
    }

    /**
     * P3-05-R2 item 3 (FactRepository.findAgain): a USER-triggered run reads the current reading only; an older
     * READY reading of the same source contributes no evidence, and the run is recorded against the current one.
     */
    @Test
    fun findAgainRunsOnTheCurrentReadingAndNeverOnAnOlderOne() {
        groundedReading()
        val f = fixture!!
        f.revision("a-old", "a")
        f.span("a-old-s1", "a-old", 0, "Due 26.11.2026")
        host.exec("UPDATE ocr_spans SET hasEvidenceRegion = 1, scriptStatus = 'LATIN' WHERE revisionId = 'a-old'")
        assertEquals(ExtractionOutcome.COMPLETED, finished(extract(ExtractionTrigger.USER)).outcome)
        assertEquals(2L, factCount())
        assertEquals(
            "the older reading has no evidence",
            0L,
            count("SELECT COUNT(*) FROM fact_evidence WHERE ocrSpanId = 'a-old-s1'"),
        )
        assertEquals(
            "the evidence names the current reading's spans",
            2L,
            count("SELECT COUNT(DISTINCT ocrSpanId) FROM fact_evidence WHERE ocrSpanId IN ('a-s1', 'a-s2')"),
        )
        assertEquals(
            listOf(listOf<Any?>("a-r", "USER")),
            host.query("SELECT ocrRevisionId, `trigger` FROM fact_extraction_runs"),
        )
    }

    @Test
    fun newOcrRevisionMarksPreviousFactsStale() {
        groundedReading()
        finished(extract())
        // P3-04-R5: the OCR pipeline marks the previous reading's facts when a newer reading becomes current.
        runBlocking { FactStaleMarker(dao, clock = { now++ }).markForReading("a-r", StaleReason.READING_REPLACED) }
        assertEquals(2L, count("SELECT COUNT(*) FROM fact_revisions WHERE status = 'STALE' AND origin = 'EXTRACTED'"))

        val f = fixture!!
        f.revision("a-r2", "a")
        f.span("a-r2-s1", "a-r2", 0, "Due 26.11.2026")
        f.span("a-r2-s2", "a-r2", 1, "Total EUR 12.50")
        host.exec("UPDATE ocr_spans SET hasEvidenceRegion = 1, scriptStatus = 'LATIN' WHERE revisionId = 'a-r2'")
        rows.moveCurrentReading("a", "a-r2")

        val result = finished(extract(ExtractionTrigger.USER))
        assertEquals(2, result.createdRevisionCount)
        assertEquals(2L, factCount())
        val continued = count(
            "SELECT COUNT(*) FROM fact_revisions r JOIN facts f ON f.currentRevisionId = r.id " +
                "WHERE r.supersedesRevisionId IS NOT NULL AND r.status = 'UNREVIEWED' AND f.sourceId = 'a'",
        )
        // C2-R33: each stale fact continues with a new current revision.
        assertEquals(2L, continued)
    }

    @Test
    fun limitOverflowCommitsNothing() {
        groundedReading()
        // 499 current revisions, so two new facts reach 501 (C2-R62).
        for (i in 1..499) {
            rows.fact("f$i", "a")
            rows.revision("r$i", "f$i", origin = "USER", status = "CONFIRMED")
            rows.reviewEvent("e$i", "r$i")
            rows.current("f$i", "r$i")
        }
        val result = finished(extract())
        assertEquals(ExtractionOutcome.LIMIT_EXCEEDED, result.outcome)
        assertEquals(499L, factCount())
        assertEquals(499L, count("SELECT COUNT(*) FROM fact_revisions WHERE origin = 'USER'"))
        assertEquals(listOf<Any?>("LIMIT_EXCEEDED"), runOutcomes())
    }

    @Test
    fun deadlineProducesFailedResultAndNoPartialRows() {
        groundedReading()
        val result = finished(runBlocking { core(deadlineMillis = 0).extract("a", ExtractionTrigger.AUTO) })
        assertEquals(ExtractionOutcome.TIMEOUT, result.outcome)
        assertEquals(0L, factCount())
        assertEquals(0L, count("SELECT COUNT(*) FROM fact_revisions"))
        assertEquals(0L, count("SELECT COUNT(*) FROM fact_evidence"))
        assertEquals(listOf<Any?>("TIMEOUT"), runOutcomes())
    }

    @Test
    fun ungroundedAndOutOfScriptSpansYieldNoFacts() {
        groundedReading()
        host.exec("UPDATE ocr_spans SET hasEvidenceRegion = 0 WHERE id = 'a-s1'")
        host.exec("UPDATE ocr_spans SET scriptStatus = 'UNSUPPORTED' WHERE id = 'a-s2'")
        assertEquals(ExtractionOutcome.COMPLETED, finished(extract()).outcome)
        assertEquals(0L, factCount())
    }

    @Test
    fun usesCurrentRevisionPointerAndCorrectionChain() {
        groundedReading()
        rows.correction("c1", "a-r", "a-s2", "Total EUR 1.00", null)
        rows.correction("c2", "a-r", "a-s2", "Total EUR 20.00", "c1")
        // A newer reading that is not current must not be read, even though it has the latest createdAt.
        val f = fixture!!
        f.revision("a-r0", "a")
        f.span("a-r0-s1", "a-r0", 0, "Total EUR 99.00")
        host.exec("UPDATE ocr_spans SET hasEvidenceRegion = 1, scriptStatus = 'LATIN' WHERE revisionId = 'a-r0'")

        finished(extract())
        val canonical = host.query(
            "SELECT canonicalText, methodId FROM fact_revisions WHERE origin = 'EXTRACTED' ORDER BY canonicalText",
        )
        val corrected = canonical.any {
            (it[0] as String).contains("20.00") && (it[1] as String).endsWith("+correction")
        }
        assertTrue("$canonical", corrected)
        assertTrue(
            "the superseded correction is not read: $canonical",
            canonical.none { (it[0] as String).contains("1.00") },
        )
        assertTrue(
            "the non-current reading is not read: $canonical",
            canonical.none { (it[0] as String).contains("99.00") },
        )
    }
}
