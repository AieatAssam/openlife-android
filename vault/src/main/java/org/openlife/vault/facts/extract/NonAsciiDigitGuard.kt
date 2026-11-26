package org.openlife.vault.facts.extract

/**
 * ADR-0008 §1 (Z-16): only ASCII `0`-`9` are digits. A numeric token that
 * holds any other digit character is abstained and claims all of its
 * characters, so no numeric rule salvages the ASCII part (`１2/04/2026`
 * gives nothing, not `2/04/2026`). Runs after URL and EMAIL, before DATE.
 */
internal object NonAsciiDigitGuard {
    private const val SEPARATORS = "/-.:,'  "

    fun scan(line: LineScan) {
        var index = 0
        while (index < line.length) {
            line.reach(index)
            index = if (isForeignDigit(line, index)) claimToken(line, index) else index + 1
        }
    }

    private fun claimToken(line: LineScan, at: Int): Int {
        var start = at
        while (extendsLeft(line, start)) start--
        var end = at
        while (end < line.length && extendsRight(line, end)) end += Character.charCount(line.text.codePointAt(end))
        line.abstain(null, start, end, AbstentionReason.NON_ASCII_DIGIT)
        return end
    }

    private fun extendsLeft(line: LineScan, start: Int): Boolean {
        val previous = start - 1
        if (previous < 0 || line.isClaimed(previous)) return false
        return isAnyDigit(line, previous) || (line.text[previous] in SEPARATORS && isAnyDigit(line, previous - 1))
    }

    private fun extendsRight(line: LineScan, end: Int): Boolean {
        if (line.isClaimed(end)) return false
        return isAnyDigit(line, end) || (line.text[end] in SEPARATORS && isAnyDigit(line, end + 1))
    }

    private fun isForeignDigit(line: LineScan, index: Int): Boolean =
        !line.isClaimed(index) && isAnyDigit(line, index) && !LineScan.isAsciiDigit(line.text[index])

    private fun isAnyDigit(line: LineScan, index: Int): Boolean {
        if (index !in 0 until line.length) return false
        val char = line.text[index]
        return if (char.isLowSurrogate() && index > 0) {
            Character.isDigit(line.text.codePointBefore(index + 1))
        } else {
            Character.isDigit(line.text.codePointAt(index))
        }
    }
}
