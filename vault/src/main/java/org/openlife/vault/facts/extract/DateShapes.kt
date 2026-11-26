package org.openlife.vault.facts.extract

import java.time.LocalDate

/**
 * The date shapes of ADR-0008 §4.1, read forward from a start offset. Each
 * function returns a token (valid or abstained) or null when the text is
 * not a date shape at all.
 */
internal object DateShapes {
    const val LONGEST_NAME = 9
    const val SHORTEST_NAME = 3
    const val MAX_SPACES = 3
    private const val YEAR_DIGITS = 4
    private const val SHORT = 2
    private const val ORDINAL = 2
    private const val CENTURY_19 = 1900
    private const val CENTURY_20 = 2000
    private const val NUMERIC_SEPARATORS = "/-."
    private const val CONTINUATION = "/.:,"

    /** A token starting with digits: year-first, day/month numeric, or day and month name. */
    fun numberFirst(line: LineScan, start: Int): DateToken? {
        val count = line.digits(start, YEAR_DIGITS)
        val next = line.ch(start + count)
        val numeric = next in NUMERIC_SEPARATORS && LineScan.isAsciiDigit(line.ch(start + count + 1))
        return when {
            count == YEAR_DIGITS && numeric -> yearFirst(line, start)
            count in 1..SHORT && numeric -> dayMonthNumeric(line, start, count)
            count in 1..SHORT -> dayThenName(line, start, count)
            else -> null
        }
    }

    private fun yearFirst(line: LineScan, start: Int): DateToken? {
        val sep = line.ch(start + YEAR_DIGITS)
        val monthAt = start + YEAR_DIGITS + 1
        val monthDigits = line.digits(monthAt, SHORT)
        val dayAt = monthAt + monthDigits + 1
        if (monthDigits !in 1..SHORT || line.ch(dayAt - 1) != sep) return null
        val dayDigits = line.digits(dayAt, SHORT)
        if (dayDigits !in 1..SHORT) return null
        val year = number(line, start, YEAR_DIGITS)
        val date = DateNames.date(year, number(line, monthAt, monthDigits), number(line, dayAt, dayDigits))
        return finish(line, start, dayAt + dayDigits, listOfNotNull(date), english = false)
    }

    private fun dayMonthNumeric(line: LineScan, start: Int, firstDigits: Int): DateToken? {
        val sep = line.ch(start + firstDigits)
        val secondAt = start + firstDigits + 1
        val secondDigits = line.digits(secondAt, YEAR_DIGITS)
        val afterSecond = secondAt + secondDigits
        val slash = sep == '/'
        if (secondDigits == YEAR_DIGITS) {
            return if (slash) noYear(line, start, afterSecond, AbstentionReason.YEAR_MONTH_ONLY) else null
        }
        if (secondDigits !in 1..SHORT) return null
        val hasThird = line.ch(afterSecond) == sep && LineScan.isAsciiDigit(line.ch(afterSecond + 1))
        if (!hasThird) return if (slash) noYear(line, start, afterSecond, AbstentionReason.NO_YEAR) else null
        val thirdAt = afterSecond + 1
        val thirdDigits = line.digits(thirdAt, YEAR_DIGITS)
        val a = number(line, start, firstDigits)
        val b = number(line, secondAt, secondDigits)
        val c = number(line, thirdAt, minOf(thirdDigits, YEAR_DIGITS))
        val end = thirdAt + thirdDigits
        return when (thirdDigits) {
            YEAR_DIGITS -> finish(line, start, end, fullYear(a, b, c), english = false)
            SHORT -> twoDigitYear(line, start..end, Triple(a, b, c), firstDigits == SHORT)
            else -> null
        }
    }

    private fun fullYear(a: Int, b: Int, year: Int): List<LocalDate> =
        listOfNotNull(DateNames.date(year, b, a), DateNames.date(year, a, b)).distinct()

    /** Both centuries of day/month and month/day, and year-first when the first group has two digits. */
    private fun twoDigitYear(
        line: LineScan,
        extent: IntRange,
        groups: Triple<Int, Int, Int>,
        yearFirst: Boolean,
    ): DateToken {
        val (a, b, c) = groups
        val values = mutableListOf<LocalDate>()
        for (century in listOf(CENTURY_19, CENTURY_20)) {
            values += listOfNotNull(DateNames.date(century + c, b, a), DateNames.date(century + c, a, b))
            if (yearFirst) values += listOfNotNull(DateNames.date(century + a, b, c))
        }
        val distinct = values.distinct()
        val token = finish(line, extent.first, extent.last, distinct, english = false)
        val single = token.reason == null && distinct.size == 1
        return if (single) token.abstained(AbstentionReason.AMBIGUOUS_FORM) else token
    }

