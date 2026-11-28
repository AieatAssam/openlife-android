package org.openlife.eval.facts.fixtures

import org.openlife.eval.ocr.core.Digests
import org.openlife.eval.ocr.fixtures.FixtureFonts
import org.openlife.eval.ocr.fixtures.FixtureRenderer
import org.openlife.eval.ocr.fixtures.LineSpec
import org.openlife.eval.ocr.fixtures.PageSpec
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.Random
import javax.imageio.ImageIO

/**
 * P3-06-R1 image part: renders eval/facts/v1/image-cases.tsv into eval/facts/v1/images.
 *
 * Each image draws the text-fixture rows it names (eval/facts/v1/text.tsv), one per line, with the OCR
 * set's renderer and IBM Plex fonts. The truth of an image is the union of its rows' expectations, which
 * were written from ADR-0008 and never from extractor output. Output per image: `<id>.png` and `<id>.json`
 * (the truth the device reads); `manifest.tsv` lists the SHA-256 of each PNG and truth file.
 *
 * `./gradlew :eval:generateFactsImages`: args are the repository root and the output directory.
 */
object FactsImageGenerator {
    const val SEED = 20_261_128L
    private const val WIDTH = 1080
    private const val TOP = 100.0
    private const val LINE_STEP = 110.0
    private const val MARGIN = 60.0
    private const val FONT_SIZE = 44f
    private const val ESCAPE_CONTROL = 0x20
    private const val RADIX_HEX = 16
    private const val HEX_WIDTH = 4
    private const val ITEM_SEP = " ‖ "
    private const val PART_SEP = "¦"
    private const val COLUMNS = 8
    private const val IDX_TEXT = 2
    private const val IDX_ITEMS = 3
    private const val IDX_ABSTAIN = 4
    private const val IDX_TRUTH = 5
    private const val ITEM_FIELDS = 3
    private const val ABSTAIN_FIELDS = 2

    data class Item(val kind: String, val raw: String, val canonical: String)

    data class Abstain(val kind: String?, val raw: String)

    data class Row(
        val id: String,
        val text: String,
        val items: List<Item>,
        val abstain: List<Abstain>,
        val truth: String?,
    )

    data class Case(val id: String, val rows: List<String>)

