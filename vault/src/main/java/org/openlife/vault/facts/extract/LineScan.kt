package org.openlife.vault.facts.extract

import java.math.BigDecimal

/** Identity of one rule, as written to `methodId` and `methodVersion`. */
internal data class RuleRef(val id: String, val version: String)

/**
 * The common tokenizer state for one span's read text: the claimed-character
 * mask shared by every rule (ADR-0008 §1), the outputs, and the cooperative
 * deadline (C2-R29). Every scan is a forward walk; no rule looks back more
 * than a constant window.
 */
internal class LineScan(val span: ExtractionSpan, private val deadline: ExtractionDeadline) {
    val text: String = span.text
    val length: Int = text.length
    private val claimed = BooleanArray(length)
    private var nextCheck = 0
    val candidates = mutableListOf<ExtractedCandidate>()
    val abstentions = mutableListOf<Abstention>()

    /** Starts a rule's walk over the text: the deadline is checked again from offset 0. */
    fun beginStage() {
        nextCheck = 0
    }

    /** Called with the scan position; checks the deadline once per 1,024 characters passed. */
    fun reach(index: Int) {
        while (index >= nextCheck) {
            deadline.check()
            nextCheck += CHECK_INTERVAL
        }
    }

    fun isClaimed(index: Int): Boolean = index in 0 until length && claimed[index]

    fun anyClaimed(start: Int, end: Int): Boolean = (maxOf(0, start) until minOf(length, end)).any { claimed[it] }

    fun claim(start: Int, end: Int) {
        for (index in maxOf(0, start) until minOf(length, end)) claimed[index] = true
    }

    /** The character at [index] if it exists and is unclaimed, otherwise [NONE]. */
    fun ch(index: Int): Char = if (index in 0 until length && !claimed[index]) text[index] else NONE

    /** The raw character at [index], or [NONE] past either end. */
    fun raw(index: Int): Char = if (index in 0 until length) text[index] else NONE

    /** ADR-0008 §1: no letter or digit (any script) immediately before [index]. */
    fun boundaryBefore(index: Int): Boolean = index <= 0 || !Character.isLetterOrDigit(text.codePointBefore(index))

    /** ADR-0008 §1: no letter or digit (any script) at [index]. */
    fun boundaryAfter(index: Int): Boolean = index >= length || !Character.isLetterOrDigit(text.codePointAt(index))

    fun matchesIgnoreCase(start: Int, word: String): Boolean =
        start >= 0 && start + word.length <= length && text.regionMatches(start, word, 0, word.length, true) &&
            !anyClaimed(start, start + word.length)

    fun abstain(kind: ExtractedKind?, start: Int, end: Int, reason: AbstentionReason) {
        claim(start, end)
        abstentions += Abstention(kind, span.spanId, start, end, reason)
    }

    /** One value or candidate set ready to emit over `[start, end)`. */
    data class Output(
        val kind: ExtractedKind,
        val start: Int,
        val end: Int,
        val values: List<FactValue>,
        val rule: RuleRef,
        val english: Boolean = false,
        val context: List<CurrencyContext> = emptyList(),
    )

    companion object {
        const val NONE: Char = '\u0000'
        const val CHECK_INTERVAL = 1_024
        const val MAX_VALUE_BYTES = 4_096

        fun isAsciiDigit(char: Char): Boolean = char in '0'..'9'

        fun isAsciiLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'

        fun isAsciiUpper(char: Char): Boolean = char in 'A'..'Z'

        /** ADR-0008 §2 ordering inside one candidate set; amounts compare as decimals, never binary floats. */
        val VALUE_ORDER: Comparator<FactValue> = Comparator { left, right -> compareValues(left, right) }

        private fun compareValues(left: FactValue, right: FactValue): Int = when {
            left is DateValue && right is DateValue -> left.date.compareTo(right.date)
            left is TimeValue && right is TimeValue -> left.secondOfDay().compareTo(right.secondOfDay())
            left is DateTimeValue && right is DateTimeValue -> compareDateTimes(left, right)
            left is MoneyValue && right is MoneyValue -> compareMoney(left, right)
            else -> left.canonicalText().compareTo(right.canonicalText())
        }

        private fun compareDateTimes(left: DateTimeValue, right: DateTimeValue): Int {
            val byDate = left.date.date.compareTo(right.date.date)
            return if (byDate != 0) byDate else left.time.secondOfDay().compareTo(right.time.secondOfDay())
        }

        private fun compareMoney(left: MoneyValue, right: MoneyValue): Int {
            val byNumber = BigDecimal(left.amount).compareTo(BigDecimal(right.amount))
            val byString = left.amount.compareTo(right.amount)
            return when {
                byNumber != 0 -> byNumber
                byString != 0 -> byString
                else -> compareCurrency(left.currency, right.currency)
            }
        }

        private fun compareCurrency(left: String?, right: String?): Int = when {
            left == right -> 0
            left == null -> -1
            right == null -> 1
            else -> left.compareTo(right)
        }
    }
}
