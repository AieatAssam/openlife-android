package org.openlife.eval.ocr.fixtures

import org.openlife.eval.ocr.core.Digests
import org.openlife.eval.ocr.core.FixtureCategory
import org.openlife.eval.ocr.core.FixtureManifest
import org.openlife.eval.ocr.core.FixtureTruth
import org.openlife.eval.ocr.core.ManifestCodec
import org.openlife.eval.ocr.core.ManifestEntry
import org.openlife.eval.ocr.core.RenderedWith
import org.openlife.eval.ocr.core.ScriptClass
import org.openlife.eval.ocr.core.TruthCodec
import org.openlife.eval.ocr.core.TruthLine
import org.openlife.eval.ocr.core.TruthSegment
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.Random
import javax.imageio.ImageIO

/** One generated image with its truth, before anything is written to disk. */
class GeneratedFixture(val truth: FixtureTruth, val truthJson: String, val png: ByteArray, val rasterSha256: String)

/**
 * P2-04-R1: generates eval/ocr/v1 deterministically from [SET_SEED]: 15
 * phone-screenshot-style images, 15 thermal receipts and 10 letters or
 * forms, 5 of them carrying non-Latin text.
 */
object OcrFixtureGenerator {
    const val SET = "ocr/v1"
    const val GENERATOR_VERSION = 2
    const val SET_SEED = 20_261_125L
    private const val SCREENSHOTS = 15
    private const val RECEIPTS = 15
    private const val LETTERS = 10
    private const val SEED_STRIDE = 1_000L

    fun generate(fonts: FixtureFonts, seed: Long = SET_SEED): List<GeneratedFixture> {
        val plan = List(SCREENSHOTS) { FixtureCategory.SCREENSHOT to it } +
            List(RECEIPTS) { FixtureCategory.RECEIPT to it } +
            List(LETTERS) { FixtureCategory.LETTER to it }
        return plan.mapIndexed { order, (category, index) ->
            val fixtureSeed = seed * SEED_STRIDE + order
            val random = Random(fixtureSeed)
            val page = when (category) {
                FixtureCategory.SCREENSHOT -> ScreenshotContent.build(index, fonts, random)
                FixtureCategory.RECEIPT -> ReceiptContent.build(index, fonts, random)
                FixtureCategory.LETTER -> LetterContent.build(index, fonts, random)
            }
            fixture(category, index, fixtureSeed, page, fonts, random)
        }
    }

    fun manifest(fixtures: List<GeneratedFixture>, fonts: FixtureFonts, seed: Long = SET_SEED): FixtureManifest =
        FixtureManifest(
            set = SET,
            generator = OcrFixtureGenerator::class.java.name,
            generatorVersion = GENERATOR_VERSION,
            seed = seed,
            renderedWith = RenderedWith.current(),
            fonts = fonts.entries,
            fixtures = fixtures.map {
                ManifestEntry(
                    id = it.truth.id,
                    category = it.truth.category,
                    expectedAbstain = it.truth.expectedAbstain,
                    image = it.truth.image,
                    truth = truthPath(it.truth.id),
                    pngSha256 = Digests.sha256(it.png),
                    rasterSha256 = it.rasterSha256,
                    truthSha256 = Digests.sha256(it.truthJson),
                )
            },
        )

    /** Writes images, truth and manifest; files of earlier generations that are no longer produced are removed. */
    fun write(fixtures: List<GeneratedFixture>, manifest: FixtureManifest, setDir: File) {
        val images = File(setDir, "fixtures").apply { mkdirs() }
        val truth = File(setDir, "truth").apply { mkdirs() }
        val produced = fixtures.map { it.truth.id }.toSet()
        images.listFiles { file ->
            file.extension == "png" && file.nameWithoutExtension !in produced
        }?.forEach { it.delete() }
        truth.listFiles { file ->
            file.extension == "json" && file.nameWithoutExtension !in produced
        }?.forEach { it.delete() }
        fixtures.forEach {
            File(setDir, it.truth.image).writeBytes(it.png)
            File(setDir, truthPath(it.truth.id)).writeText(it.truthJson)
        }
        File(setDir, "manifest.json").writeText(ManifestCodec.encode(manifest))
    }

    private fun truthPath(id: String) = "truth/$id.json"

    @Suppress("LongParameterList")
    private fun fixture(
        category: FixtureCategory,
        index: Int,
        seed: Long,
        page: PageSpec,
        fonts: FixtureFonts,
        random: Random,
    ): GeneratedFixture {
        val id = String.format(Locale.ROOT, "%s-%02d", category.key, index + 1)
        val rendered = FixtureRenderer.render(page, fonts, random)
        val lines = page.lines.zip(rendered.lines).map { (line, ink) ->
            val segments = ink.segments.map {
                val text = line.text.substring(it.start, it.end)
                TruthSegment(text, it.box, FixtureRenderer.scriptOf(text))
            }
            TruthLine(line.text, ink.box, FixtureRenderer.scriptOf(line.text), segments)
        }
        val scriptClass = FixtureRenderer.scriptOf(lines.joinToString(" ") { it.text })
        val truth = FixtureTruth(
            id = id,
            category = category,
            image = "fixtures/$id.png",
            width = page.width,
            height = page.height,
            seed = seed,
            style = style(category, page),
            skewDegrees = page.skewDegrees,
            noiseDensity = page.noiseDensity,
            scriptClass = scriptClass,
            expectedAbstain = scriptClass != ScriptClass.LATIN,
            lines = lines,
        )
        val png = ByteArrayOutputStream().use { out ->
            check(ImageIO.write(rendered.image, "png", out)) { "no PNG writer" }
            out.toByteArray()
        }
        return GeneratedFixture(truth, TruthCodec.encode(truth), png, FixtureRenderer.rasterSha256(rendered.image))
    }

    private fun style(category: FixtureCategory, page: PageSpec): String = when (category) {
        FixtureCategory.SCREENSHOT -> "phone-screenshot"
        FixtureCategory.RECEIPT -> "thermal-receipt"
        FixtureCategory.LETTER -> if (page.decorations.count { !it.filled } > 1) "form" else "letter"
    }
}
