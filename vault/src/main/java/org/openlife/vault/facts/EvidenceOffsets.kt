package org.openlife.vault.facts

/**
 * C2 §4.3 offsets of an evidence row within its read text (`correctedText`
 * when the row names a correction, else the span's text), in UTF-16 units:
 * `0 ≤ start < end ≤ length`. The trigger checks everything except the upper
 * bound, because SQLite's `length()` counts code points (P3-02 review M-03);
 * the evidence writer (P3-04) checks this before it inserts a row.
 */
object EvidenceOffsets {
    fun fit(readText: String, start: Int, end: Int): Boolean = start >= 0 && start < end && end <= readText.length
}
