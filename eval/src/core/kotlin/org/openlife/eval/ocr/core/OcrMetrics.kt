package org.openlife.eval.ocr.core

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
    fun normalizeLine(text: String): String = TODO("P2-04 GREEN")

    fun referenceText(lines: List<TruthLine>): String = TODO("P2-04 GREEN")

    fun hypothesisText(spans: List<SpanObservation>): String = TODO("P2-04 GREEN")

    fun levenshtein(a: String, b: String): Int = TODO("P2-04 GREEN")

    fun cer(reference: String, hypothesis: String): Double = TODO("P2-04 GREEN")

    fun iou(a: Box, b: Box): Double = TODO("P2-04 GREEN")

    /** Pairs of (truth index, span index). */
    fun matchLines(truth: List<Box>, spans: List<SpanObservation>, minIou: Double): List<Pair<Int, Int>> =
        TODO("P2-04 GREEN")

    fun p95NearestRank(values: List<Long>): Long = TODO("P2-04 GREEN")
}
