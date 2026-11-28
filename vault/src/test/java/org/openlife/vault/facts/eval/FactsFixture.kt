package org.openlife.vault.facts.eval

import org.openlife.vault.facts.extract.ExtractedKind
import java.io.File

/** One expected candidate: kind, the exact raw slice of the span, and the canonical text (ADR-0008 §3). */
internal data class ExpectedItem(val kind: ExtractedKind, val raw: String, val canonical: String)

/** One expected abstention. A null kind is written `*` in the fixture. */
internal data class ExpectedAbstention(val kind: ExtractedKind?, val raw: String)

/** One fixture row. [truth] is set only for ambiguous d/m rows and must equal one DATE item's canonical. */
internal data class FactRow(
    val id: String,
    val stress: String,
    val text: String,
    val items: List<ExpectedItem>,
    val abstain: List<ExpectedAbstention>,
    val truth: String?,
)

/** Reads eval/facts/v1/text.tsv: eight TAB-separated columns, described in eval/facts/v1/README.md. */
internal object FactsFixture {
    private const val COLUMNS = 8
    private const val RADIX_HEX = 16
    private const val ITEM_SEP = " ‖ "
    private const val PART_SEP = "¦"
    private val ESCAPE = Regex("\\\\u([0-9A-Fa-f]{4})")

    fun load(file: File): List<FactRow> = file.readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map(::parseRow)

    /** Decodes the `\uXXXX` escapes used for invisible and look-alike characters. */
    internal fun decode(value: String): String =
        ESCAPE.replace(value) { Char(it.groupValues[1].toInt(RADIX_HEX)).toString() }

    private fun parseRow(line: String): FactRow {
        val cells = line.split('\t')
        require(cells.size == COLUMNS) { "row has ${cells.size} columns: ${cells[0]}" }
        val id = cells[0]
        val text = decode(cells[2])
        val items = parseItems(id, text, decode(cells[3]))
        val truth = cells[5].ifEmpty { null }
        require(truth == null || items.any { it.kind == ExtractedKind.DATE && it.canonical == truth }) {
            "$id: truth '$truth' is not a DATE item"
        }
        return FactRow(id, cells[1], text, items, parseAbstain(id, text, decode(cells[4])), truth)
    }

    private fun parseItems(id: String, text: String, cell: String): List<ExpectedItem> =
        if (cell.isEmpty()) emptyList() else cell.split(ITEM_SEP).map { item ->
            val parts = item.split(PART_SEP)
            require(parts.size == 3) { "$id: item needs KIND, raw and canonical: $item" }
            requireInText(id, text, parts[1])
            ExpectedItem(ExtractedKind.valueOf(parts[0]), parts[1], parts[2])
        }

    private fun parseAbstain(id: String, text: String, cell: String): List<ExpectedAbstention> =
        if (cell.isEmpty()) emptyList() else cell.split(ITEM_SEP).map { item ->
            val parts = item.split(PART_SEP)
            require(parts.size == 2) { "$id: abstention needs KIND and raw: $item" }
            requireInText(id, text, parts[1])
            ExpectedAbstention(if (parts[0] == "*") null else ExtractedKind.valueOf(parts[0]), parts[1])
        }

    private fun requireInText(id: String, text: String, raw: String) =
        require(text.contains(raw)) { "$id: raw '$raw' is not a slice of the text" }
}
