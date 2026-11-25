package org.openlife.vault.search

import java.text.Normalizer
import java.util.Locale

/**
 * P2-08-R4: a bounded excerpt of a hit's text with the matched ranges. Built
 * in Kotlin from the stored text, so the FTS5 and LIKE paths highlight the
 * same way. Matching folds case and diacritics like the index's tokenizer
 * (unicode61 remove_diacritics 2): earlier tokens match whole words, the last
 * one a word prefix. The excerpt is plain text; nothing in it is a link.
 */
object SearchSnippet {
    const val MAX_LENGTH = 160
    private const val ELLIPSIS = "…"
    private const val LEAD_IN_DIVISOR = 3

    data class Snippet(val text: String, val highlights: List<IntRange>)

    fun of(text: String, tokens: List<String>): Snippet {
        val ranges = matches(text, tokens)
        if (text.length <= MAX_LENGTH) return Snippet(text, ranges)
        val anchor = ranges.firstOrNull()?.first ?: 0
        var start = (anchor - MAX_LENGTH / LEAD_IN_DIVISOR).coerceAtLeast(0)
        val end = (start + MAX_LENGTH).coerceAtMost(text.length)
        start = (end - MAX_LENGTH).coerceAtLeast(0)
        if (start > 0 && Character.isLowSurrogate(text[start])) start++
        val cutEnd = if (end < text.length && Character.isLowSurrogate(text[end])) end - 1 else end
        val prefix = if (start > 0) ELLIPSIS else ""
        val suffix = if (cutEnd < text.length) ELLIPSIS else ""
        val shift = prefix.length - start
        val inside = ranges
            .filter { it.first >= start && it.last < cutEnd }
            .map { (it.first + shift)..(it.last + shift) }
        return Snippet(prefix + text.substring(start, cutEnd) + suffix, inside)
    }

    private fun matches(text: String, tokens: List<String>): List<IntRange> {
        if (tokens.isEmpty()) return emptyList()
        val folded = StringBuilder()
        val origin = mutableListOf<Int>()
        text.forEachIndexed { index, char ->
            val piece = fold(char.toString())
            piece.forEach {
                folded.append(it)
                origin += index
            }
        }
        val haystack = folded.toString()
        val found = mutableListOf<IntRange>()
        tokens.forEachIndexed { tokenIndex, token ->
            val needle = fold(token)
            if (needle.isEmpty()) return@forEachIndexed
            var from = haystack.indexOf(needle)
            while (from >= 0) {
                val end = from + needle.length
                val startsWord = from == 0 || !haystack[from - 1].isLetterOrDigit()
                val endsWord = end == haystack.length || !haystack[end].isLetterOrDigit()
                if (startsWord && (tokenIndex == tokens.lastIndex || endsWord)) {
                    found += origin[from]..extendOverMarks(text, origin[end - 1])
                }
                from = haystack.indexOf(needle, from + 1)
            }
        }
        return merge(found)
    }

    private fun fold(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)

    private fun extendOverMarks(text: String, last: Int): Int {
        var end = last
        while (end + 1 < text.length && (isMark(text[end + 1]) || Character.isLowSurrogate(text[end + 1]))) end++
        return end
    }

    private fun isMark(char: Char): Boolean = when (Character.getType(char).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> false
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        val merged = mutableListOf<IntRange>()
        ranges.sortedBy { it.first }.forEach { range ->
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }

    private val MARKS = Regex("\\p{M}+")
}
