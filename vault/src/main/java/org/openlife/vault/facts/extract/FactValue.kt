package org.openlife.vault.facts.extract

import java.time.LocalDate

/**
 * One typed value (ADR-0008 §2). Amounts are decimal strings, never binary
 * floating point; dates and times are floating local values with no zone.
 */
sealed interface FactValue {
    val kind: ExtractedKind

    /** ADR-0008 §3. */
    fun canonicalText(): String
}

/** A calendar date, proleptic Gregorian, year 1000..9999. */
data class DateValue(val date: LocalDate) : FactValue {
    init {
        require(date.year in MIN_YEAR..MAX_YEAR) { "Year out of range" }
    }

    override val kind: ExtractedKind get() = ExtractedKind.DATE

    override fun canonicalText(): String = date.toString()

    companion object {
        const val MIN_YEAR = 1000
        const val MAX_YEAR = 9999
    }
}

/** A 24-hour local time; [second] is set only when seconds were printed. */
data class TimeValue(val hour: Int, val minute: Int, val second: Int?) : FactValue {
    init {
        require(hour in 0..MAX_HOUR && minute in 0..MAX_MINUTE) { "Time out of range" }
        require(second == null || second in 0..MAX_MINUTE) { "Second out of range" }
    }

    override val kind: ExtractedKind get() = ExtractedKind.TIME

    override fun canonicalText(): String = buildString {
        append(two(hour)).append(':').append(two(minute))
        if (second != null) append(':').append(two(second))
    }

    /** Seconds since midnight, for chronological ordering only. */
    internal fun secondOfDay(): Int = (hour * MINUTES_PER_HOUR + minute) * SECONDS_PER_MINUTE + (second ?: 0)

    companion object {
        const val MAX_HOUR = 23
        const val MAX_MINUTE = 59
        private const val MINUTES_PER_HOUR = 60
        private const val SECONDS_PER_MINUTE = 60
        private const val TWO_DIGITS = 10

        private fun two(value: Int): String = if (value < TWO_DIGITS) "0$value" else value.toString()
    }
}

/** A date and a time printed together, both unambiguous (ADR-0008 §4.3). */
data class DateTimeValue(val date: DateValue, val time: TimeValue) : FactValue {
    override val kind: ExtractedKind get() = ExtractedKind.DATETIME

    override fun canonicalText(): String = date.canonicalText() + " " + time.canonicalText()
}

/**
 * An amount (ADR-0008 §5). [amount] is an optional `-`, integer digits with
 * no leading zero (except `0`), then optionally `.` and the printed fraction.
 * [currency] is an ISO 4217 code or null ("not stated"); [rawSymbol] is the
 * printed symbol or null when only a code was printed.
 */
data class MoneyValue(val amount: String, val currency: String?, val rawSymbol: String?) : FactValue {
    init {
        require(currency != null || rawSymbol != null) { "An amount needs a code or a symbol" }
    }

    override val kind: ExtractedKind get() = ExtractedKind.MONEY

    override fun canonicalText(): String = amount + " " + (currency ?: rawSymbol)
}

/** A URL, email address or phone number: its normalised form and the printed slice. */
data class TextValue(override val kind: ExtractedKind, val normalized: String, val raw: String) : FactValue {
    init {
        require(kind in TEXT_KINDS) { "Not a text kind" }
    }

    override fun canonicalText(): String = normalized

    private companion object {
        val TEXT_KINDS = setOf(ExtractedKind.URL, ExtractedKind.EMAIL, ExtractedKind.PHONE)
    }
}
