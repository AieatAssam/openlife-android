package org.openlife.vault.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C2-R61 / F-70 boundary (P3-02 review M-09): [SourceRowRemoval] is the only
 * code that deletes rows of a Source's fact and OCR tables. The other
 * removers are the STAGED cleanup (`SourceDao.deleteStaged`, a Source with no
 * dependent rows), the C2 triggers (edges and FTS rows) and the vault reset,
 * which wipes the database files and runs no SQL. Reads the sources of the
 * vault and app modules; run with `--rerun` after a comment-only change,
 * since Gradle does not track these files as the test's inputs.
 */
class SourceRowRemovalBoundaryTest {
    private val roots = listOf(File("src/main/java"), File("../app/src/main/java"))

    private fun sources(): List<File> = roots.flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun onlyTheRoutineAndStagedCleanupRemoveSourceRows() {
        val main = File("src/main/java/org/openlife/vault")
        val files = sources()
        assertEquals(
            "nothing calls deleteById directly",
            emptyList<String>(),
            files.filter { it.readText().contains(".deleteById(") }.map { it.name },
        )
        assertEquals(
            "deleteById is handed only to SourceRowRemoval as step 7",
            setOf("DeletionRepository.kt", "RecoveryRepository.kt"),
            files.filter { it.readText().contains("::deleteById") }.map { it.name }.toSet(),
        )
        listOf("repository/DeletionRepository.kt", "repository/RecoveryRepository.kt").forEach { path ->
            assertTrue(
                "$path removes rows through SourceRowRemoval",
                File(main, path).readText().contains("SourceRowRemoval.remove("),
            )
        }
        val deletes = files
            .filter { Regex("DELETE FROM `?sources`?").containsMatchIn(it.readText()) }.map { it.name }.toSet()
        assertEquals(setOf("SourceRowRemoval.kt", "SourceDao.kt"), deletes)
        assertTrue(File(main, "storage/SourceDao.kt").readText().contains("state = 'STAGED'"))
    }

    @Test
    fun noOtherCodeDeletesFactOrOcrRows() {
        val table = TABLES.joinToString("|")
        val delete = Regex("DELETE\\s+FROM\\s+`?($table)`?\\b", RegexOption.IGNORE_CASE)
        val offenders = sources().filter { it.name !in ALLOWED }.flatMap { file ->
            val text = file.readText()
            val statements = delete.findAll(text).map { "${file.name}: ${it.value}" }.toList()
            val annotations = if (text.contains("@Delete")) listOf("${file.name}: @Delete") else emptyList()
            statements + annotations
        }
        assertEquals("only SourceRowRemoval deletes these rows (C2-R61)", emptyList<String>(), offenders)
    }

    private companion object {
        /** The routine itself; the triggers delete only `dependency_edges` and FTS rows. */
        val ALLOWED = setOf("SourceRowRemoval.kt", "FactSchemaTriggers.kt")

        val TABLES = listOf(
            "facts", "fact_revisions", "fact_evidence", "review_events", "fact_extraction_runs",
            "dependency_edges", "ocr_revisions", "ocr_spans", "ocr_user_revisions", "ocr_span_reviews",
        )
    }
}
