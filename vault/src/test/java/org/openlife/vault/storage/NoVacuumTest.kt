package org.openlife.vault.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * S-04: VACUUM may renumber implicit rowids. The FTS rowid mapping (SearchIndex) and the
 * current-reading order (C2-R14, F-71) both depend on rowids being stable, so no production
 * source may run VACUUM. Comments are ignored; the scan is case-insensitive.
 */
class NoVacuumTest {
    private val roots = listOf(File("src/main"), File("../app/src/main"))

    @Test
    fun noProductionSourceRunsVacuum() {
        val hits = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (codeOf(line).contains("VACUUM", ignoreCase = true)) "${file.path}:${index + 1}" else null
            }
        }
        assertEquals(emptyList<String>(), hits)
    }

    /** The code part of a line: blank for comment lines, and without a trailing // comment. */
    private fun codeOf(line: String): String {
        val trimmed = line.trim()
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) return ""
        return trimmed.substringBefore("//")
    }
}
