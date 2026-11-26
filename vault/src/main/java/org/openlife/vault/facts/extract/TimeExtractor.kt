package org.openlife.vault.facts.extract

/** A recognised time shape (ADR-0008 §4.2); [reason] set when it is abstained. */
internal data class TimeToken(
    val start: Int,
    val end: Int,
    val values: List<TimeValue>,
    val english: Boolean,
    val reason: AbstentionReason? = null,
    val inRange: Boolean = false,
)

/**
 * `rules-time-v1` (ADR-0008 §4.2, §4.3): `H:MM[:SS]` with an optional
 * am/pm marker, or an hour with a marker. Hours 1-12 without a marker or a
 * leading zero give both readings. Anything that could be a zone or offset
 * after the time abstains it.
 */
object TimeExtractor {
    const val RULE_ID = "rules-time"
    const val RULES_VERSION = "v1"
    internal val RULE = RuleRef(RULE_ID, RULES_VERSION)

    private const val SHORT = 2
    private const val MAX_PARTS = 3
    private const val MAX_MINUTE = 59
    private const val MAX_HOUR = 23
    private const val HALF_DAY = 12
    private val MARKERS = listOf("a.m." to false, "p.m." to true, "am" to false, "pm" to true)

    /** Finds every time token, with ranges marked; nothing is claimed or emitted yet. */
    internal fun scan(line: LineScan): List<TimeToken> {
        val tokens = mutableListOf<TimeToken>()
        var index = 0
        while (index < line.length) {
            line.reach(index)
            val token = if (canStart(line, index)) parse(line, index) else null
            index = token?.end ?: (index + 1)
            if (token != null) tokens += token
        }
        return TimeRanges.apply(line, tokens)
    }

    private fun canStart(line: LineScan, index: Int): Boolean {
        if (!LineScan.isAsciiDigit(line.ch(index))) return false
        val afterDate = line.raw(index - 1) == 'T' && line.isClaimed(index - 2)
        return afterDate || NumericStarts.canStart(line, index)
    }

    private fun parse(line: LineScan, start: Int): TimeToken? {
        val hourDigits = line.digits(start, SHORT)
        if (hourDigits !in 1..SHORT) return null
        val parts = mutableListOf(number(line, start, hourDigits))
        var at = start + hourDigits
        while (parts.size < MAX_PARTS && line.ch(at) == ':' && line.digits(at + 1, SHORT) == SHORT) {
            parts += number(line, at + 1, SHORT)
            at += SHORT + 1
        }
        val marker = marker(line, at)
        if (parts.size == 1 && marker == null) return null
        val end = marker?.first ?: at
        val leadingZero = hourDigits == SHORT && line.text[start] == '0'
        val token = TimeToken(start, end, emptyList(), english = marker != null)
        val problem = TimeZones.after(line, end)
        return when {
            problem != null -> token.copy(end = problem, reason = AbstentionReason.ZONE_OR_ANNOTATION)
            attached(line, end) -> token.copy(reason = AbstentionReason.ATTACHED_CHARACTERS)
            else -> withValues(token, parts, marker?.second, leadingZero)
        }
    }

    private fun attached(line: LineScan, end: Int): Boolean =
        !line.boundaryAfter(end) || (line.raw(end) == ':' && LineScan.isAsciiDigit(line.raw(end + 1)))

    private fun withValues(token: TimeToken, parts: List<Int>, pm: Boolean?, leadingZero: Boolean): TimeToken {
        val hour = parts[0]
        val minute = parts.getOrNull(1) ?: 0
        val second = parts.getOrNull(2)
        val validClock = minute <= MAX_MINUTE && (second ?: 0) <= MAX_MINUTE
        val hours = when {
            !validClock -> null
            pm != null -> markedHours(hour, pm)
            else -> plainHours(hour, leadingZero)
        }
        return when {
            hours == null -> token.copy(reason = AbstentionReason.INVALID_VALUE)
            hours.isEmpty() && pm != null -> token.copy(reason = AbstentionReason.MARKER_CONFLICT)
            hours.isEmpty() -> token.copy(reason = AbstentionReason.AMBIGUOUS_FORM)
            else -> token.copy(values = hours.map { TimeValue(it, minute, second) })
        }
    }

    /** Without a marker: 13-23 or a leading zero is one reading, 1-12 two, a bare 0 none (null when invalid). */
    private fun plainHours(hour: Int, leadingZero: Boolean): List<Int>? = when {
        hour > MAX_HOUR -> null
        leadingZero || hour > HALF_DAY -> listOf(hour)
        hour == 0 -> emptyList()
        else -> listOf(hour % HALF_DAY, hour % HALF_DAY + HALF_DAY)
    }

