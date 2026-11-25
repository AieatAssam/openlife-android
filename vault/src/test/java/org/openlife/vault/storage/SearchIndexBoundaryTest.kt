package org.openlife.vault.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-08-R8 (VS-2): the FTS tables are written only by the triggers that
 * SearchIndex creates. No other main source names an FTS table in a write,
 * DeletionRepository has no FTS statement, and nothing runs VACUUM (which can
 * renumber the implicit rowids the external-content index is keyed on).
 */
class SearchIndexBoundaryTest {
    private val sources = listOf(File("src/main/java"), File("../app/src/main/java"))
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test
    fun onlySearchIndexWritesFtsRows() {
        val write = Regex("(INSERT|DELETE|UPDATE|REPLACE)[^\\n]*(ocr_text_fts|ocr_correction_fts)", RegexOption.IGNORE_CASE)
        val writers = sources.filter { write.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(listOf("SearchIndex.kt"), writers)
    }

    @Test
    fun deletionRepositoryHasNoFtsStatement() {
        val deletion = sources.single { it.name == "DeletionRepository.kt" }.readText()
        assertTrue(!deletion.contains("fts", ignoreCase = true))
    }

    @Test
    fun nothingRunsVacuum() {
        val vacuum = Regex("\\bVACUUM\\b", RegexOption.IGNORE_CASE)
        assertEquals(emptyList<String>(), sources.filter { vacuum.containsMatchIn(it.readText().lines().filterNot { l -> l.trim().startsWith("*") || l.trim().startsWith("//") }.joinToString("\n")) }.map { it.name })
    }
}
