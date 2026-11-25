package org.openlife.eval.ocr.core

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** One span an engine emitted: its text and, when the engine supplied one, its region in source pixels. */
data class SpanObservation(val text: String, val box: Box?)

/** A truth line that was matched, and the spans that matched it: one whole-line span, or one per segment. */
data class LineMatch(val line: Int, val spans: List<Int>, val bySegments: Boolean)

/** CER and matched lines for one image, computed from its spans and truth. */
data class ImageScore(val cer: Double, val linesMatched: Int)

/**
 * P2-04-R2 metrics. Definitions (also in eval/ocr/v1/README.md):
 *
 * - Line normalisation: Unicode NFC, every run of whitespace becomes one
 *   space, then trim. Case and punctuation are kept.
 * - Reference text: the truth lines in order, normalised, joined by `\n`.
 *   Whole lines, never segments.
 * - Hypothesis text: see [hypothesisText].
 * - CER: Levenshtein distance over Unicode code points divided by the
 *   reference length in code points. An empty hypothesis scores 1.0.
 * - IoU: intersection area over union area, exclusive right and bottom.
 * - Line match: see [matchLines].
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

    /** The one scoring path: the harness records it, and the gate recomputes it from the recorded spans. */
    fun score(truth: FixtureTruth, spans: List<SpanObservation>, minIou: Double): ImageScore = ImageScore(
        cer = cer(referenceText(truth.lines), hypothesisText(spans, truth.skewDegrees, truth.width, truth.height)),
        linesMatched = matchLines(truth.lines, spans, minIou).size,
    )

    /**
     * Reading order of the engine's spans (P2-04 review X-02).
     *
     * Each span with a region is placed by its box centre rotated back by
     * the image's recorded skew about the image centre, so an item and its
     * price on a skewed receipt fall on one row. Spans are grouped into rows:
     * a span joins the current row while its deskewed centre lies within half
     * the row's first span's deskewed text height of that span's centre. The
     * text height is recovered from the axis-aligned box of text rotated by
     * the skew. Rows are ordered by deskewed centre, spans in a row by
     * deskewed left-to-right position and joined by a space; rows joined by
     * `\n`. Spans without a region follow in emitted order, one per row.
     *
     * Only the image's geometry (skew, width, height) is used, never truth
     * text or truth boxes, so misread, missing or misplaced spans still cost
     * CER. Limit: an engine whose boxes are not axis-aligned boxes of the
     * skewed text may be ordered differently than it reads.
     */
    fun hypothesisText(
        spans: List<SpanObservation>,
        skewDegrees: Double = 0.0,
        width: Int = 0,
        height: Int = 0,
    ): String {
        val angle = Math.toRadians(skewDegrees)
        val placed = spans.mapNotNull { span -> span.box?.let { Placed(span, deskew(it, angle, width, height)) } }
            .sortedWith(compareBy({ it.y }, { it.x }))
        val rows = mutableListOf<MutableList<Placed>>()
        var rowCentre = 0.0
        var rowHalfHeight = 0.0
        for (span in placed) {
            if (rows.isEmpty() || span.y - rowCentre > rowHalfHeight) {
                rows += mutableListOf(span)
                rowCentre = span.y
                rowHalfHeight = textHeight(span.span.box!!, angle) / 2.0
            } else {
                rows.last() += span
            }
        }
        val placedRows = rows.map { row ->
            row.sortedBy { it.x }.map { normalizeLine(it.span.text) }.filter { it.isNotEmpty() }.joinToString(" ")
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

    /**
     * Line match (P2-04 review X-01). Each truth line offers targets: its
     * whole-line box, and, when it has two or more segments, each segment's
     * box. Spans and targets are paired one-to-one, greedily by descending
     * IoU (ties: line, target, span order), among pairs with IoU at least
     * [minIou]. Once a line's whole-line target is paired its segment
     * targets are withdrawn, and once one of its segments is paired its
     * whole-line target is withdrawn. A line is matched when its whole-line
     * target is paired, or when every one of its segments is.
     *
     * So an engine that reads a receipt row as one span and an engine that
     * reads item and price as two spans both match the row.
     */
    fun matchLines(truth: List<TruthLine>, spans: List<SpanObservation>, minIou: Double): List<LineMatch> {
        val targets = targetsOf(truth)
        val pairing = Pairing()
        for (candidate in candidatesOf(targets, spans, minIou)) pairing.offer(targets[candidate.target], candidate.span)
        return truth.indices.mapNotNull { line -> pairing.matchOf(line, truth[line].segments.size) }
    }

    private fun targetsOf(truth: List<TruthLine>): List<Target> = truth.flatMapIndexed { index, line ->
        val segments = if (line.segments.size > 1) {
            line.segments.mapIndexed { segment, part -> Target(index, segment, part.box) }
        } else {
            emptyList()
        }
        listOf(Target(index, WHOLE, line.box)) + segments
    }

    private fun candidatesOf(targets: List<Target>, spans: List<SpanObservation>, minIou: Double): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        targets.forEachIndexed { t, target ->
            spans.forEachIndexed { s, span ->
                val score = span.box?.let { iou(target.box, it) } ?: 0.0
                if (score >= minIou && score > 0.0) candidates += Candidate(score, t, s)
            }
        }
        return candidates.sortedWith(compareByDescending<Candidate> { it.iou }.thenBy { it.target }.thenBy { it.span })
    }

    /** The one-to-one pairing state of [matchLines], with its withdrawal rule. */
    private class Pairing {
        private val usedSpans = mutableSetOf<Int>()
        private val whole = mutableMapOf<Int, Int>()
        private val segments = mutableMapOf<Int, MutableMap<Int, Int>>()

        fun offer(target: Target, span: Int) {
            val lineSegments = segments[target.line]
            val free = when {
                span in usedSpans || target.line in whole -> false
                target.segment == WHOLE -> lineSegments == null
                else -> lineSegments?.containsKey(target.segment) != true
            }
            if (!free) return
            if (target.segment == WHOLE) {
                whole[target.line] = span
            } else {
                segments.getOrPut(target.line) { mutableMapOf() }[target.segment] = span
            }
            usedSpans += span
        }

        fun matchOf(line: Int, segmentCount: Int): LineMatch? {
            val span = whole[line]
            val bySegment = segments[line]
            return when {
                span != null -> LineMatch(line, listOf(span), bySegments = false)

                bySegment != null && bySegment.size == segmentCount ->
                    LineMatch(line, bySegment.toSortedMap().values.toList(), bySegments = true)

                else -> null
            }
        }
    }

    fun p95NearestRank(values: List<Long>): Long {
        require(values.isNotEmpty()) { "p95 needs at least one value" }
        val sorted = values.sorted()
        val rank = ceil(P95 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** The box centre rotated by -[angle] about the image centre: inverse of the generator's page rotation. */
    private fun deskew(box: Box, angle: Double, width: Int, height: Int): Pair<Double, Double> {
        val dx = (box.left + box.right) / 2.0 - width / 2.0
        val dy = (box.top + box.bottom) / 2.0 - height / 2.0
        return (cos(angle) * dx + sin(angle) * dy + width / 2.0) to (-sin(angle) * dx + cos(angle) * dy + height / 2.0)
    }

    /**
     * Height of text whose rotation by [angle] has the axis-aligned [box]:
     * solving W = w cos + h sin, H = w sin + h cos for h. Clamped to [1, H].
     */
    private fun textHeight(box: Box, angle: Double): Double {
        val c = cos(angle)
        val s = abs(sin(angle))
        val denominator = c * c - s * s
        val height = if (denominator <= 0.0) box.height.toDouble() else (box.height * c - box.width * s) / denominator
        return height.coerceIn(1.0, box.height.toDouble().coerceAtLeast(1.0))
    }

    private class Placed(val span: SpanObservation, centre: Pair<Double, Double>) {
        val x = centre.first
        val y = centre.second
    }

    private class Target(val line: Int, val segment: Int, val box: Box)

    private class Candidate(val iou: Double, val target: Int, val span: Int)

    private const val WHOLE = -1
}