    /** 12 with a marker gives both readings (ADR-0008 §4.2); 0 or 13+ with a marker contradicts it. */
    private fun markedHours(hour: Int, pm: Boolean): List<Int> = when {
        hour == 0 || hour > HALF_DAY -> emptyList()
        hour == HALF_DAY -> listOf(0, HALF_DAY)
        pm -> listOf(hour + HALF_DAY)
        else -> listOf(hour)
    }

    /** An am/pm marker directly after or one space after [at]: its end offset and whether it is pm. */
    private fun marker(line: LineScan, at: Int): Pair<Int, Boolean>? {
        val from = if (line.ch(at) == ' ') at + 1 else at
        for ((word, pm) in MARKERS) {
            val end = from + word.length
            if (line.matchesIgnoreCase(from, word) && line.boundaryAfter(end)) return end to pm
        }
        return null
    }

    private fun number(line: LineScan, start: Int, count: Int): Int = line.text.substring(start, start + count).toInt()
}

/** ADR-0008 §4.3 (Z-06): what after a time could be a zone, an offset or an annotation. */
internal object TimeZones {
    private const val OFFSET_DIGITS = 2
    private const val LONG_OFFSET = 4
    private const val MIN_LETTERS = 2
    private const val MAX_LETTERS = 5
    private const val MAX_ANNOTATION = 32
    private val NOT_ZONES = setOf("TO", "TILL", "UNTIL", "AND", "OR", "AT", "ON")

    /** The end of a zone token directly or one space after [end], or null when none follows. */
    fun after(line: LineScan, end: Int): Int? {
        val at = if (line.ch(end) == ' ') end + 1 else end
        val char = line.ch(at)
        return when {
            char == 'Z' && line.boundaryAfter(at + 1) -> at + 1
            char == '+' || char == '-' -> offset(line, at)
            LineScan.isAsciiUpper(char) -> letters(line, at)
            char == '(' -> annotation(line, at)
            else -> null
        }
    }

    private fun offset(line: LineScan, sign: Int): Int? {
        val digits = line.digits(sign + 1, LONG_OFFSET)
        val hoursEnd = sign + 1 + OFFSET_DIGITS
        val minutesEnd = hoursEnd + 1 + OFFSET_DIGITS
        return when {
            digits == LONG_OFFSET -> hoursEnd + OFFSET_DIGITS
            digits != OFFSET_DIGITS -> null
            line.ch(hoursEnd) == ':' && line.digits(hoursEnd + 1, OFFSET_DIGITS) == OFFSET_DIGITS -> minutesEnd
            else -> hoursEnd
        }
    }

    private fun letters(line: LineScan, at: Int): Int? {
        var end = at
        while (end - at <= MAX_LETTERS && LineScan.isAsciiUpper(line.ch(end))) end++
        val word = line.text.substring(at, end)
        val zone = end - at in MIN_LETTERS..MAX_LETTERS && !Character.isLetter(line.raw(end).code) && word !in NOT_ZONES
        return if (zone) end else null
    }

    private fun annotation(line: LineScan, open: Int): Int? {
        val next = line.ch(open + 1)
        if (!LineScan.isAsciiLetter(next) && next != '+' && next != '-') return null
        var end = open + 1
        while (end - open < MAX_ANNOTATION && line.ch(end) != ')' && line.ch(end) != LineScan.NONE) end++
        return if (line.raw(end) == ')') end + 1 else line.tokenEnd(open)
    }
}

/** ADR-0008 §4.2 ranges: both times stay separate facts, never merged into a date-time (ZZ-03). */
internal object TimeRanges {
    private const val MAX_GAP = 12

    fun apply(line: LineScan, tokens: List<TimeToken>): List<TimeToken> {
        val out = mutableListOf<TimeToken>()
        for (token in tokens) {
            val previous = out.lastOrNull()
            if (previous == null || !joined(line, previous.end, token.start)) {
                out += token
                continue
            }
            out[out.lastIndex] = previous.copy(inRange = true)
            out += if (token.reason == AbstentionReason.ZONE_OR_ANNOTATION) {
                out.removeAt(out.lastIndex)
                token.copy(start = previous.start, values = emptyList(), inRange = true)
            } else {
                token.copy(inRange = true)
            }
        }
        return out
    }

    private fun joined(line: LineScan, from: Int, to: Int): Boolean {
        if (to - from > MAX_GAP) return false
        val between = line.text.substring(from, to).trim(' ')
        return (between.length == 1 && between[0] in DateNames.DASHES) ||
            DateNames.RANGE_WORDS.any { it.equals(between, ignoreCase = true) }
    }
}
