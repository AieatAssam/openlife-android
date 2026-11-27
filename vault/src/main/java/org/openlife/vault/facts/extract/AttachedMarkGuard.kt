package org.openlife.vault.facts.extract

/**
 * ADR-0008 §1 (amendment A-6, finding EE-04): a format or combining mark
 * (Unicode Cf, Mn, Mc, Me) attaches to its neighbours. The rules already
 * abstain a token a mark trails or splits once they have read it; a mark
 * that leads a token stops them reading it at all. This last pass makes
 * every such token visible: for each unclaimed run of marks, the
 * surrounding unclaimed token (bounded by whitespace or claimed text)
 * either joins an abstention it touches, keeping that abstention's kind,
 * or, when some rule could read it (an ASCII digit, `@`, `//` or `www.`),
 * is abstained with no kind. Emoji sequences and words stay untouched.
 * Each character is visited a constant number of times.
 */
internal object AttachedMarkGuard {
    fun scan(line: LineScan) {
        val starts = HashMap<Int, Int>()
        val ends = HashMap<Int, Int>()
        line.abstentions.forEachIndexed { index, abstention ->
            starts[abstention.startOffset] = index
            ends[abstention.endOffset] = index
        }
        var index = 0
        while (index < line.length) {
            line.reach(index)
            val mark = !line.isClaimed(index) && LineScan.isAttachedMark(line.text.codePointAt(index))
            index = if (mark) judge(line, index, starts, ends) else index + 1
        }
    }

    private fun judge(line: LineScan, at: Int, starts: Map<Int, Int>, ends: Map<Int, Int>): Int {
        var start = at
        while (start > 0 && inToken(line, start - 1)) start--
        var end = line.marksEnd(at)
        while (end < line.length && inToken(line, end)) end++
        val after = starts[end]?.takeIf { line.isClaimed(end) }
        val before = ends[start]?.takeIf { start > 0 && line.isClaimed(start - 1) }
        val reason = AbstentionReason.ATTACHED_CHARACTERS
        when {
            after != null -> extend(line, after, start, null)
            before != null -> extend(line, before, null, end)
            readable(line.text.substring(start, end)) -> line.abstain(null, start, end, reason)
        }
        return maxOf(end, at + 1)
    }

    /** A token some rule could read: an ASCII digit, `@`, or a URL start (`//`, `www.`). */
    private fun readable(token: String): Boolean =
        token.any { LineScan.isAsciiDigit(it) || it == '@' } || "//" in token ||
            token.contains("www.", ignoreCase = true)

    private fun inToken(line: LineScan, index: Int): Boolean {
        val char = line.text[index]
        return !line.isClaimed(index) && !Character.isWhitespace(char) && !LineScan.isSpace(char)
    }

    private fun extend(line: LineScan, index: Int, start: Int?, end: Int?) {
        val old = line.abstentions[index]
        val wider = old.copy(startOffset = start ?: old.startOffset, endOffset = end ?: old.endOffset)
        line.claim(wider.startOffset, wider.endOffset)
        line.abstentions[index] = wider
    }
}
