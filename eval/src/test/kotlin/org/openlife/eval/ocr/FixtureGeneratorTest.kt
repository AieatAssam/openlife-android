package org.openlife.eval.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.openlife.eval.ocr.core.Box
import org.openlife.eval.ocr.core.Digests
import org.openlife.eval.ocr.core.FixtureCategory
import org.openlife.eval.ocr.core.ManifestCodec
import org.openlife.eval.ocr.core.RenderedWith
import org.openlife.eval.ocr.core.ScriptClass
import org.openlife.eval.ocr.core.TruthCodec
import org.openlife.eval.ocr.fixtures.FixtureFonts
import org.openlife.eval.ocr.fixtures.FixtureRenderer
import org.openlife.eval.ocr.fixtures.GeneratedFixture
import org.openlife.eval.ocr.fixtures.OcrFixtureGenerator
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

class FixtureGeneratorTest {
    private val root = File(System.getProperty("openlife.rootDir") ?: "..")
    private val setDir = File(root, "eval/ocr/v1")
    private val fonts by lazy { FixtureFonts.load(root) }

    @Test
    fun generatesDeterministicImagesAndTruth() {
        val first = OcrFixtureGenerator.generate(fonts)
        val second = OcrFixtureGenerator.generate(fonts)
        assertEquals(first.size, second.size)
        first.zip(second).forEach { (a, b) ->
            assertArrayEquals("${a.truth.id}: same seed, same PNG bytes", a.png, b.png)
            assertEquals(a.truthJson, b.truthJson)
            assertEquals(a.rasterSha256, b.rasterSha256)
        }
        assertEquals(
            ManifestCodec.encode(OcrFixtureGenerator.manifest(first, fonts)),
            ManifestCodec.encode(OcrFixtureGenerator.manifest(second, fonts)),
        )

        val reseeded = OcrFixtureGenerator.generate(fonts, seed = OcrFixtureGenerator.SET_SEED + 1)
        val changed = first.zip(reseeded).count { (a, b) -> a.rasterSha256 != b.rasterSha256 }
        assertTrue("another seed must give other images ($changed of ${first.size} changed)", changed >= first.size / 2)
    }

    @Test
    fun setHasTheRequiredComposition() {
        val fixtures = OcrFixtureGenerator.generate(fonts)
        val truths = fixtures.map { it.truth }
        assertEquals(40, truths.size)
        assertEquals(truths.size, truths.map { it.id }.toSet().size)
        assertEquals(15, truths.count { it.category == FixtureCategory.SCREENSHOT })
        assertEquals(15, truths.count { it.category == FixtureCategory.RECEIPT })
        assertEquals(10, truths.count { it.category == FixtureCategory.LETTER })

        val abstain = truths.filter { it.expectedAbstain }
        assertEquals("five non-Latin or mixed samples", 5, abstain.size)
        assertTrue(abstain.all { it.scriptClass != ScriptClass.LATIN })
        assertTrue(truths.filterNot { it.expectedAbstain }.all { it.scriptClass == ScriptClass.LATIN })
        assertTrue("fully non-Latin samples", abstain.count { it.scriptClass == ScriptClass.NON_LATIN } >= 2)

        // V-15: an otherwise Latin receipt with a stray Cyrillic word, and one with a single stray glyph.
        val mixedReceipts = abstain.filter {
            it.category == FixtureCategory.RECEIPT &&
                it.scriptClass == ScriptClass.MIXED
        }
        assertEquals(2, mixedReceipts.size)
        mixedReceipts.forEach { receipt ->
            val latin = receipt.lines.count { it.script == ScriptClass.LATIN }
            val other = receipt.lines.count { it.script != ScriptClass.LATIN }
            assertEquals("${receipt.id}: one stray line", 1, other)
            assertTrue("${receipt.id}: otherwise Latin", latin >= 10)
        }
        assertTrue(
            "a currency sign on a Latin receipt is not a reason to abstain",
            truths.filter { it.category == FixtureCategory.RECEIPT && !it.expectedAbstain }
                .flatMap { it.lines }
                .any { line -> listOf("€", "£", "¥").any { it in line.text } },
        )

        truths.forEach { truth ->
            assertTrue(truth.id, truth.lines.isNotEmpty())
            truth.lines.forEach { line ->
                assertTrue("${truth.id}: '${line.text}' box ${line.box}", line.box.width > 0 && line.box.height > 0)
                assertTrue(
                    "${truth.id}: box inside image",
                    line.box.right <= truth.width && line.box.bottom <= truth.height,
                )
                assertEquals(line.text.trim(), line.text)
            }
            when (truth.category) {
                FixtureCategory.RECEIPT -> {
                    assertTrue("${truth.id}: skew 0-3 degrees", abs(truth.skewDegrees) <= 3.0)
                    assertTrue("${truth.id}: noisy", truth.noiseDensity > 0.0)
                }

                else -> assertEquals(0.0, truth.skewDegrees, 0.0)
            }
        }
        assertTrue("some receipts are skewed", truths.count { abs(it.skewDegrees) >= 1.0 } >= 5)

        fixtures.forEach { fixture ->
            val image = ImageIO.read(fixture.png.inputStream())
            assertEquals(fixture.truth.width, image.width)
            assertEquals(fixture.truth.height, image.height)
        }
    }

