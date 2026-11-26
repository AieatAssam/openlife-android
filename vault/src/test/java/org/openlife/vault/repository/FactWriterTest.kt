package org.openlife.vault.repository

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactLimits
import org.openlife.vault.facts.FactRefusal
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.ReviewDecision
import org.openlife.vault.facts.StaleReason
import org.openlife.vault.storage.FactHost
import org.openlife.vault.storage.FactRows
import org.openlife.vault.storage.HostFactDao
import org.openlife.vault.storage.HostSqlite

/**
 * P3-02-R5 on host SQLite: the transactional bodies of FactRepository's
 * writes and the stale traversal, run against the production SQL and
 * triggers. FactRepositoryTest runs the same cases through Room and
 * SQLCipher on a device (gap until a device run).
 */
class FactWriterTest {
    private val host = HostSqlite()
    private val rows = FactRows(host)
    private val dao = HostFactDao(host)
    private var now = 50_000L
    private val writer = FactWriter(dao, clock = { now++ })
    private val marker = FactStaleMarker(dao, clock = { now++ })

    @After
    fun close() = host.close()

    private val date = FactValue("{\"date\":\"2026-11-27\"}", "2026-11-27")

    private fun status(id: String) = host.query(
        "SELECT status, staleReason FROM fact_revisions WHERE id = ?",
        id,
    ).single()

    private fun epoch(sourceId: String) = host.long("SELECT factInputEpoch FROM sources WHERE id = ?", sourceId)

    /** Source `a`: fact `f` with EXTRACTED `x` (evidence on a-s1) current; fact `g` with `y` on a-s2. */
    private fun extracted() {
        val fixture = FactHost.fresh(host)
        rows.readySourceWithReading(fixture, "a")
        rows.fact("f", "a")
        rows.revision("x", "f")
        rows.current("f", "x")
        rows.evidence("e1", "x", "a", "a-s1")
        rows.fact("g", "a")
        rows.revision("y", "g")
        rows.current("g", "y")
        rows.evidence("e2", "y", "a", "a-s2")
    }

    private fun written(result: FactWrite): FactWrite.Written {
        assertTrue("$result", result is FactWrite.Written)
        return result as FactWrite.Written
    }

    @Test
    fun basisEdgeMustPointToAnOlderRevisionOfTheSameFact() = runBlocking {
        extracted()
        val first = written(writer.correct("x", date))
        val second = written(writer.correct(first.revisionId, FactValue("{\"date\":\"2026-11-28\"}", "2026-11-28")))
        listOf(first, second).forEach {
            assertEquals("the basis is the extracted revision", "x", dao.basisOf(it.revisionId))
            assertEquals(listOf<Any?>("CONFIRMED", null), status(it.revisionId))
            assertEquals(
                listOf(listOf<Any?>(1L, "CONFIRM")),
                host.query(
                    "SELECT sequence, decision FROM review_events WHERE revisionId = ?",
                    it.revisionId,
                ),
            )
        }
        assertEquals(second.revisionId, dao.findFact("f")!!.currentRevisionId)
        assertEquals(
            "superseded revisions are read-only history",
            FactWrite.Refused(FactRefusal.NOT_ALLOWED),
            writer.review("x", ReviewDecision.CONFIRM),
        )
    }

    @Test
    fun correctionOnSpanMarksDependentRevisionsStale() = runBlocking {
        extracted()
        val corrected = written(writer.correct("x", date))
        val before = epoch("a")
        assertEquals(2, marker.markForSpan("a-s1", StaleReason.SPAN_CORRECTED))
        assertEquals(listOf<Any?>("STALE", "SPAN_CORRECTED"), status("x"))
        assertEquals(listOf<Any?>("STALE", "BASIS_STALE"), status(corrected.revisionId))
        assertEquals(listOf<Any?>("UNREVIEWED", null), status("y"))
        assertEquals(before + 1, epoch("a"))
        assertEquals(
            "STALE is terminal; the epoch still moves",
            0,
            marker.markForSpan("a-s1", StaleReason.SPAN_CORRECTED),
        )
        assertEquals(before + 2, epoch("a"))
        assertEquals(
            FactWrite.Refused(FactRefusal.NOT_ALLOWED),
            writer.correct(corrected.revisionId, date),
        )
        assertEquals(1, marker.markForReading("a-r", StaleReason.READING_REPLACED))
        assertEquals(listOf<Any?>("STALE", "READING_REPLACED"), status("y"))
    }

