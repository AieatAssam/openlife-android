package org.openlife.eval.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.eval.ocr.core.Digests
import java.io.File

/**
 * P3-06 image part: the committed PNGs and truth files match eval/facts/v1/images/manifest.tsv. Always runs
 * (no runtime-dependent rendering is compared; the generator is run on demand with :eval:generateFactsImages).
 */
class FactsImageSetTest {
    private val root = File(System.getProperty("openlife.rootDir") ?: "..")
    private val dir = File(root, "eval/facts/v1/images")

    @Test
    fun committedImagesMatchTheirManifest() {
        val rows = File(dir, "manifest.tsv").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals("30 image cases", 30, rows.size)
        rows.forEach { line ->
            val cells = line.split('\t')
            val id = cells[0]
            assertEquals("$id PNG", cells[1], Digests.sha256(File(dir, "$id.png").readBytes()))
            assertEquals("$id truth", cells[2], Digests.sha256(File(dir, "$id.json").readText(Charsets.UTF_8)))
        }
    }

    @Test
    fun everyCaseNamesRowsThatExistInTheTextSet() {
        val text = File(root, "eval/facts/v1/text.tsv").readLines(Charsets.UTF_8)
        val ids = text.filter { !it.startsWith("#") && it.isNotBlank() }.map { it.split('\t')[0] }.toSet()
        val cases = File(root, "eval/facts/v1/image-cases.tsv").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
        cases.forEach { line ->
            line.split('\t')[1].split('+').forEach { id ->
                assertTrue("$id is in text.tsv", id in ids)
            }
        }
    }
}
