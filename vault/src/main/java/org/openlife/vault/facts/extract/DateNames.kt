package org.openlife.vault.facts.extract

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Reference data of `rules-date-v1` (ADR-0008 §1: part of the version):
 * English month and weekday names, and the range words of §4.1.
 */
internal object DateNames {
    private val MONTH_NAMES = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )
    private const val ABBREVIATION = 3
    private const val SEPT = "sept"
    private const val SEPTEMBER = 9

    private val WEEKDAYS: Map<String, DayOfWeek> = DayOfWeek.entries.flatMap { day ->
        val name = day.name.lowercase()
        listOf(name to day, name.take(ABBREVIATION) to day)
    }.toMap()

    /** Words that join a range: §4.1 for dates, §4.2 for times (lowercase). */
    val RANGE_WORDS = listOf("until", "till", "to")

    /** ZZ-06: words between `<digits>` and `D Month YYYY` that make a range. */
    val RANGE_PREFIX_WORDS = listOf("until", "till", "and", "to")

    /** Dashes that join a range. */
    const val DASHES = "-–—"

    /** Month 1..12 for a full English name, its three-letter abbreviation or `Sept`, in any case. */
    fun month(word: String): Int? {
        val lower = word.lowercase()
        val full = MONTH_NAMES.indexOf(lower)
        return when {
            full >= 0 -> full + 1
            lower == SEPT -> SEPTEMBER
            else -> abbreviation(lower)
        }
    }

    /** Month 1..12 for a three-letter abbreviation or `Sept` only (the `D-Mon-YYYY` form). */
    fun abbreviation(word: String): Int? {
        val lower = word.lowercase()
        if (lower == SEPT) return SEPTEMBER
        val index = MONTH_NAMES.indexOfFirst { lower.length == ABBREVIATION && it.startsWith(lower) }
        return if (index >= 0) index + 1 else null
    }

    fun weekday(word: String): DayOfWeek? = WEEKDAYS[word.lowercase()]

    fun isOrdinal(word: String): Boolean = word.lowercase() in setOf("st", "nd", "rd", "th")

    /** The date if it exists in the proleptic Gregorian calendar with a year in 1000..9999. */
    fun date(year: Int, month: Int, day: Int): LocalDate? {
        val valid = year in DateValue.MIN_YEAR..DateValue.MAX_YEAR && month in 1..MONTHS &&
            day >= 1 && YearMonth.of(year, month).isValidDay(day)
        return if (valid) LocalDate.of(year, month, day) else null
    }

    private const val MONTHS = 12
}
