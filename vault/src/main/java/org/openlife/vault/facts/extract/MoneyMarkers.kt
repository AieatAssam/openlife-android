package org.openlife.vault.facts.extract

/**
 * A symbol (§5.3) or an uppercase ISO 4217 code at `[start, end)`. A
 * [pair] (`EUR/USD`) is recognised so that the amount beside it abstains;
 * it names no currency.
 */
internal data class Marker(
    val start: Int,
    val end: Int,
    val symbol: String?,
    val code: String?,
    val pair: Boolean = false,
)

/** A dense number: ASCII digits with single inner separators (§5.2), at `[start, end)`. */
internal data class Dense(val start: Int, val end: Int, val text: String) {
    val pureDigits: Boolean get() = text.all(LineScan::isAsciiDigit)
}

/** One forward pass each for the markers and the dense numbers of a line. */
internal object MoneyMarkers {
    private const val CODE_LENGTH = 3
    private const val KR = "kr"
    private val DOLLAR_PREFIXES = listOf("US", "CA", "AU", "NZ", "HK", "S", "R")
    private val SYMBOL_CHARS: String =
        (MoneyTables.SINGLE.keys + MoneyTables.MULTI.keys).filter { it.length == 1 && it != "$" }.joinToString("")

    fun markers(line: LineScan): List<Marker> {
        val out = mutableListOf<Marker>()
        var index = 0
        while (index < line.length) {
            line.reach(index)
            val char = line.ch(index)
            val marker = when {
                char == '$' -> dollar(line, index)
                char in SYMBOL_CHARS -> Marker(index, index + 1, char.toString(), null)
                LineScan.isAsciiLetter(char) && !line.letterBefore(index) -> word(line, index)
                else -> null
            }
            if (marker != null) out += marker
            val skip = if (LineScan.isAsciiLetter(char)) line.asciiWordEnd(index, line.length) else 0
            index = maxOf(index + 1, marker?.end ?: skip)
        }
        return out
    }

    /**
     * `$`, or a §5.3 prefixed dollar. A `$` with any other letter attached
     * before it (`C$`, excluded in §5.3) is not a marker: the attached
     * letters could name a currency v1 does not read.
     */
    private fun dollar(line: LineScan, at: Int): Marker? {
        for (prefix in DOLLAR_PREFIXES) {
            val start = at - prefix.length
            val whole = start >= 0 && line.text.startsWith(prefix, start) && !line.anyClaimed(start, at) &&
                !line.letterBefore(start)
            if (whole) return Marker(start, at + 1, prefix + "$", null)
        }
        return if (line.letterBefore(at)) null else Marker(at, at + 1, "$", null)
    }

    private fun word(line: LineScan, start: Int): Marker? {
        val end = line.asciiWordEnd(start, CODE_LENGTH)
        if (line.letterAt(end)) return null
        val word = line.text.substring(start, end)
        val pairEnd = if (isCode(word)) CurrencyPairs.endAfter(line.text, end) else null
        return when {
            word == KR -> Marker(start, end, KR, null)
            pairEnd != null -> Marker(start, pairEnd, null, null, pair = true)
            isCode(word) -> Marker(start, end, null, word)
            else -> null
        }
    }

    /**
     * A marker no number owns within one space separator of the run
     * `[start, end)`: a marker with a digit on its far side marks that
     * number instead (`12.00 € +1 555 123 4567`). Constant window
     * (finding EE-01).
     */
    fun freeBeside(line: LineScan, start: Int, end: Int): Boolean {
        val before = markerEndingAt(line, if (LineScan.isSpace(line.ch(start - 1))) start - 1 else start)
        val after = markerStartingAt(line, if (LineScan.isSpace(line.ch(end))) end + 1 else end)
        return before?.takeIf { !digitNear(line, it.start - 1, -1) } != null ||
            after?.takeIf { !digitNear(line, it.end, 1) } != null
    }

    private fun digitNear(line: LineScan, at: Int, step: Int): Boolean {
        val spaced = LineScan.isSpace(line.ch(at)) && LineScan.isAsciiDigit(line.ch(at + step))
        return LineScan.isAsciiDigit(line.ch(at)) || spaced
    }

    private fun markerStartingAt(line: LineScan, at: Int): Marker? {
        val char = line.ch(at)
        val dollarAt = if (LineScan.isAsciiLetter(char)) line.asciiWordEnd(at, 2) else at
        return when {
            line.ch(dollarAt) == '$' -> dollar(line, dollarAt)?.takeIf { it.start == at }
            char in SYMBOL_CHARS -> Marker(at, at + 1, char.toString(), null)
            LineScan.isAsciiLetter(char) && !line.letterBefore(at) -> word(line, at)
            else -> null
        }
    }

    private fun markerEndingAt(line: LineScan, end: Int): Marker? {
        val char = line.ch(end - 1)
        var start = end
        while (end - start < CODE_LENGTH && LineScan.isAsciiLetter(line.ch(start - 1))) start--
        return when {
            char == '$' -> dollar(line, end - 1)
            char in SYMBOL_CHARS -> Marker(end - 1, end, char.toString(), null)
            start < end && !line.letterBefore(start) -> word(line, start)?.takeIf { it.end == end }
            else -> null
        }
    }

    private fun isCode(word: String): Boolean =
        word.length == CODE_LENGTH && word.all(LineScan::isAsciiUpper) && word in MoneyTables.LIST_ONE

    private fun continuesDense(line: LineScan, at: Int): Boolean {
        val char = line.ch(at)
        val joins = char in MoneyNumbers.SEPARATORS && LineScan.isAsciiDigit(line.ch(at + 1))
        return LineScan.isAsciiDigit(char) || joins
    }

    fun numbers(line: LineScan): List<Dense> {
        val out = mutableListOf<Dense>()
        var index = 0
        while (index < line.length) {
            line.reach(index)
            if (!LineScan.isAsciiDigit(line.ch(index))) {
                index++
                continue
            }
            var end = index
            while (continuesDense(line, end)) end++
            out += Dense(index, end, line.text.substring(index, end))
            index = end
        }
        return out
    }
}
