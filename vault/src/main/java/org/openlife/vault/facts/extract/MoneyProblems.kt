package org.openlife.vault.facts.extract

/**
 * ADR-0008 §5.1 forms that abstain an amount: ranges, signs it does not
 * read, a trailing minus, `25,-`, parentheses, `CR`/`DR`, a marker with
 * numbers on both sides, and attached characters. Each check reads a
 * constant window.
 */
internal object MoneyProblems {
    private const val AND = "and"
    private val RANGE_WORDS = listOf("to", "until", "till", AND)

    /** The other end of a range: `[start, end)` and the marker it carries, if any. */
    private data class Partner(val start: Int, val end: Int, val marker: Marker?)

    /** The first form that abstains [amount], as `(start, end, reason)`, or null. */
    fun problem(scene: MoneyScene, amount: Amount): Triple<Int, Int, AbstentionReason>? = rangeBefore(scene, amount)
        ?: rangeAfter(scene, amount)
        ?: signed(amount)
        ?: bothSides(scene, amount)
        ?: trailing(scene.line, amount)
        ?: MoneyEnclosed.problem(scene.line, amount)

    /**
     * A partner amount before this one, joined by a dash-like character or
     * `to`/`until`/`till`/`and` with up to 4 space separators each side; the
     * partner's own markers join the range (amendment E-07). `and` joins
     * only two amounts with the same marker (`between €10 and €20`), so
     * `$5 and £10` stays two amounts.
     */
    private fun rangeBefore(scene: MoneyScene, amount: Amount): Triple<Int, Int, AbstentionReason>? {
        val line = scene.line
        val gap = line.spaceRunBefore(amount.coreStart)
        if (gap > LineScan.MAX_SPACE_RUN) return null
        val (joinerStart, word) = joinerBefore(line, amount.coreStart - gap) ?: return null
        val partnerGap = line.spaceRunBefore(joinerStart)
        if (partnerGap > LineScan.MAX_SPACE_RUN) return null
        val partner = partnerBefore(scene, joinerStart - partnerGap) ?: return null
        if (word == AND && !sameMarker(partner.marker, amount)) return null
        return Triple(partner.start, amount.end, AbstentionReason.RANGE)
    }

    private fun rangeAfter(scene: MoneyScene, amount: Amount): Triple<Int, Int, AbstentionReason>? {
        val line = scene.line
        val gap = line.spaceRun(amount.end)
        if (gap > LineScan.MAX_SPACE_RUN) return null
        val (joinerEnd, word) = joinerAfter(line, amount.end + gap) ?: return null
        val partnerGap = line.spaceRun(joinerEnd)
        if (partnerGap > LineScan.MAX_SPACE_RUN) return null
        val partner = partnerAfter(scene, joinerEnd + partnerGap) ?: return null
        if (word == AND && !sameMarker(partner.marker, amount)) return null
        return Triple(amount.start, partner.end, AbstentionReason.RANGE)
    }

    /** A dash-like character or a whole range word ending at [end]: its start and the word ("" for a dash). */
    private fun joinerBefore(line: LineScan, end: Int): Pair<Int, String>? {
        if (LineScan.isDashLike(line.ch(end - 1))) return end - 1 to ""
        val word = RANGE_WORDS.firstOrNull { word ->
            val start = end - word.length
            line.matchesIgnoreCase(start, word) && line.boundaryBefore(start) && line.boundaryAfter(end)
        } ?: return null
        return end - word.length to word
    }

    /** A dash-like character or a whole range word starting at [start]: its end and the word ("" for a dash). */
    private fun joinerAfter(line: LineScan, start: Int): Pair<Int, String>? {
        if (LineScan.isDashLike(line.ch(start))) return start + 1 to ""
        val word = RANGE_WORDS.firstOrNull { word ->
            line.matchesIgnoreCase(start, word) && line.boundaryBefore(start) && line.boundaryAfter(start + word.length)
        } ?: return null
        return start + word.length to word
    }

    /** A number ending at [end] or before a marker ending there, with a marker before it if none after. */
    private fun partnerBefore(scene: MoneyScene, end: Int): Partner? {
        val suffix = scene.markerEndingAt(end)
        val number = scene.numberEndingAt(suffix?.start ?: end) ?: return null
        val prefix = if (suffix == null) markerBefore(scene, number.start) else null
        return Partner(prefix?.start ?: number.start, end, suffix ?: prefix)
    }

    /** A number starting at [start] or after a marker starting there, with a marker after it if none before. */
    private fun partnerAfter(scene: MoneyScene, start: Int): Partner? {
        val prefix = scene.markerAt(start)
        val number = scene.numberStartingAt(prefix?.end ?: start) ?: return null
        val suffix = if (prefix == null) markerAfter(scene, number.end) else null
        return Partner(start, suffix?.end ?: number.end, prefix ?: suffix)
    }