    /** `D Month YYYY`, `Dth Month YYYY` and `D-Mon-YYYY`. */
    private fun dayThenName(line: LineScan, start: Int, dayDigits: Int): DateToken? {
        val day = number(line, start, dayDigits)
        var at = start + dayDigits
        if (line.ch(at) == '-') return dashed(line, start, day, at)
        at = afterOrdinal(line, at)
        val gap = line.spaces(at, MAX_SPACES)
        if (gap !in 1..MAX_SPACES) return null
        val nameStart = at + gap
        val nameEnd = line.asciiWordEnd(nameStart, LONGEST_NAME)
        if (nameEnd == nameStart || Character.isLetter(line.raw(nameEnd).code)) return null
        val month = DateNames.month(line.text.substring(nameStart, nameEnd)) ?: return null
        val afterName = if (line.ch(nameEnd) == '.') nameEnd + 1 else nameEnd
        val yearGap = line.spaces(afterName, MAX_SPACES)
        val yearAt = afterName + yearGap
        if (yearGap !in 1..MAX_SPACES || line.digits(yearAt, YEAR_DIGITS) != YEAR_DIGITS) {
            return noYear(line, start, afterName, AbstentionReason.NO_YEAR)
        }
        val date = DateNames.date(number(line, yearAt, YEAR_DIGITS), month, day)
        return finish(line, start, yearAt + YEAR_DIGITS, listOfNotNull(date), english = true)
    }

    private fun dashed(line: LineScan, start: Int, day: Int, dash: Int): DateToken? {
        val nameEnd = line.asciiWordEnd(dash + 1, LONGEST_NAME)
        if (nameEnd == dash + 1 || line.ch(nameEnd) != '-') return null
        val month = DateNames.abbreviation(line.text.substring(dash + 1, nameEnd)) ?: return null
        if (line.digits(nameEnd + 1, YEAR_DIGITS) != YEAR_DIGITS) return null
        val date = DateNames.date(number(line, nameEnd + 1, YEAR_DIGITS), month, day)
        return finish(line, start, nameEnd + 1 + YEAR_DIGITS, listOfNotNull(date), english = true)
    }

    /** `Month D, YYYY`, `Month Dth YYYY`, and the abstained `Month D` and `Month YYYY`. */
    fun monthFirst(line: LineScan, start: Int, wordEnd: Int, month: Int): DateToken? {
        val afterName = if (line.ch(wordEnd) == '.') wordEnd + 1 else wordEnd
        val gap = line.spaces(afterName, MAX_SPACES)
        val dayAt = afterName + gap
        val dayDigits = line.digits(dayAt, YEAR_DIGITS)
        if (gap !in 1..MAX_SPACES) return null
        if (dayDigits == YEAR_DIGITS) return noYear(line, start, dayAt + dayDigits, AbstentionReason.YEAR_MONTH_ONLY)
        if (dayDigits !in 1..SHORT) return null
        var at = dayAt + dayDigits
        at = afterOrdinal(line, at)
        val dayEnd = at
        if (line.ch(at) == ',') at++
        val yearGap = line.spaces(at, MAX_SPACES)
        val yearAt = at + yearGap
        if (yearGap !in 1..MAX_SPACES || line.digits(yearAt, YEAR_DIGITS) != YEAR_DIGITS) {
            return noYear(line, start, dayEnd, AbstentionReason.NO_YEAR)
        }
        val date = DateNames.date(number(line, yearAt, YEAR_DIGITS), month, number(line, dayAt, dayDigits))
        return finish(line, start, yearAt + YEAR_DIGITS, listOfNotNull(date), english = true)
            .copy(startsWithDigit = false)
    }

    /** Skips an English ordinal suffix (`st`, `nd`, `rd`, `th`) directly at [at]. */
    private fun afterOrdinal(line: LineScan, at: Int): Int {
        val end = at + ORDINAL
        val ordinal = line.asciiWordEnd(at, ORDINAL) == end && DateNames.isOrdinal(line.text.substring(at, end))
        return if (ordinal) end else at
    }

    private fun noYear(line: LineScan, start: Int, end: Int, reason: AbstentionReason): DateToken {
        val token = finish(line, start, end, emptyList(), english = false)
        return if (token.reason == AbstentionReason.ATTACHED_CHARACTERS) token else token.abstained(reason)
    }

    /**
     * Judges what follows the shape: a continuation (`/1`, `.13`) or attached
     * letters abstain the token; `T` and a digit joins a time (§4.3). No
     * valid value abstains it as invalid.
     */
    private fun finish(line: LineScan, start: Int, end: Int, values: List<LocalDate>, english: Boolean): DateToken {
        val token = DateToken(start, end, values, english)
        val next = line.raw(end)
        val nextDigit = LineScan.isAsciiDigit(line.raw(end + 1))
        return when {
            next in CONTINUATION && nextDigit -> continued(line, token)
            next == 'T' && nextDigit && values.isNotEmpty() -> token.copy(joinedByT = true)
            !line.boundaryAfter(end) -> token.abstained(AbstentionReason.ATTACHED_CHARACTERS)
            values.isEmpty() -> token.abstained(AbstentionReason.INVALID_VALUE)
            else -> token
        }
    }

    private fun continued(line: LineScan, token: DateToken): DateToken =
        token.abstained(AbstentionReason.ATTACHED_CHARACTERS, to = continuationEnd(line, token.end))

    private fun continuationEnd(line: LineScan, from: Int): Int {
        var end = from
        while (true) {
            val char = line.ch(end)
            val joins = char in CONTINUATION && LineScan.isAsciiDigit(line.ch(end + 1))
            if (!LineScan.isAsciiDigit(char) && !joins) return end
            end++
        }
    }

    private fun number(line: LineScan, start: Int, count: Int): Int = line.text.substring(start, start + count).toInt()
}
