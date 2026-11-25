package org.openlife.eval.ocr.core

import java.text.Normalizer
import kotlin.math.ceil

/** One span an engine emitted: its text and, when the engine supplied one, its region in source pixels. */
data class SpanObservation(val text: String, val box: Box?)

/**
 * P2-04-R2 metrics. Definitions (also in eval/ocr/v1/README.md):
 *
 * - Line normalisation: Unicode NFC, every run of whitespace becomes one
 *   space, then trim. Case and punctuation are kept.
 * - Reference text: the truth lines in order, normalised, joined by `\n`.
 * - Hypothesis text: spans with a region are grouped into rows (a span joins
 *   the current row while its vertical centre lies within half the row's
 *   first span height of that span's centre), rows ordered by centre, spans
 *   in a row ordered left to right and joined by a space; rows joined by
 *   `\n`. Spans without a region follow in emitted order, one per row.
 * - CER: Levenshtein distance over Unicode code points divided by the
 *   reference length in code points. An empty hypothesis scores 1.0.
 * - IoU: intersection area over union area, exclusive right and bottom.
 * - Line match: truth lines and regions are paired one-to-one, greedily by
 *   descending IoU, among pairs with IoU at least the gate's threshold.
 * - p95: nearest rank, the value at 1-based rank ceil(0.95 * n) of the
 *   sorted values.
 */
object OcrMetrics {
    private const val P95 = 0.95
    private val whitespace = Regex("\\s+")

    fun normalizeLine(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).replace(whitespace, " ").trim()

    fun referenceText(lines: List<TruthLine>): String =
        lines.map { normalizeLine(it.text) }.filter { it.isNotEmpty() }.joinToString("\n")

    fun hypothesisText(spans: List<SpanObservation>): String {
        val placed = spans.filter { it.box != null }.sortedWith(compareBy({ centre(it) }, { it.box!!.left }))
        val rows = mutableListOf<MutableList<SpanObservation>>()
        var rowCentre = 0.0
        var rowHalfHeight = 0.0
        for (span in placed) {
            val centre = centre(span)
            if (rows.isEmpty() || centre - rowCentre > rowHalfHeight) {
                rows += mutableListOf(span)
                rowCentre = centre
                rowHalfHeight = span.box!!.height / 2.0
            } else {
                rows.last() += span
            }
        }
        val placedRows = rows.map { row ->
            row.sortedBy { it.box!!.left }.map { normalizeLine(it.text) }.filter { it.isNotEmpty() }.joinToString(" ")
        }
        val loose = spans.filter { it.box == null }.map { normalizeLine(it.text) }
        return (placedRows + loose).filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun levenshtein(a: String, b: String): Int {
        val left = a.codePoints().toArray()
        val right = b.codePoints().toArray()
        var previous = IntArray(right.size + 1) { it }
        var current = IntArray(right.size + 1)
        for (i in 1..left.size) {
            current[0] = i
            for (j in 1..right.size) {
                val substitution = previous[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.size]
    }

    fun cer(reference: String, hypothesis: String): Double {
        val length = reference.codePointCount(0, reference.length)
        require(length > 0) { "CER needs a non-empty reference" }
        return levenshtein(reference, hypothesis).toDouble() / length
    }

    fun iou(a: Box, b: Box): Double {
        val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        val intersection = width.toLong() * height.toLong()
        val union = a.area + b.area - intersection
        return if (union == 0L) 0.0 else intersection.toDouble() / union
    }

    /** Pairs of (truth index, span index). */
    fun matchLines(truth: List<Box>, spans: List<SpanObservation>, minIou: Double): List<Pair<Int, Int>> {
        val candidates = mutableListOf<Triple<Double, Int, Int>>()
        truth.forEachIndexed { t, box ->
            spans.forEachIndexed { s, span ->
                val score = span.box?.let { iou(box, it) } ?: 0.0
                if (score >= minIou && score > 0.0) candidates += Triple(score, t, s)
            }
        }
        candidates.sortWith(
            compareByDescending<Triple<Double, Int, Int>> { it.first }.thenBy { it.second }.thenBy { it.third },
        )
        val usedTruth = mutableSetOf<Int>()
        val usedSpans = mutableSetOf<Int>()
        val pairs = mutableListOf<Pair<Int, Int>>()
        for ((_, t, s) in candidates) {
            if (t !in usedTruth && s !in usedSpans) {
                usedTruth += t
                usedSpans += s
                pairs += t to s
            }
        }
        return pairs
    }

    fun p95NearestRank(values: List<Long>): Long {
        require(values.isNotEmpty()) { "p95 needs at least one value" }
        val sorted = values.sorted()
        val rank = ceil(P95 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun centre(span: SpanObservation): Double = (span.box!!.top + span.box.bottom) / 2.0
}
