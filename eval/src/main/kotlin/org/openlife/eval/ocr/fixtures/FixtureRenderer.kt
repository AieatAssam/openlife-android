package org.openlife.eval.ocr.fixtures

import org.openlife.eval.ocr.core.Box
import org.openlife.eval.ocr.core.Digests
import org.openlife.eval.ocr.core.ScriptClass
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.util.Random
import kotlin.math.ceil
import kotlin.math.floor

/** One line of text: its primary font (already sized), the left end of its baseline, and its ink. */
data class LineSpec(val text: String, val font: Font, val x: Double, val baseline: Double, val ink: Color)

/** A shape drawn under the text: a filled or stroked rectangle, optionally rounded. */
data class Decoration(val rect: Rectangle2D.Double, val color: Color, val filled: Boolean, val radius: Double = 0.0)

/** Everything needed to draw one fixture. Skew rotates the whole page about its centre. */
data class PageSpec(
    val width: Int,
    val height: Int,
    val gray: Boolean,
    val background: Color,
    val decorations: List<Decoration>,
    val lines: List<LineSpec>,
    val skewDegrees: Double,
    val noiseDensity: Double,
)

class RenderedPage(val image: BufferedImage, val boxes: List<Box>)

/**
 * Draws a [PageSpec] with java.awt, headless and deterministic: glyphs are
 * laid out with an identity render context and no kerning, so the only
 * randomness is the seeded [Random] passed in for noise.
 *
 * Truth boxes are ink bounds: the union of the drawn glyph outlines after
 * the page transform, rounded outwards. A line that would leave the image is
 * an error, never clamped into a box the engines could not match.
 */
object FixtureRenderer {
    private val layoutContext = FontRenderContext(null, true, true)
    private const val BYTES_PER_INT = 4

    /** Fails unless [primary] or the fallback can draw every character of [text]. */
    fun requireDrawable(text: String, primary: Font, fonts: FixtureFonts) {
        runs(text, primary, fonts)
    }

    /** Width of [text] as it will be drawn. */
    fun advance(text: String, primary: Font, fonts: FixtureFonts): Double = runs(
        text,
        primary,
        fonts,
    ).sumOf { (run, font) -> font.createGlyphVector(layoutContext, run).logicalBounds.width }

    fun render(page: PageSpec, fonts: FixtureFonts, random: Random): RenderedPage {
        val image = BufferedImage(
            page.width,
            page.height,
            if (page.gray) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB,
        )
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            graphics.color = page.background
            graphics.fillRect(0, 0, page.width, page.height)
            val transform = AffineTransform.getRotateInstance(
                Math.toRadians(page.skewDegrees),
                page.width / 2.0,
                page.height / 2.0,
            )
            graphics.transform = transform
            graphics.stroke = BasicStroke(2f)
            for (decoration in page.decorations) {
                graphics.color = decoration.color
                val shape: Shape = if (decoration.radius > 0.0) {
                    val r = decoration.radius
                    RoundRectangle2D.Double(
                        decoration.rect.x,
                        decoration.rect.y,
                        decoration.rect.width,
                        decoration.rect.height,
                        r,
                        r,
                    )
                } else {
                    decoration.rect
                }
                if (decoration.filled) graphics.fill(shape) else graphics.draw(shape)
            }
            val boxes = page.lines.map { line ->
                graphics.color = line.ink
                drawLine(graphics, line, fonts, transform, page)
            }
            addNoise(image, page, random)
            return RenderedPage(image, boxes)
        } finally {
            graphics.dispose()
        }
    }

    /** Script of the letters in [text]; a line with no letters counts as Latin, as in production. */
    fun scriptOf(text: String): ScriptClass {
        var latin = false
        var other = false
        text.codePoints().forEach { codePoint ->
            if (Character.isLetter(codePoint)) {
                val isLatin = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
                if (isLatin) latin = true else other = true
            }
        }
        return when {
            other && latin -> ScriptClass.MIXED
            other -> ScriptClass.NON_LATIN
            else -> ScriptClass.LATIN
        }
    }

    /** SHA-256 of the width, height and every pixel as ARGB: the canonical identity of an image. */
    fun rasterSha256(image: BufferedImage): String {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val buffer = ByteBuffer.allocate((pixels.size + 2) * BYTES_PER_INT)
        buffer.putInt(image.width).putInt(image.height)
        pixels.forEach { buffer.putInt(it) }
        return Digests.sha256(buffer.array())
    }

    private fun drawLine(
        graphics: java.awt.Graphics2D,
        line: LineSpec,
        fonts: FixtureFonts,
        transform: AffineTransform,
        page: PageSpec,
    ): Box {
        var x = line.x
        var bounds: Rectangle2D? = null
        for ((run, font) in runs(line.text, line.font, fonts)) {
            val glyphs = font.createGlyphVector(layoutContext, run)
            graphics.drawGlyphVector(glyphs, x.toFloat(), line.baseline.toFloat())
            val ink = transform.createTransformedShape(glyphs.getOutline(x.toFloat(), line.baseline.toFloat())).bounds2D
            if (!ink.isEmpty) bounds = bounds?.createUnion(ink) ?: ink
            x += glyphs.logicalBounds.width
        }
        val ink = requireNotNull(bounds) { "line '${line.text}' drew no ink" }
        val box = Box(floor(ink.minX).toInt(), floor(ink.minY).toInt(), ceil(ink.maxX).toInt(), ceil(ink.maxY).toInt())
        require(box.right <= page.width && box.bottom <= page.height) { "line '${line.text}' leaves the image: $box" }
        return box
    }

    /** Splits [text] into runs the primary font draws and runs only the fallback can draw. */
    private fun runs(text: String, primary: Font, fonts: FixtureFonts): List<Pair<String, Font>> {
        val fallback = fonts.fallback.deriveFont(primary.size2D)
        val runs = mutableListOf<Pair<StringBuilder, Font>>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val font = when {
                primary.canDisplay(codePoint) -> primary

                fallback.canDisplay(codePoint) -> fallback

                else -> throw IllegalArgumentException(
                    "the fixture fonts cannot draw U+%04X in '%s'".format(codePoint, text),
                )
            }
            if (runs.isEmpty() || runs.last().second !== font) runs += StringBuilder() to font
            runs.last().first.appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
        return runs.map { it.first.toString() to it.second }
    }

    private fun addNoise(image: BufferedImage, page: PageSpec, random: Random) {
        val count = (page.noiseDensity * page.width * page.height).toInt()
        val light = page.background.rgb
        repeat(count) {
            val x = random.nextInt(page.width)
            val y = random.nextInt(page.height)
            val level = random.nextInt(NOISE_DARKEST)
            val dark = Color(level, level, level).rgb
            image.setRGB(x, y, if (random.nextBoolean()) dark else light)
        }
    }

    private const val NOISE_DARKEST = 110
}
