package org.openlife.vault.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** VS-2, C2-R18 on host SQLite: edges are trigger-written and never cross a Source. */
class DependencyEdgeTest {
    private val host = HostSqlite()
    private val rows = FactRows(host)

    @After
    fun close() = host.close()

    private fun refused(requirement: String, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal citing $requirement")
        } catch (e: HostSqlite.Failure) {
            assertTrue("${e.message} should cite $requirement", e.message.orEmpty().contains(requirement))
        }
    }

    private fun edge(kind: String, dependent: String, dependentSource: String, dependencyKind: String, dependency: String, dependencySource: String) =
        host.exec(
            "INSERT INTO dependency_edges(edgeKind, dependentKind, dependentId, dependentSourceId, dependencyKind, " +
                "dependencyId, dependencySourceId) VALUES (?, 'FACT_REVISION', ?, ?, ?, ?, ?)",
            kind, dependent, dependentSource, dependencyKind, dependency, dependencySource,
        )

    private fun edges(): List<List<Any?>> = host.query(
        "SELECT edgeKind, dependentId, dependencyKind, dependencyId FROM dependency_edges ORDER BY edgeKind, dependentId",
    )

    @Test
    fun crossSourceEdgeIsRefusedByTrigger() {
        val fixture = FactHost.fresh(host)
        rows.readySourceWithReading(fixture, "a")
        rows.readySourceWithReading(fixture, "b")
        rows.fact("f", "a")
        rows.revision("x", "f")
        rows.current("f", "x")
        refused("C2-R18") { edge("EVIDENCE", "x", "a", "OCR_SPAN", "b-s1", "b") }
        refused("C2-R18") { rows.evidence("e0", "x", "a", "b-s1") }
        assertEquals(0L, host.long("SELECT COUNT(*) FROM dependency_edges"))
        rows.evidence("e1", "x", "a", "a-s1")
        assertEquals("evidence insert writes its edge", listOf(listOf<Any?>("EVIDENCE", "x", "OCR_SPAN", "a-s1")), edges())
        refused("C2-R18") { host.exec("UPDATE dependency_edges SET dependencyId = 'a-s2'") }
    }

    @Test
    fun basisEdgeMustPointToAnOlderNonUserRevisionOfTheSameFact() {
        val fixture = FactHost.fresh(host)
        rows.readySourceWithReading(fixture, "a")
        rows.fact("f", "a")
        rows.fact("g", "a")
        rows.revision("x", "f")
        rows.current("f", "x")
        rows.revision("y", "g")
        rows.current("g", "y")
        rows.revision("u1", "f", origin = "USER", status = "CONFIRMED", supersedes = "x")
        rows.revision("u2", "f", origin = "USER", status = "CONFIRMED", supersedes = "u1")
        assertEquals(
            "a USER revision's basis is the nearest non-USER revision of its chain",
            listOf(listOf<Any?>("BASIS", "u1", "FACT_REVISION", "x"), listOf<Any?>("BASIS", "u2", "FACT_REVISION", "x")),
            edges(),
        )
        refused("C2-R18") { edge("BASIS", "x", "a", "FACT_REVISION", "u1", "a") }
        refused("C2-R18") { edge("BASIS", "u1", "a", "FACT_REVISION", "y", "a") }
        refused("C2-R18") { edge("INPUT", "u1", "a", "FACT_REVISION", "x", "a") }
        refused("C2 §4.2") { rows.revision("z", "f", origin = "USER", status = "CONFIRMED", supersedes = "y") }
    }

    @Test
    fun deletingEvidenceRemovesItsEdgeOnlyWhenNoOtherRowNamesTheSpan() {
        val fixture = FactHost.fresh(host)
        rows.readySourceWithReading(fixture, "a")
        rows.fact("f", "a", kind = "MONEY")
        rows.revision("x", "f", kind = "MONEY", valueJson = "{\"amount\":\"12.50\",\"currency\":\"EUR\"}")
        rows.current("f", "x")
        rows.evidence("e1", "x", "a", "a-s2")
        rows.evidence("e2", "x", "a", "a-s2", role = "CURRENCY_CONTEXT")
        rows.evidence("e3", "x", "a", "a-s1", role = "CURRENCY_CONTEXT")
        assertEquals(2L, host.long("SELECT COUNT(*) FROM dependency_edges"))
        rows.setState("a", "DELETING")
        host.exec("DELETE FROM fact_evidence WHERE id = 'e2'")
        assertEquals(2L, host.long("SELECT COUNT(*) FROM dependency_edges"))
        host.exec("DELETE FROM fact_evidence WHERE id IN ('e1', 'e3')")
        assertEquals(0L, host.long("SELECT COUNT(*) FROM dependency_edges"))
    }

    @Test
    fun noKotlinStatementWritesEdges() {
        val writers = File("src/main/java/org/openlife/vault").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("(INSERT( OR IGNORE)? INTO|DELETE FROM|UPDATE) `?dependency_edges").containsMatchIn(it.readText()) }
            .map { it.name }.toSet()
        assertEquals("only triggers write dependency_edges (C2-R18)", setOf("FactSchemaTriggers.kt"), writers)
    }
}