    @Test
    fun reviewFollowsTheStatusTable() = runBlocking {
        extracted()
        val x = "x"
        assertEquals(FactWrite.Written("f", x), writer.review(x, ReviewDecision.CONFIRM))
        assertEquals(listOf<Any?>("CONFIRMED", null), status("x"))
        writer.review(x, ReviewDecision.REJECT)
        assertEquals(listOf<Any?>("REJECTED", null), status("x"))
        writer.review(x, ReviewDecision.REOPEN)
        assertEquals("REOPEN returns to the initial status", listOf<Any?>("UNREVIEWED", null), status("x"))
        assertEquals(FactWrite.Refused(FactRefusal.NOT_ALLOWED), writer.review(x, ReviewDecision.REOPEN))
        assertEquals(
            listOf(1L, 2L, 3L),
            host.query(
                "SELECT sequence FROM review_events WHERE revisionId = ? ORDER BY sequence",
                "x",
            ).map { it.single() },
        )
        rows.fact("h", "a")
        rows.revision("z", "h")
        rows.current("h", "z")
        assertEquals(
            "CONFIRM needs a VALUE row",
            FactWrite.Refused(FactRefusal.NOT_ALLOWED),
            writer.review("z", ReviewDecision.CONFIRM),
        )
    }

    @Test
    fun clarificationChoosesOneCandidateByteForByte() = runBlocking {
        val fixture = FactHost.fresh(host)
        rows.readySourceWithReading(fixture, "a")
        rows.fact("f", "a")
        val candidates = "{\"candidates\":[{\"date\":\"2026-03-04\"},{\"date\":\"2026-04-03\"}]}"
        rows.revision(
            "x",
            "f",
            status = "CLARIFICATION_NEEDED",
            valueJson = candidates,
            canonicalText = "2026-03-04 | 2026-04-03",
        )
        rows.current("f", "x")
        rows.evidence("e1", "x", "a", "a-s1")
        val x = "x"
        assertEquals(FactWrite.Refused(FactRefusal.NOT_ALLOWED), writer.review(x, ReviewDecision.CONFIRM))
        assertEquals(FactWrite.Refused(FactRefusal.INVALID_VALUE), writer.clarify(x, date))
        val chosen = written(writer.clarify(x, FactValue("{\"date\":\"2026-04-03\"}", "2026-04-03")))
        val again = written(writer.clarify(chosen.revisionId, FactValue("{\"date\":\"2026-03-04\"}", "2026-03-04")))
        assertEquals("x", dao.basisOf(again.revisionId))
        assertEquals("user:clarify", dao.findRevision(again.revisionId)!!.methodId)
    }

    @Test
    fun userFactsAreConfirmedWithoutEvidenceAndBounded() = runBlocking {
        val fixture = FactHost.fresh(host)
        rows.readySourceWithReading(fixture, "a")
        fixture.source("s", state = "STAGED")
        val added = written(writer.addUserFact("a", FactKind.DATE, date))
        val revision = dao.findRevision(added.revisionId)!!
        assertEquals(
            listOf("USER", "CONFIRMED", "user:add"),
            listOf(revision.origin, revision.status, revision.methodId),
        )
        assertEquals(0, dao.countValueEvidence(revision.id))
        assertEquals(1L, host.long("SELECT ordinal FROM facts WHERE id = ?", added.factId))
        assertEquals(FactWrite.Refused(FactRefusal.SOURCE_NOT_READY), writer.addUserFact("s", FactKind.DATE, date))
        assertEquals(
            FactWrite.Refused(FactRefusal.INVALID_VALUE),
            writer.addUserFact("a", FactKind.DATE, FactValue("x", "x")),
        )
        assertEquals(FactWrite.Refused(FactRefusal.INVALID_VALUE), writer.addUserFact("a", FactKind.UNRECOGNISED, date))
        repeat(FactLimits.MAX_CURRENT_REVISIONS - 1) { writer.addUserFact("a", FactKind.DATE, date) }
        assertEquals(FactLimits.MAX_CURRENT_REVISIONS, dao.countCurrentRevisions("a"))
        assertEquals(FactWrite.Refused(FactRefusal.LIMIT_REACHED), writer.addUserFact("a", FactKind.DATE, date))
    }

