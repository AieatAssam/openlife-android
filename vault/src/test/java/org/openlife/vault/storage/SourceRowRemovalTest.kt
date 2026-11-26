package org.openlife.vault.storage

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * C2-R43 / C2-R61 (finding F-70) on host SQLite: one routine removes a
 * Source's rows for both DeletionRepository and RecoveryRepository's
 * DELETING branch, with the same result whether `PRAGMA foreign_keys` is on
 * or off, and it is the only remover of a non-STAGED Source row.
 */
class SourceRowRemovalTest {
    private val hosts = mutableListOf<HostSqlite>()

    @After
    fun close() = hosts.forEach(HostSqlite::close)

    /** Source `a` with every C2 and OCR row kind; Source `b` alongside, which must survive untouched. */
    private fun populated(): Pair<HostSqlite, FactRows> {
        val host = HostSqlite().also { hosts += it }
        val fixture = FactHost.fresh(host)
        val rows = FactRows(host)
        listOf("a", "b").forEach { id ->
            rows.readySourceWithReading(fixture, id)
            fixture.review("$id-s1", "ACCEPTED")
            rows.correction("$id-c1", "$id-r", "$id-s1", "Due 27.11.2026", null)
            rows.correction("$id-c2", "$id-r", "$id-s1", "Due 28.11.2026", "$id-c1")
            rows.fact("$id-f", id)
            rows.revision("$id-x", "$id-f")
            rows.current("$id-f", "$id-x")
            rows.evidence("$id-e", "$id-x", id, "$id-s1")
            rows.reviewEvent("$id-v", "$id-x", "REJECT")
            rows.revision("$id-u", "$id-f", origin = "USER", status = "CONFIRMED", supersedes = "$id-x")
            rows.current("$id-f", "$id-u", "$id-x")
            rows.reviewEvent("$id-w", "$id-u")
            rows.run("$id-run", id, "$id-r")
        }
        assertEquals("EVIDENCE and BASIS edges per source", 4L, host.long("SELECT COUNT(*) FROM dependency_edges"))
        return host to rows
    }

    private fun snapshot(rows: FactRows): Map<String, List<List<Any?>>> = TABLES.associateWith { table ->
        rows.dump(table).map { row -> row.map { if (it is ByteArray) it.toList() else it } }
    }

    @Test
    fun deletionAndRecoveryShareOneRoutineWithTheSameResultWithForeignKeysOnAndOff() = runBlocking {
        val results = listOf(true, false).map { foreignKeys ->
            val (host, rows) = populated()
            host.exec("PRAGMA foreign_keys = ${if (foreignKeys) "ON" else "OFF"}")
            assertEquals(if (foreignKeys) 1L else 0L, host.long("PRAGMA foreign_keys"))
            rows.setState("a", "DELETING")
            val dao = HostSourceRowRemovalDao(host)
            host.exec("SAVEPOINT removal")
            SourceRowRemoval.remove(dao, "a", dao::deleteSource)
            host.exec("RELEASE removal")
            assertEquals("whole-set statements in C2-R43 order", ORDER, dao.executed)
            TABLES.forEach { table ->
                val left = host.query("SELECT * FROM $table").flatten().filterIsInstance<String>().filter { it.startsWith("a") }
                assertEquals("$table keeps nothing of source a (fk=$foreignKeys)", emptyList<String>(), left)
            }
            assertEquals(0L, host.long("SELECT COUNT(*) FROM dependency_edges WHERE dependentSourceId = 'a' OR dependencySourceId = 'a'"))
            assertEquals("source b's two spans stay indexed", 2L, host.long("SELECT COUNT(*) FROM ocr_text_fts_docsize"))
            snapshot(rows)
        }
        assertEquals("foreign keys on and off give the same database", results[0], results[1])
        assertTrue(results[0].getValue("sources").single()[0] == "b")
    }

    @Test
    fun theRoutineRefusesASourceThatIsNotDeletingAndTheOldOrderFailsUnderNoAction() = runBlocking {
        val (host, _) = populated()
        val dao = HostSourceRowRemovalDao(host)
        try {
            SourceRowRemoval.remove(dao, "a", dao::deleteSource)
            fail("a READY source is never removed")
        } catch (expected: IllegalStateException) {
            assertEquals(emptyList<String>(), dao.executed)
        }
        // F-70 regression: the pre-C2 order (readings, then the Source) fails
        // on the NO ACTION evidence -> span reference.
        FactRows(host).setState("a", "DELETING")
        try {
            host.exec("DELETE FROM ocr_revisions WHERE sourceId = 'a'")
            fail("NO ACTION from fact_evidence must refuse the pre-C2 order")
        } catch (expected: HostSqlite.Failure) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains("FOREIGN KEY"))
        }
    }

    @Test
    fun onlyTheRoutineAndStagedCleanupRemoveSourceRows() {
        val main = File("src/main/java/org/openlife/vault")
        val files = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertEquals("nothing calls deleteById directly", emptyList<String>(),
            files.filter { it.readText().contains(".deleteById(") }.map { it.name })
        assertEquals("deleteById is handed only to SourceRowRemoval as step 7",
            setOf("DeletionRepository.kt", "RecoveryRepository.kt"),
            files.filter { it.readText().contains("::deleteById") }.map { it.name }.toSet())
        listOf("repository/DeletionRepository.kt", "repository/RecoveryRepository.kt").forEach { path ->
            assertTrue("$path removes rows through SourceRowRemoval", File(main, path).readText().contains("SourceRowRemoval.remove("))
        }
        val deletes = files
            .filter { Regex("DELETE FROM `?sources`?").containsMatchIn(it.readText()) }.map { it.name }.toSet()
        assertEquals(setOf("SourceRowRemoval.kt", "SourceDao.kt"), deletes)
        assertTrue(File(main, "storage/SourceDao.kt").readText().contains("state = 'STAGED'"))
    }

    private companion object {
        val TABLES = listOf(
            "sources", "ocr_revisions", "ocr_spans", "ocr_user_revisions", "ocr_span_reviews", "facts",
            "fact_revisions", "fact_evidence", "review_events", "fact_extraction_runs", "dependency_edges",
        )
        val ORDER = listOf(
            SourceRowRemoval.DELETE_REVIEW_EVENTS, SourceRowRemoval.DELETE_FACT_EVIDENCE,
            SourceRowRemoval.DELETE_FACT_REVISIONS, SourceRowRemoval.DELETE_FACTS,
            SourceRowRemoval.DELETE_EXTRACTION_RUNS, SourceRowRemoval.DELETE_SPAN_REVIEWS,
            SourceRowRemoval.DELETE_CORRECTIONS, SourceRowRemoval.DELETE_SPANS, SourceRowRemoval.DELETE_READINGS,
            SourceRowRemoval.DELETE_SOURCE,
        )
    }
}
