package org.openlife.vault.facts.extract

/**
 * ADR-0008 §5.1 forms that abstain an amount: ranges, a trailing minus,
 * `25,-`, parentheses, `CR`/`DR`, a marker with numbers on both sides, and
 * attached characters. Each check reads a constant window.
 */
internal object MoneyProblems {
    private const val CREDIT_LENGTH = 2
    private const val RANGE_WORD = "to"
    private val CREDITS = listOf("CR", "DR")

    /** The first form that abstains [amount], as `(start, end, reason)`, or null. */
    fun problem(scene: MoneyScene, amount: Amount): Triple<Int, Int, AbstentionReason>? = rangeBefore(scene, amount)
        ?: rangeAfter(scene, amount)
        ?: bothSides(scene, amount)
        ?: trailing(scene.line, amount)
        ?: enclosed(scene.line, amount)

    private fun rangeBefore(scene: MoneyScene, amount: Amount): Triple<Int, Int, AbstentionReason>? {
        val line = scene.line
        var at = amount.start
        if (line.ch(at - 1) == ' ') at--
        at = when {
            line.ch(at - 1) in DateNames.DASHES -> at - 1
            line.matchesIgnoreCase(at - RANGE_WORD.length, RANGE_WORD) && line.raw(at) == ' ' -> at - RANGE_WORD.length
            else -> return null
        }
        val partner = scene.numberEndingAt(at) ?: return null
        return Triple(partner.start, amount.end, AbstentionReason.RANGE)
    }

    private fun rangeAfter(scene: MoneyScene, amount: Amount): Triple<Int, Int, AbstentionReason>? {
        if (amount.suffix != null) return null
        val line = scene.line
        var at = amount.end
        if (line.ch(at) == ' ') at++
        at = when {
            line.ch(at) in DateNames.DASHES -> at + 1
            line.matchesIgnoreCase(at, RANGE_WORD) && line.raw(at + RANGE_WORD.length) == ' ' -> at + RANGE_WORD.length
            else -> return null
        }
        val partner = scene.numberStartingAt(at) ?: return null
        return Triple(amount.start, partner.end, AbstentionReason.RANGE)
    }

    private fun bothSides(scene: MoneyScene, amount: Amount): Triple<Int, Int, AbstentionReason>? {
        val after = amount.suffix?.let { scene.numberStartingAt(it.end) }
        val before = amount.prefix?.takeIf { !amount.negative }?.let { scene.numberEndingAt(it.start) }
        return when {
            after != null -> Triple(amount.start, after.end, AbstentionReason.AMBIGUOUS_FORM)
            before != null -> Triple(before.start, amount.end, AbstentionReason.AMBIGUOUS_FORM)
            else -> null
        }
    }

    /** `25,-`, `25.-`, `25,--` and a trailing minus (`25.00-`, `25.00 -`). */
    private fun trailing(line: LineScan, amount: Amount): Triple<Int, Int, AbstentionReason>? {
        var at = amount.end
        if (line.ch(at) == ',' || line.ch(at) == '.') {
            if (line.ch(at + 1) != '-') return null
            at++
            while (line.ch(at) == '-') at++
            return Triple(amount.start, at, AbstentionReason.SIGN_OR_CREDIT)
        }
        if (line.ch(at) == ' ') at++
        val dash = line.ch(at)
        return if (dash == '-' || dash == '–') Triple(amount.start, at + 1, AbstentionReason.SIGN_OR_CREDIT) else null
    }

    /** Parentheses, `CR`/`DR`, and letters or digits attached after the amount. */
    private fun enclosed(line: LineScan, amount: Amount): Triple<Int, Int, AbstentionReason>? {
        val creditAt = if (line.ch(amount.end) == ' ') amount.end + 1 else amount.end
        val creditEnd = creditAt + CREDIT_LENGTH
        val credit = CREDITS.any { line.text.startsWith(it, creditAt) } && line.boundaryAfter(creditEnd) &&
            !line.anyClaimed(creditAt, creditEnd)
        return when {
            line.ch(amount.start - 1) == '(' && line.ch(amount.end) == ')' -> parenthesised(amount)
            credit -> Triple(amount.start, creditEnd, AbstentionReason.SIGN_OR_CREDIT)
            !line.boundaryAfter(amount.end) -> attached(line, amount)
            else -> null
        }
    }

    private fun parenthesised(amount: Amount) =
        Triple(amount.start - 1, amount.end + 1, AbstentionReason.SIGN_OR_CREDIT)

    private fun attached(line: LineScan, amount: Amount) =
        Triple(amount.start, line.tokenEnd(amount.end), AbstentionReason.ATTACHED_CHARACTERS)
}
