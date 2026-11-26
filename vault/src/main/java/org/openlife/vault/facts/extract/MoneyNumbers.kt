package org.openlife.vault.facts.extract

/** The readings of one printed number, or why it is abstained (ADR-0008 §5.2). */
internal data class Readings(val amounts: List<String>, val reason: AbstentionReason? = null) {
    /** Every reading has printed fraction digits (§5.1, English-word codes). */
    val allFractional: Boolean get() = amounts.isNotEmpty() && amounts.all { '.' in it }
}

/**
 * ADR-0008 §5.2 digits and separators. Amounts are decimal strings built
 * from the printed digits; no binary floating point is involved (C2-R47).
 */
internal object MoneyNumbers {
    const val NBSP = ' '
    const val NNBSP = ' '
    const val SEPARATORS = ",.'$NBSP$NNBSP"
    private const val GROUP = 3
    private const val MAX_FRACTION_FOR_DECIMAL = 2
    private const val MAX_INTEGER = 15
    private const val MAX_FRACTION = 3
    private val GROUPING_ONLY = setOf('\'', NBSP, NNBSP)

    /** Readings of a dense number: ASCII digits with single inner separators. */
    fun read(printed: String): Readings {
        val separators = printed.filter { it in SEPARATORS }.toSet()
        val grouping = separators.intersect(GROUPING_ONLY)
        val decimals = separators - GROUPING_ONLY
        return when {
            grouping.size > 1 -> abstain(AbstentionReason.AMBIGUOUS_FORM)
            decimals.size == 2 -> bothMarks(printed, grouping)
            decimals.size == 1 -> oneMark(printed, decimals.single(), grouping.isNotEmpty())
            else -> grouped(printed, grouping.singleOrNull(), null)
        }
    }

    /** Comma and dot both present: the last is the decimal separator, the other groups. */
    private fun bothMarks(printed: String, grouping: Set<Char>): Readings {
        val decimal = printed.last { it == ',' || it == '.' }
        val group = if (decimal == ',') '.' else ','
        val decimalAt = printed.lastIndexOf(decimal)
        val once = printed.indexOf(decimal) == decimalAt
        val valid = grouping.isEmpty() && once && printed.lastIndexOf(group) < decimalAt
        if (!valid) return abstain(AbstentionReason.AMBIGUOUS_FORM)
        return grouped(printed.substring(0, decimalAt), group, printed.substring(decimalAt + 1))
    }

    private fun oneMark(printed: String, mark: Char, otherGrouping: Boolean): Readings {
        val count = printed.count { it == mark }
        val at = printed.indexOf(mark)
        val fraction = printed.length - at - 1
        return when {
            count > 1 -> if (otherGrouping) abstain(AbstentionReason.AMBIGUOUS_FORM) else grouped(printed, mark, null)
            fraction <= MAX_FRACTION_FOR_DECIMAL -> decimal(printed, at)
            fraction == GROUP -> both(printed, mark, at, otherGrouping)
            else -> abstain(AbstentionReason.AMBIGUOUS_FORM)
        }
    }

    /**
     * One comma or dot before exactly 3 digits: the decimal reading and the
     * grouping reading. When the grouping reading cannot exist (a first
     * group over 3 digits, a first group led by `0`, or another grouping
     * character) the form is abstained: `1234,567`, `0.500` and `1'234,567`
     * (amendments, rulings Q11 and E-10).
     */
    private fun both(printed: String, mark: Char, at: Int, otherGrouping: Boolean): Readings {
        val decimal = decimal(printed, at)
        if (decimal.reason != null) return decimal
        if (otherGrouping || at > GROUP || printed.first() == '0') return abstain(AbstentionReason.AMBIGUOUS_FORM)
        val group = grouped(printed, mark, null)
        return Readings(decimal.amounts + group.takeIf { it.reason == null }?.amounts.orEmpty())
    }

    private fun decimal(printed: String, at: Int): Readings =
        grouped(printed.substring(0, at), groupingOf(printed), printed.substring(at + 1))

    private fun groupingOf(printed: String): Char? = printed.firstOrNull { it in GROUPING_ONLY }

    /** Integer part grouped by [group] (first group 1-3 digits, then 3), and an optional fraction. */
    private fun grouped(integer: String, group: Char?, fraction: String?): Readings {
        val groups = if (group == null) listOf(integer) else integer.split(group)
        val sized = group == null || (groups.first().length <= GROUP && groups.drop(1).all { it.length == GROUP })
        val shaped = sized && groups.all { it.isNotEmpty() && it.all(LineScan::isAsciiDigit) }
        if (!shaped || fraction?.all(LineScan::isAsciiDigit) == false) return abstain(AbstentionReason.AMBIGUOUS_FORM)
        val digits = groups.joinToString("").trimStart('0').ifEmpty { "0" }
        val tooLong = digits.length > MAX_INTEGER || (fraction?.length ?: 0) > MAX_FRACTION
        if (tooLong) return abstain(AbstentionReason.LIMIT)
        return Readings(listOf(if (fraction == null) digits else "$digits.$fraction"))
    }

    /**
     * Joins ASCII-space-separated digit groups read as one grouped number
     * (§5.2). The printed digits are joined first and leading zeros removed
     * after, so `5 000` is 5000 (finding E-01).
     */
    fun joined(groups: List<String>): Readings {
        if (!groups.all { group -> group.isNotEmpty() && group.all(LineScan::isAsciiDigit) }) {
            return abstain(AbstentionReason.AMBIGUOUS_FORM)
        }
        val digits = groups.joinToString("").trimStart('0').ifEmpty { "0" }
        return if (digits.length > MAX_INTEGER) abstain(AbstentionReason.LIMIT) else Readings(listOf(digits))
    }

    private fun abstain(reason: AbstentionReason) = Readings(emptyList(), reason)
}