    private fun markerBefore(scene: MoneyScene, at: Int): Marker? = scene.markerEndingAt(at)
        ?: scene.markerEndingAt(at - 1)?.takeIf { LineScan.isSpace(scene.line.raw(at - 1)) }

    private fun markerAfter(scene: MoneyScene, at: Int): Marker? = scene.markerAt(at)
        ?: scene.markerAt(at + 1)?.takeIf { LineScan.isSpace(scene.line.raw(at)) }

    private fun sameMarker(partner: Marker?, amount: Amount): Boolean {
        val own = amount.prefix ?: amount.suffix
        return partner != null && own != null && partner.symbol == own.symbol && partner.code == own.code
    }

    private fun signed(amount: Amount): Triple<Int, Int, AbstentionReason>? =
        if (amount.signProblem) Triple(amount.start, amount.end, AbstentionReason.SIGN_OR_CREDIT) else null

    private fun bothSides(scene: MoneyScene, amount: Amount): Triple<Int, Int, AbstentionReason>? {
        val after = amount.suffix?.let { scene.numberStartingAt(it.end) }
        val before = amount.prefix?.takeIf { !amount.negative }?.let { scene.numberEndingAt(it.start) }
        return when {
            after != null -> Triple(amount.start, after.end, AbstentionReason.AMBIGUOUS_FORM)
            before != null -> Triple(before.start, amount.end, AbstentionReason.AMBIGUOUS_FORM)
            else -> null
        }
    }

    /** `25,-`, `25.-`, `25,--` and a trailing dash-like minus up to 4 space separators after (`25.00 −`). */
    private fun trailing(line: LineScan, amount: Amount): Triple<Int, Int, AbstentionReason>? {
        var at = amount.end
        if (line.ch(at) == ',' || line.ch(at) == '.') {
            if (line.ch(at + 1) != '-') return null
            at++
            while (line.ch(at) == '-') at++
            return Triple(amount.start, at, AbstentionReason.SIGN_OR_CREDIT)
        }
        val gap = line.spaceRun(at)
        val dash = at + gap
        val minus = gap <= LineScan.MAX_SPACE_RUN && LineScan.isDashLike(line.ch(dash))
        return if (minus) Triple(amount.start, dash + 1, AbstentionReason.SIGN_OR_CREDIT) else null
    }
}

/** ADR-0008 §5.1 forms around an amount: parentheses, `CR`/`DR`, and attached characters or marks. */
internal object MoneyEnclosed {
    private const val CREDIT_LENGTH = 2
    private val CREDITS = listOf("CR", "DR")

    /** Parentheses (with or without inner spaces), `CR`/`DR`, and characters or marks attached either side. */
    fun problem(line: LineScan, amount: Amount): Triple<Int, Int, AbstentionReason>? {
        val creditAt = if (line.ch(amount.end) == ' ') amount.end + 1 else amount.end
        val creditEnd = creditAt + CREDIT_LENGTH
        val credit = CREDITS.any { line.text.startsWith(it, creditAt) } && line.boundaryAfter(creditEnd) &&
            !line.anyClaimed(creditAt, creditEnd)
        return parenthesised(line, amount) ?: when {
            credit -> Triple(amount.start, creditEnd, AbstentionReason.SIGN_OR_CREDIT)
            !line.boundaryAfter(amount.end) -> attached(line, amount)
            markBefore(line, amount.start) -> markAttached(line, amount)
            else -> null
        }
    }

    private fun parenthesised(line: LineScan, amount: Amount): Triple<Int, Int, AbstentionReason>? {
        val open = amount.start - 1 - line.spaceRunBefore(amount.start)
        val close = amount.end + line.spaceRun(amount.end)
        val inside = amount.start - open <= LineScan.MAX_SPACE_RUN + 1 && close - amount.end <= LineScan.MAX_SPACE_RUN
        val enclosed = inside && line.ch(open) == '(' && line.ch(close) == ')'
        return if (enclosed) Triple(open, close + 1, AbstentionReason.SIGN_OR_CREDIT) else null
    }

    private fun attached(line: LineScan, amount: Amount) =
        Triple(amount.start, line.tokenEnd(amount.end), AbstentionReason.ATTACHED_CHARACTERS)

    /** A format or combining mark directly before the amount (`​€5`; amendment E-06). */
    private fun markBefore(line: LineScan, at: Int): Boolean =
        at > 0 && !line.isClaimed(at - 1) && LineScan.isAttachedMark(line.text.codePointBefore(at))

    private fun markAttached(line: LineScan, amount: Amount): Triple<Int, Int, AbstentionReason> {
        val width = Character.charCount(line.text.codePointBefore(amount.start))
        return Triple(amount.start - width, amount.end, AbstentionReason.ATTACHED_CHARACTERS)
    }
}
