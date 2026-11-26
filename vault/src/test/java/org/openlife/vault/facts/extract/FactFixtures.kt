package org.openlife.vault.facts.extract

import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Table-driven fixtures under `src/test/resources/facts/` (P3-03 files_touched).
 *
 * One row per line: the input, ` ‖ `, then the expected outputs separated by
 * ` ‖ `, or `-` for none. The expected values are written out by hand from
 * ADR-0008; nothing is computed by the code under test.
 *
 * - Input: spans separated by `␤` (span ids `s1`, `s2`, …, ordinals 1, 2, …).
 *   `\uXXXX` escapes are decoded in inputs and expected values.
 * - Candidate: `[@N ]KIND¦rawText¦canonicalText` (span N, default 1). The
 *   candidates of a row must match exactly, in span then offset order.
 * - Abstention: `[@N ]!KIND¦rawText`: that exact slice must be recorded as
 *   abstained (recognised and claimed, ADR-0008 §1).
 * - `#` starts a comment line.
 */
internal object FactFixtures {
    private const val SPAN_SEPARATOR = "␤"
    private const val COLUMN_SEPARATOR = " ‖ "

    data class Row(val line: Int, val input: String, val candidates: List<String>, val abstentions: List<String>)

    fun load(name: String): List<Row> {
        val stream = checkNotNull(FactFixtures::class.java.classLoader?.getResourceAsStream("facts/$name")) {
            "Missing fixture facts/$name"
        }
        val lines = stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        return lines.mapIndexedNotNull { index, raw ->
            if (raw.isBlank() || raw.startsWith("#")) return@mapIndexedNotNull null
            val columns = raw.split(COLUMN_SEPARATOR)
            val expected = columns.drop(1).filter { it.isNotEmpty() && it != "-" }.map(::decode)
            Row(
                line = index + 1,
                input = decode(columns[0]),
                candidates = expected.filterNot { it.withoutSpan().startsWith("!") },
                abstentions = expected.filter { it.withoutSpan().startsWith("!") },
            )
        }
    }

    fun spansOf(input: String): List<ExtractionSpan> = input.split(SPAN_SEPARATOR).mapIndexed { index, text ->
        ExtractionSpan(spanId = "s${index + 1}", correctionId = null, ordinal = index + 1, text = text)
    }

    /** Runs every row and fails once, listing every mismatching row. */
    fun check(name: String, minimumRows: Int) {
        val rows = load(name)
        assertTrue("$name has ${rows.size} rows, expected at least $minimumRows", rows.size >= minimumRows)
        val failures = rows.mapNotNull(::mismatch)
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${rows.size} rows in $name differ:\n" + failures.joinToString("\n"))
        }
    }

    fun render(result: ExtractionResult, spans: List<ExtractionSpan>): List<String> {
        val order = spans.withIndex().associate { it.value.spanId to it.index }
        return result.candidates.sortedWith(compareBy({ order.getValue(it.spanId) }, { it.startOffset })).map {
            prefix(order.getValue(it.spanId)) + "${it.kind}¦${it.rawText}¦${it.canonicalText}"
        }
    }

    private fun mismatch(row: Row): String? {
        val spans = spansOf(row.input)
        val result = FactExtractor.extract(spans)
        val actual = render(result, spans)
        val missingAbstentions = row.abstentions.filterNot { hasAbstention(result, spans, it) }
        val sliceErrors = result.candidates.filterNot { candidate ->
            val text = spans.single { it.spanId == candidate.spanId }.text
            text.substring(candidate.startOffset, candidate.endOffset) == candidate.rawText
        }
        return if (actual == row.candidates && missingAbstentions.isEmpty() && sliceErrors.isEmpty()) {
            null
        } else {
            "  line ${row.line}: <${row.input}>\n    expected ${row.candidates}\n    actual   $actual" +
                (if (missingAbstentions.isEmpty()) "" else "\n    missing abstentions $missingAbstentions") +
                (if (sliceErrors.isEmpty()) "" else "\n    raw text is not the slice: $sliceErrors")
        }
    }

    private fun hasAbstention(result: ExtractionResult, spans: List<ExtractionSpan>, item: String): Boolean {
        val spanIndex = spanIndexOf(item)
        val body = item.withoutSpan().removePrefix("!")
        val kind = ExtractedKind.valueOf(body.substringBefore('¦'))
        val raw = body.substringAfter('¦')
        val span = spans[spanIndex]
        return result.abstentions.any {
            it.spanId == span.spanId && it.kind == kind && span.text.substring(it.startOffset, it.endOffset) == raw
        }
    }

    private fun prefix(spanIndex: Int): String = if (spanIndex == 0) "" else "@${spanIndex + 1} "

    private fun spanIndexOf(item: String): Int =
        if (item.startsWith("@")) item.substring(1, item.indexOf(' ')).toInt() - 1 else 0

    private fun String.withoutSpan(): String = if (startsWith("@")) substring(indexOf(' ') + 1) else this

    private fun decode(text: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            if (text.startsWith("\\u", index) && index + ESCAPE_LENGTH <= text.length) {
                out.append(text.substring(index + 2, index + ESCAPE_LENGTH).toInt(HEX).toChar())
                index += ESCAPE_LENGTH
            } else {
                out.append(text[index])
                index++
            }
        }
        return out.toString()
    }

    private const val ESCAPE_LENGTH = 6
    private const val HEX = 16
}