    @Test
    fun clarificationIsRefusedWhenTheCandidateTextsDoNotLineUp() = runBlocking {
        val fixture = FactHost.fresh(host)
        rows.readySourceWithReading(fixture, "a")
        rows.fact("f", "a", kind = "URL")
        val first = "{\"normalized\":\"https://a.example/x | y\",\"raw\":\"a.example/x | y\"}"
        val second = "{\"normalized\":\"https://b.example/\",\"raw\":\"b.example\"}"
        rows.revision(
            "x",
            "f",
            kind = "URL",
            status = "CLARIFICATION_NEEDED",
            valueJson = "{\"candidates\":[$first,$second]}",
            canonicalText = "https://a.example/x | y | https://b.example/",
        )
        rows.current("f", "x")
        rows.evidence("e1", "x", "a", "a-s1")
        assertEquals(
            "three texts for two candidates: no text can be matched to its candidate (M-08)",
            FactWrite.Refused(FactRefusal.INVALID_VALUE),
            writer.clarify("x", FactValue(first, "anything at all")),
        )
        assertEquals(
            FactWrite.Refused(FactRefusal.INVALID_VALUE),
            writer.clarify("x", FactValue(second, "https://b.example/")),
        )
    }

    @Test
    fun userValuesMustHaveTheShapeOfTheirKind() = runBlocking {
        val fixture = FactHost.fresh(host)
        rows.readySourceWithReading(fixture, "a")
        val invalid = FactWrite.Refused(FactRefusal.INVALID_VALUE)
        listOf(
            FactKind.DATE to FactValue("{\"time\":\"10:00\"}", "10:00"),
            FactKind.DATE to FactValue("{\"date\":\"2026-02-30\"}", "2026-02-30"),
            FactKind.DATE to FactValue("{\"date\":\"2026-11-27\"}", "2026-11-28"),
            FactKind.DATE to FactValue("{\"date\": \"2026-11-27\"}", "2026-11-27"),
            FactKind.TIME to FactValue("{\"time\":\"25:00\"}", "25:00"),
            FactKind.DATETIME to FactValue("{\"date\":\"2026-11-27\"}", "2026-11-27"),
            FactKind.MONEY to FactValue("{\"date\":\"2026-11-27\"}", "2026-11-27"),
            FactKind.URL to FactValue("{\"amount\":\"1.00\",\"currency\":null,\"rawSymbol\":null}", "1.00"),
        ).forEach { (kind, value) -> assertEquals("$kind", invalid, writer.addUserFact("a", kind, value)) }
        listOf(
            FactKind.DATE to FactValue("{\"date\":\"2026-11-27\"}", "2026-11-27"),
            FactKind.TIME to FactValue("{\"time\":\"14:30\"}", "14:30"),
            FactKind.TIME to FactValue("{\"time\":\"14:30:05\"}", "14:30:05"),
            FactKind.DATETIME to FactValue("{\"date\":\"2026-11-27\",\"time\":\"14:30\"}", "2026-11-27 14:30"),
        ).forEach { (kind, value) -> written(writer.addUserFact("a", kind, value)) }
        extractedOn("f2")
        assertEquals("a correction keeps the kind", invalid, writer.correct("x2", FactValue("{\"time\":\"10:00\"}", "10:00")))
    }

    private fun extractedOn(factId: String) {
        rows.fact(factId, "a")
        rows.revision("x2", factId)
        rows.current(factId, "x2")
        rows.evidence("e2", "x2", "a", "a-s2")
    }
}
