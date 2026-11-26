package org.openlife.vault.facts.extract

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * A recognised date shape (ADR-0008 §4.1). [values] is empty and [reason]
 * set when it is abstained; it claims `[start, end)` either way.
 */
internal data class DateToken(
    val start: Int,
    val end: Int,
    val values: List<LocalDate>,
    val english: Boolean,
    val reason: AbstentionReason? = null,
    val joinedByT: Boolean = false,
    val startsWithDigit: Boolean = true,
    val twoDigitYear: Boolean = false,
) {
    fun abstained(why: AbstentionReason, from: Int = start, to: Int = end): DateToken =
        copy(start = from, end = to, values = emptyList(), reason = why, joinedByT = false)
}

/**
 * `rules-date-v1` (ADR-0008 §4.1): ISO and year-first forms, numeric
 * day/month forms with both readings, two-digit years with both centuries
 * and year-first, English month names, weekday filtering, and ranges, which
 * are abstained and claimed as a whole.
 */
object DateExtractor {
    const val RULE_ID = "rules-date"
    const val RULES_VERSION = "v1"
    internal val RULE = RuleRef(RULE_ID, RULES_VERSION)

    /** Finds every date token, records abstentions, claims all of them, and returns the valid ones. */
    internal fun scan(line: LineScan): List<DateToken> {
        val tokens = mutableListOf<DateToken>()
        var index = 0
        while (index < line.length) {
            line.reach(index)
            val token = tokenAt(line, index)
            if (token == null) index++ else tokens += token.also { index = it.end }
        }
        val judged = DateRanges.apply(line, tokens)
        for (token in judged) {
            val reason = token.reason
            if (reason == null) {
                line.claim(token.start, token.end)
            } else {
                line.abstain(ExtractedKind.DATE, token.start, token.end, reason)
            }
        }
        return judged.filter { it.reason == null }
    }

    private fun tokenAt(line: LineScan, index: Int): DateToken? {
        val char = line.ch(index)
        return when {
            LineScan.isAsciiDigit(char) && NumericStarts.canStart(line, index) -> DateShapes.numberFirst(line, index)
            LineScan.isAsciiLetter(char) && line.boundaryBefore(index) -> wordFirst(line, index)
            else -> null
        }
    }

    private fun wordFirst(line: LineScan, start: Int): DateToken? {
        val end = line.asciiWordEnd(start, DateShapes.LONGEST_NAME)
        val name = end - start in DateShapes.SHORTEST_NAME..DateShapes.LONGEST_NAME
        if (!name || line.letterAt(end)) return null
        val word = line.text.substring(start, end)
        val weekday = DateNames.weekday(word)
        if (weekday != null) return weekdayFirst(line, start, end, weekday)
        val month = DateNames.month(word) ?: return null
        return DateShapes.monthFirst(line, start, end, month)
    }

    /** A weekday before a date filters its candidates; a weekday alone is nothing (ADR-0008 §4.1). */
    private fun weekdayFirst(line: LineScan, start: Int, wordEnd: Int, weekday: DayOfWeek): DateToken? {
        val comma = if (line.ch(wordEnd) == ',') 1 else 0
        val gap = line.spaces(wordEnd + comma, DateShapes.MAX_SPACES)
        if (gap !in 1..DateShapes.MAX_SPACES) return null
        val at = wordEnd + comma + gap
        val inner = dateAfterWeekday(line, at) ?: return null
        if (inner.reason != null) return inner
        val kept = inner.values.filter { it.dayOfWeek == weekday }
        val filtered = inner.copy(start = start, values = kept, english = true, startsWithDigit = false)
        return when {
            kept.isEmpty() -> filtered.abstained(AbstentionReason.WEEKDAY_MISMATCH)
            inner.twoDigitYear && kept.size == 1 -> filtered.abstained(AbstentionReason.AMBIGUOUS_FORM)
            else -> filtered
        }
    }

    private fun dateAfterWeekday(line: LineScan, at: Int): DateToken? {
        val char = line.ch(at)
        if (LineScan.isAsciiDigit(char)) return DateShapes.numberFirst(line, at)
        if (!LineScan.isAsciiLetter(char)) return null
        val end = line.asciiWordEnd(at, DateShapes.LONGEST_NAME)
        if (line.letterAt(end)) return null
        val month = DateNames.month(line.text.substring(at, end)) ?: return null
        return DateShapes.monthFirst(line, at, end, month)
    }
}

/**
 * Where a numeric token may start: a token boundary, and not inside a run
 * such as `x03/04`, `1:14:30` or `123-2026-09-16` (amendment E-05). After
 * `-`, only a standalone 1-2 digit day may come before (`3-5 April 2026`,
 * a range of ADR-0008 §4.1).
 */
internal object NumericStarts {
    private const val JOINERS = "/.:,"
    private const val MAX_DAY_DIGITS = 2

    fun canStart(line: LineScan, index: Int): Boolean {
        if (!line.boundaryBefore(index)) return false
        val joiner = line.raw(index - 1)
        val afterDigit = index >= 2 && Character.isDigit(line.text[index - 2])
        return when {
            !afterDigit -> true
            joiner in JOINERS -> false
            joiner == '-' -> standaloneDay(line, index - 1)
            else -> true
        }
    }

    private fun standaloneDay(line: LineScan, dash: Int): Boolean {
        var start = dash
        while (start > 0 && dash - start <= MAX_DAY_DIGITS && Character.isDigit(line.text[start - 1])) start--
        return dash - start <= MAX_DAY_DIGITS && line.boundaryBefore(start)
    }
}