    @Test
    fun generatorRefusesTextItsFontsCannotDraw() {
        try {
            FixtureRenderer.requireDrawable("東京 TOTAL", fonts.mono, fonts)
            fail("a line the fonts cannot draw must not become ground truth")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("cannot draw"))
        }
    }

    /** Review X-01: receipt item and price rows carry one segment per column, inside the row. */
    @Test
    fun columnRowsCarryOneSegmentPerColumn() {
        val truths = OcrFixtureGenerator.generate(fonts).map { it.truth }
        val receiptRows = truths.filter { it.category == FixtureCategory.RECEIPT }.flatMap { it.lines }
        assertTrue(receiptRows.count { it.segments.size == 2 } >= 100)
        for (truth in truths) {
            for (line in truth.lines) {
                assertEquals(line.text.split(Regex(" {3,}")), line.segments.map { it.text })
                line.segments.forEach {
                    assertTrue("${truth.id}: '${it.text}' inside its line", it.box.within(line.box))
                }
            }
        }
    }

    private fun Box.within(outer: Box) =
        left >= outer.left && right <= outer.right && top >= outer.top && bottom <= outer.bottom

    /**
     * Unconditional: the committed PNGs and truth are the source of truth.
     * Each file must be the one the manifest's digests describe, and each
     * PNG must decode to the manifest's raster digest (PNG decoding does not
     * depend on fonts or the JDK's rasteriser).
     */
    @Test
    fun committedSetMatchesItsManifest() {
        val manifestFile = File(setDir, "manifest.json")
        assertTrue("missing $manifestFile; run ./gradlew :eval:generateOcrFixtures", manifestFile.isFile)
        val committed = ManifestCodec.decode(manifestFile.readText())
        assertEquals(OcrFixtureGenerator.GENERATOR_VERSION, committed.generatorVersion)
        assertEquals(fonts.entries, committed.fonts)
        assertEquals(40, committed.fixtures.size)
        committed.fixtures.forEach { entry ->
            val png = File(setDir, entry.image).readBytes()
            val truth = File(setDir, entry.truth).readText()
            assertEquals("${entry.id} PNG matches manifest", entry.pngSha256, Digests.sha256(png))
            assertEquals("${entry.id} truth matches manifest", entry.truthSha256, Digests.sha256(truth))
            assertEquals(
                "${entry.id} committed PNG decodes to the manifest raster",
                entry.rasterSha256,
                FixtureRenderer.rasterSha256(ImageIO.read(png.inputStream())),
            )
            val decoded = TruthCodec.decode(truth)
            assertEquals(entry.category, decoded.category)
            assertEquals(entry.expectedAbstain, decoded.expectedAbstain)
        }
    }

    /**
     * The committed set is exactly what the generator produces, compared as
     * decoded pixels and truth JSON. Font rasterisation differs between JDK
     * builds and platforms, so this runs only on the runtime the manifest
     * records (review X-06); elsewhere it is skipped, never passed.
     */
    @Test
    fun committedSetRegeneratesOnTheRecordingRuntime() {
        val committed = ManifestCodec.decode(File(setDir, "manifest.json").readText())
        val here = RenderedWith.current()
        assumeTrue(
            "pixel regeneration skipped: set rendered with ${committed.renderedWith}, this runtime is $here",
            committed.renderedWith == here,
        )
        val generated = OcrFixtureGenerator.generate(fonts, committed.seed)
        assertEquals(generated.map { it.truth.id }, committed.fixtures.map { it.id })
        val byId: Map<String, GeneratedFixture> = generated.associateBy { it.truth.id }
        committed.fixtures.forEach { entry ->
            val fixture = byId.getValue(entry.id)
            assertEquals("${entry.id} truth regenerates", fixture.truthJson, File(setDir, entry.truth).readText())
            assertEquals("${entry.id} pixels regenerate", entry.rasterSha256, fixture.rasterSha256)
        }
    }
}
