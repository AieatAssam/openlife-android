package org.openlife.vault.facts.extract

/**
 * ADR-0008 §4.1 ranges: two dates joined by a dash or a range word, and a
 * bare day before a date (`3–5 April 2026`, `3 and 5 April 2026`), are
 * abstained and claimed as a whole. Each check looks back a bounded window.
 */
internal object DateRanges {
    private const val MAX_GAP = 3
    private const val MAX_DAY_DIGITS = 2

    fun apply(line: LineScan, tokens: List<DateToken>): List<DateToken> {
        val out = mutableListOf<DateToken>()
        for (token in tokens) {
            val previous = out.lastOrNull()
            out += when {
                previous != null && joined(line, previous.end, token.start) -> {
                    out.removeAt(out.lastIndex)
                    token.abstained(AbstentionReason.RANGE, from = previous.start)
                }

                token.startsWithDigit -> prefixed(line, token, previous?.end ?: 0)

                else -> token
            }
        }
        return out
    }

    /** True when `[from, to)` is a range joiner with optional spaces around it. */
    private fun joined(line: LineScan, from: Int, to: Int): Boolean {
        if (to - from > MAX_GAP * 2 + "until".length) return false
        val between = line.text.substring(from, to).trim(' ')
        return (between.length == 1 && between[0] in DateNames.DASHES) ||
            DateNames.RANGE_WORDS.any { it.equals(between, ignoreCase = true) }
    }

    /** `<day> <joiner> <date>`: the date abstains as a range from the bare day. */
    private fun prefixed(line: LineScan, token: DateToken, floor: Int): DateToken {
        var at = token.start
        at -= spacesBefore(line, at)
        val joinerStart = joinerStart(line, at) ?: return token
        at = joinerStart - spacesBefore(line, joinerStart)
        var digitsStart = at
        while (at - digitsStart < MAX_DAY_DIGITS && LineScan.isAsciiDigit(line.ch(digitsStart - 1))) digitsStart--
        val standalone = digitsStart < at && digitsStart >= floor && NumericStarts.canStart(line, digitsStart)
        return if (standalone) token.abstained(AbstentionReason.RANGE, from = digitsStart) else token
    }

    private fun joinerStart(line: LineScan, end: Int): Int? {
        val char = line.ch(end - 1)
        if (char in DateNames.DASHES || char == '&') return end - 1
        val word = DateNames.RANGE_PREFIX_WORDS.firstOrNull { line.matchesIgnoreCase(end - it.length, it) }
            ?: return null
        val start = end - word.length
        return if (line.boundaryBefore(start)) start else null
    }

    private fun spacesBefore(line: LineScan, end: Int): Int {
        var count = 0
        while (count < MAX_GAP && line.ch(end - count - 1) == ' ') count++
        return count
    }
}