    fun parseRows(tsv: String): Map<String, Row> = tsv.lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val cells = line.split('\t')
            require(cells.size == COLUMNS) { "row has ${cells.size} columns: ${cells[0]}" }
            require(!cells[IDX_TEXT].contains("\\u")) { "${cells[0]}: escaped characters are not drawable" }
            Row(
                id = cells[0],
                text = cells[IDX_TEXT],
                items = parseItems(cells[IDX_ITEMS]),
                abstain = parseAbstain(cells[IDX_ABSTAIN]),
                truth = cells[IDX_TRUTH].ifEmpty { null },
            )
        }
        .associateBy { it.id }

    fun parseCases(tsv: String): List<Case> = tsv.lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val cells = line.split('\t')
            require(cells.size == 2) { "case row needs id and rows: $line" }
            Case(cells[0], cells[1].split('+'))
        }

    private fun parseItems(cell: String): List<Item> {
        if (cell.isEmpty()) {
            return emptyList()
        }
        return cell.split(ITEM_SEP).map { item ->
            val parts = item.split(PART_SEP)
            require(parts.size == ITEM_FIELDS) { "item needs KIND, raw and canonical: $item" }
            Item(parts[0], parts[1], parts[2])
        }
    }

    private fun parseAbstain(cell: String): List<Abstain> {
        if (cell.isEmpty()) {
            return emptyList()
        }
        return cell.split(ITEM_SEP).map { item ->
            val parts = item.split(PART_SEP)
            require(parts.size == ABSTAIN_FIELDS) { "abstention needs KIND and raw: $item" }
            Abstain(if (parts[0] == "*") null else parts[0], parts[1])
        }
    }

    /** One image: its page, its truth JSON and its PNG. */
    class Rendered(
        val case: Case,
        val rows: List<Row>,
        val png: ByteArray,
        val truthJson: String,
        val width: Int,
        val height: Int,
    )

    fun render(case: Case, rowsById: Map<String, Row>, fonts: FixtureFonts, index: Int): Rendered {
        val rows = case.rows.map { id -> rowsById[id] ?: error("${case.id}: no row $id") }
        val height = (TOP + LINE_STEP * (rows.size - 1) + LINE_STEP / 2).toInt()
        val font = fonts.sans.deriveFont(FONT_SIZE)
        val lines = rows.mapIndexed { i, row ->
            FixtureRenderer.requireDrawable(row.text, font, fonts)
            LineSpec(row.text, font, MARGIN, TOP + LINE_STEP * i, Color.BLACK)
        }
        val page = PageSpec(
            width = WIDTH,
            height = height,
            gray = false,
            background = Color.WHITE,
            decorations = emptyList(),
            lines = lines,
            skewDegrees = 0.0,
            noiseDensity = 0.0,
        )
        val rendered = FixtureRenderer.render(page, fonts, Random(SEED * SEED_STRIDE + index))
        val png = ByteArrayOutputStream().use { out ->
            check(ImageIO.write(rendered.image, "png", out)) { "no PNG writer" }
            out.toByteArray()
        }
        return Rendered(case, rows, png, truthJson(case, rows, WIDTH, height), WIDTH, height)
    }

    private const val SEED_STRIDE = 1_000L

    fun truthJson(case: Case, rows: List<Row>, width: Int, height: Int): String {
        val items = rows.flatMap { row ->
            row.items.map {
                "{\"row\":${q(row.id)},\"kind\":${q(it.kind)},\"raw\":${q(it.raw)},\"canonical\":${q(it.canonical)}}"
            }
        }
        val abstain = rows.flatMap { row ->
            row.abstain.map { "{\"row\":${q(row.id)},\"kind\":${it.kind?.let(::q) ?: "null"},\"raw\":${q(it.raw)}}" }
        }
        val truths = rows.mapNotNull { row -> row.truth?.let { "{\"row\":${q(row.id)},\"canonical\":${q(it)}}" } }
        return buildString {
            append("{\"schema\":\"openlife-facts-image-truth/1\",")
            append("\"id\":${q(case.id)},\"image\":${q("${case.id}.png")},\"width\":$width,\"height\":$height,")
            append("\"rows\":[${case.rows.joinToString(",") { q(it) }}],")
            append("\"lines\":[${rows.joinToString(",") { q(it.text) }}],")
            append("\"items\":[${items.joinToString(",")}],")
            append("\"abstain\":[${abstain.joinToString(",")}],")
            append("\"truth\":[${truths.joinToString(",")}]}\n")
        }
    }

    private fun q(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c.code < ESCAPE_CONTROL -> append("\\u").append(c.code.toString(RADIX_HEX).padStart(HEX_WIDTH, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    fun write(renders: List<Rendered>, fonts: FixtureFonts, gateText: String, outDir: File) {
        outDir.mkdirs()
        val header = "# P3-06 image set manifest: id, PNG SHA-256, truth SHA-256, " +
            "seed $SEED, width, height\n"
        val manifest = StringBuilder(header)
        manifest.append("#fonts\t").append(fonts.entries.joinToString(" ") { "${it.file}=${it.sha256}" }).append('\n')
        File(outDir, "gate.json").writeText(gateText)
        manifest.append("gate.json\t").append(Digests.sha256(gateText)).append("\t-\t0\t0\n")
        renders.forEach { r ->
            val id = r.case.id
            File(outDir, "$id.png").writeBytes(r.png)
            File(outDir, "$id.json").writeText(r.truthJson)
            manifest.append(
                String.format(
                    Locale.ROOT,
                    "%s\t%s\t%s\t%d\t%d\n",
                    id,
                    Digests.sha256(r.png),
                    Digests.sha256(r.truthJson),
                    r.width,
                    r.height,
                ),
            )
        }
        File(outDir, "manifest.tsv").writeText(manifest.toString())
    }
}

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: GenerateFactsImages <repo-root> <output-dir>" }
    val root = File(args[0])
    val fonts = FixtureFonts.load(root)
    val rowsById = FactsImageGenerator.parseRows(File(root, "eval/facts/v1/text.tsv").readText(Charsets.UTF_8))
    val cases = FactsImageGenerator.parseCases(File(root, "eval/facts/v1/image-cases.tsv").readText(Charsets.UTF_8))
    val renders = cases.mapIndexed { i, case -> FactsImageGenerator.render(case, rowsById, fonts, i) }
    val gateText = File(root, "eval/facts/v1/gate.json").readText(Charsets.UTF_8)
    FactsImageGenerator.write(renders, fonts, gateText, File(args[1]))
    println("wrote ${renders.size} images to ${args[1]}")
}
