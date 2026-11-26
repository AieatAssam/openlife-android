package org.openlife.vault.storage

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * C2-R43 / C2-R61 (finding F-70) on host SQLite: one routine removes a
 * Source's rows for both DeletionRepository and RecoveryRepository's
 * DELETING branch, with the same result whether `PRAGMA foreign_keys` is on
 * or off (SourceRowRemovalBoundaryTest proves it is the only remover).
 */
class SourceRowRemovalTest {
    private val hosts = mutableListOf<HostSqlite>()

    @After
    fun close() = hosts.forEach(HostSqlite::close)

    /** Source `a` with every C2 and OCR row kind; Source `b` alongside, which must survive untouched. */
    private fun populated(recursive: Boolean = true, migrated: Boolean = false): Pair<HostSqlite, FactRows> {
        val host = HostSqlite(recursiveTriggers = recursive).also { hosts += it }
        val fixture = if (migrated) FactHost.migrated(host, 5) else FactHost.fresh(host)
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
        // M-01/M-05: every combination of foreign keys, recursive triggers and
        // a fresh or a migrated v6 database ends with the same rows.
        val modes = listOf(true, false).flatMap { fk ->
            listOf(true, false).flatMap { rec -> listOf(false, true).map { mig -> Triple(fk, rec, mig) } }
        }
        val results = modes.map { (foreignKeys, recursive, migrated) ->
            val (host, rows) = populated(recursive, migrated)
            host.exec("PRAGMA foreign_keys = ${if (foreignKeys) "ON" else "OFF"}")
            assertEquals(if (foreignKeys) 1L else 0L, host.long("PRAGMA foreign_keys"))
            rows.setState("a", "DELETING")
            val dao = HostSourceRowRemovalDao(host)
            host.exec("SAVEPOINT removal")
            SourceRowRemoval.remove(dao, "a", dao::deleteSource)
            host.exec("RELEASE removal")
            assertEquals("whole-set statements in C2-R43 order", ORDER, dao.executed)
            TABLES.forEach { table ->
                val left = host.query(
                    "SELECT * FROM $table",
                ).flatten().filterIsInstance<String>().filter { it.startsWith("a") }
                val mode = "fk=$foreignKeys, rec=$recursive, mig=$migrated"
                assertEquals("$table keeps nothing of source a ($mode)", emptyList<String>(), left)
            }
            assertEquals(
                0L,
                host.long(
                    "SELECT COUNT(*) FROM dependency_edges WHERE dependentSourceId = 'a' OR dependencySourceId = 'a'",
                ),
            )
            assertEquals(
                "source b's two spans stay indexed",
                2L,
                host.long("SELECT COUNT(*) FROM ocr_text_fts_docsize"),
            )
            snapshot(rows)
        }
        results.forEachIndexed { i, result ->
            assertEquals("mode ${modes[i]} gives the same database", results[0], result)
        }
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
        // F-70 regression: the pre-C2 order (readings by cascade, then the
        // Source) is refused even on a DELETING Source: by the C2-R20 guard
        // when the cascade reaches a superseded correction whose reading is
        // already gone, and otherwise by the NO ACTION evidence -> span key.
        val rows = FactRows(host)
        rows.setState("a", "DELETING")
        listOf(
            "DELETE FROM ocr_revisions WHERE sourceId = 'a'" to "C2-R20",
            "DELETE FROM ocr_user_revisions WHERE id = 'a-c2'" to null,
            "DELETE FROM ocr_user_revisions WHERE id = 'a-c1'" to null,
            "DELETE FROM ocr_revisions WHERE sourceId = 'a'" to "FOREIGN KEY",
        ).forEach { (sql, refusal) ->
            val failure = runCatching { host.exec(sql) }.exceptionOrNull()
            if (refusal == null) {
                assertEquals(null, failure)
            } else {
                assertTrue("$sql: $failure", failure?.message.orEmpty().contains(refusal))
            }
        }
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
