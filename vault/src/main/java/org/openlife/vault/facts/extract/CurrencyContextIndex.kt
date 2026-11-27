package org.openlife.vault.facts.extract

/**
 * ADR-0008 §5.4 currency context for a multi-code symbol: qualifying codes
 * in the same span and in the spans with ordinal exactly ±1. Each span is
 * indexed once, so a lookup costs a constant per amount (C2-R29).
 *
 * A code qualifies when it is a whole uppercase word, a code some
 * multi-code symbol takes, not on the §5.5 English-word list, not one
 * half of a currency pair (`EUR/USD`), and not in marker position (at
 * most one space separator, and any sign, from an ASCII digit: `USD -33.10`
 * is a marker, never context). The span text is read as
 * printed; claims made by other rules there are not consulted.
 *
 * Neighbours are found by ordinal, so the spans must be one reading with
 * unique ordinals; duplicates fail loudly.
 */
internal class CurrencyContextIndex(spans: List<ExtractionSpan>) {
    private val byOrdinal: Map<Int, ExtractionSpan> = spans.associateBy { it.ordinal }.also {
        require(it.size == spans.size) { "span ordinals must be unique within one reading" }
    }
    private val codes = HashMap<String, Map<String, Int>>()

    /**
     * The context of an amount marked by [symbol] in [span]: the distinct
     * qualifying codes, and one row per inspected neighbour span.
     */
    fun lookup(span: ExtractionSpan, symbol: String): Pair<List<String>, List<CurrencyContext>> {
        val accepted = MoneyTables.MULTI.getValue(symbol)
        val found = LinkedHashSet<String>()
        found += accepted.filter { it in codesOf(span) }
        val rows = listOfNotNull(byOrdinal[span.ordinal - 1], byOrdinal[span.ordinal + 1]).map { neighbour ->
            val index = codesOf(neighbour)
            val hits = accepted.filter { it in index }
            found += hits
            val first = hits.minOfOrNull { index.getValue(it) }
            CurrencyContext(neighbour.spanId, neighbour.correctionId, first, first?.plus(CODE_LENGTH))
        }
        return found.sorted() to rows
    }

    private fun codesOf(span: ExtractionSpan): Map<String, Int> = codes.getOrPut(span.spanId) { index(span.text) }

    private fun index(text: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        var at = 0
        while (at <= text.length - CODE_LENGTH) {
            val end = at + CODE_LENGTH
            val word = text.substring(at, end)
            val whole = !letterBefore(text, at) && !letterAt(text, end)
            val free = !nearDigit(text, at, end) && CurrencyPairs.endAfter(text, end) == null &&
                !CurrencyPairs.isSecond(text, at)
            if (whole && word in MoneyTables.CONTEXT_CODES && free) {
                out.putIfAbsent(word, at)
            }
            at++
        }
        return out
    }

    private fun nearDigit(text: String, start: Int, end: Int): Boolean {
        var before = start - 1
        if (text.getOrNull(before)?.let(LineScan::isSignLike) == true) before--
        if (text.getOrNull(before)?.let(LineScan::isSpace) == true) before--
        var after = end
        if (text.getOrNull(after)?.let(LineScan::isSpace) == true) after++
        if (text.getOrNull(after)?.let(LineScan::isSignLike) == true) after++
        return digitAt(text, before) || digitAt(text, after)
    }

    private fun letterAt(text: String, index: Int): Boolean =
        index in text.indices && isLetterLike(Character.codePointAt(text, index))

    private fun letterBefore(text: String, index: Int): Boolean =
        index in 1..text.length && isLetterLike(Character.codePointBefore(text, index))

    private fun isLetterLike(codePoint: Int): Boolean =
        Character.isLetter(codePoint) || LineScan.isAttachedMark(codePoint)

    private fun digitAt(text: String, index: Int): Boolean = text.getOrNull(index)?.let(LineScan::isAsciiDigit) == true

    private companion object {
        const val CODE_LENGTH = 3
    }
}
