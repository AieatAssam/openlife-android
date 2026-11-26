package org.openlife.vault.facts.extract

/**
 * `rules-phone-v1` (ADR-0008 §7): a run needs a `+`, `tel:` or a
 * whole-token label before it. The whole maximal run is judged; a sub-run
 * is never taken.
 */
object PhoneExtractor {
    const val RULE_ID = "rules-phone"
    const val RULES_VERSION = "v1"
    private val RULE = RuleRef(RULE_ID, RULES_VERSION)

    private val LABELS = setOf("tel", "telephone", "phone", "ph", "mobile", "mob", "cell", "fax", "call")
    private const val LONGEST_LABEL = 9
    private const val MIN_DIGITS = 7
    private const val MAX_DIGITS = 15
    private const val MAX_PAREN_DIGITS = 4
    private const val CARD_GROUP = 4
    private const val CARD_GROUPS = 3
    private const val QUAD = 4
    private const val QUAD_PART = 3
    private val CARD_DIGITS = 13..19
    private const val SEPARATORS = " -."

    internal fun scan(line: LineScan) {
        var index = 0
        while (index < line.length) {
            line.reach(index)
            val char = line.ch(index)
            index = when {
                char == '+' && startsPlus(line, index) -> judge(line, index)
                LineScan.isAsciiLetter(char) && line.boundaryBefore(index) -> label(line, index)
                else -> index + 1
            }
        }
    }

    private fun startsPlus(line: LineScan, index: Int): Boolean =
        line.boundaryBefore(index) && LineScan.isAsciiDigit(line.ch(index + 1))

    private fun label(line: LineScan, start: Int): Int {
        val end = line.asciiWordEnd(start, LONGEST_LABEL)
        if (Character.isLetter(line.raw(end).code) || line.text.substring(start, end).lowercase() !in LABELS) {
            return maxOf(end, start + 1)
        }
        var at = end
        if (line.ch(at) == ':' || line.ch(at) == '.') at++
        if (line.ch(at) == ' ') at++
        val next = line.ch(at)
        val starts = LineScan.isAsciiDigit(next) || (next == '+' && LineScan.isAsciiDigit(line.ch(at + 1)))
        return if (starts) judge(line, at) else end
    }

    /** Reads the maximal run from [start] and judges it; returns where scanning continues. */
    private fun judge(line: LineScan, start: Int): Int {
        val groups = mutableListOf<String>()
        var at = if (line.ch(start) == '+') start + 1 else start
        do {
            val digits = line.digits(at, Int.MAX_VALUE - 1)
            val group = if (digits > 0) line.text.substring(at, at + digits) else "(${parenGroup(line, at)})"
            groups += group
            at += group.length
            if (!startsGroup(line, at) && line.ch(at) in SEPARATORS && startsGroup(line, at + 1)) at++
        } while (startsGroup(line, at))
        val end = continuationEnd(line, at)
        val reason = reason(line, start, at, end, groups)
        if (reason != null) {
            line.abstain(ExtractedKind.PHONE, start, end, reason)
        } else {
            val normalized = normalized(line.text[start] == '+', groups)
            val value = TextValue(ExtractedKind.PHONE, normalized, line.text.substring(start, end))
            line.emit(LineScan.Output(ExtractedKind.PHONE, start, end, listOf(value), RULE))
        }
        return maxOf(end, start + 1)
    }

    private fun startsGroup(line: LineScan, at: Int): Boolean =
        LineScan.isAsciiDigit(line.ch(at)) || parenGroup(line, at) != null

    private fun parenGroup(line: LineScan, at: Int): String? {
        if (line.ch(at) != '(') return null
        val digits = line.digits(at + 1, MAX_PAREN_DIGITS)
        val close = at + 1 + digits
        return if (digits in 1..MAX_PAREN_DIGITS && line.ch(close) == ')') line.text.substring(at + 1, close) else null
    }

    /** A run followed by `/` or `:` and digits is abstained through them (§7). */
    private fun continuationEnd(line: LineScan, runEnd: Int): Int {
        var end = runEnd
        while ((line.ch(end) == '/' || line.ch(end) == ':') && LineScan.isAsciiDigit(line.ch(end + 1))) {
            end++
            while (LineScan.isAsciiDigit(line.ch(end))) end++
        }
        return end
    }

    private fun reason(line: LineScan, start: Int, runEnd: Int, end: Int, groups: List<String>): AbstentionReason? {
        val digits = groups.sumOf { group -> group.count(LineScan::isAsciiDigit) }
        val text = line.text.substring(start, runEnd)
        val bare = groups.filter { it.all(LineScan::isAsciiDigit) }
        return when {
            end > runEnd -> AbstentionReason.INVALID_VALUE
            line.isClaimed(runEnd) || !line.boundaryAfter(runEnd) -> AbstentionReason.ATTACHED_CHARACTERS
            touchesClaim(line, runEnd) -> AbstentionReason.OVERLAPS_CLAIM
            dottedQuad(text) -> AbstentionReason.DOTTED_QUAD
            cardLike(bare, digits, groups.size) -> AbstentionReason.CARD_LIKE
            digits !in MIN_DIGITS..MAX_DIGITS -> AbstentionReason.LIMIT
            else -> null
        }
    }

    /** The run stopped at a separator before a character another rule claimed. */
    private fun touchesClaim(line: LineScan, runEnd: Int): Boolean =
        line.raw(runEnd) in SEPARATORS && line.isClaimed(runEnd + 1) && LineScan.isAsciiDigit(line.raw(runEnd + 1))

    private fun dottedQuad(text: String): Boolean {
        val parts = text.split('.')
        return parts.size == QUAD && parts.all { it.length in 1..QUAD_PART && it.all(LineScan::isAsciiDigit) }
    }

    private fun cardLike(bare: List<String>, digits: Int, groupCount: Int): Boolean {
        val fours = bare.count { it.length == CARD_GROUP }
        return fours >= CARD_GROUPS || (digits in CARD_DIGITS && fours == groupCount)
    }

    private fun normalized(plus: Boolean, groups: List<String>): String {
        val kept = if (plus) groups.filter { it != "(0)" } else groups
        return (if (plus) "+" else "") + kept.joinToString("") { group -> group.filter(LineScan::isAsciiDigit) }
    }
}
