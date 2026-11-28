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
    private val ATTACHED = AbstentionReason.ATTACHED_CHARACTERS
    const val RULE_ID = "rules-time"
    const val RULES_VERSION = "v1"
    internal val RULE = RuleRef(RULE_ID, RULES_VERSION)

    private const val SHORT = 2
    private const val BARE_HHMM = 4
    private const val ALONE_END = ".;!?)"
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
            val token = if (canStart(line, index, tokens.lastOrNull()?.end)) parse(line, index) else null
            index = token?.end ?: (index + 1)
            if (token != null) tokens += token
        }
        return TimeRanges.apply(line, tokens)
    }

    /** Never right after a sign, unless the sign joins this time to the one before (`9am-5pm`; finding E-02). */
    private fun canStart(line: LineScan, index: Int, previousEnd: Int?): Boolean {
        if (!LineScan.isAsciiDigit(line.ch(index))) return false
        val afterSign = LineScan.isSignLike(line.raw(index - 1)) && previousEnd != index - 1
        val afterDate = line.raw(index - 1) == 'T' && line.isClaimed(index - 2)
        return !afterSign && (afterDate || NumericStarts.canStart(line, index))
    }

    private fun parse(line: LineScan, start: Int): TimeToken? {
        if (line.digits(start, SHORT) > SHORT) return bareHhmm(line, start)
        val hourDigits = line.digits(start, SHORT)
        if (hourDigits !in 1..SHORT) return null
        val parts = mutableListOf(number(line, start, hourDigits))
        var at = start + hourDigits
        while (parts.size < MAX_PARTS && line.ch(at) == ':' && line.digits(at + 1, SHORT) == SHORT) {
            parts += number(line, at + 1, SHORT)
            at += SHORT + 1
        }
        val marker = marker(line, at)
        if (parts.size == 1 && marker == null) return otherSeparator(line, start, at)
        if (parts.size > 1 && fraction(line, at)) {
            return TimeToken(start, line.tokenEnd(at), emptyList(), english = false, reason = ATTACHED)
        }
        val end = marker?.first ?: at
        val leadingZero = hourDigits == SHORT && line.text[start] == '0'
        val token = TimeToken(start, end, emptyList(), english = marker != null)
        val problem = TimeZones.after(line, end)
        return when {
            problem != null -> token.copy(end = problem, reason = AbstentionReason.ZONE_OR_ANNOTATION)
            attached(line, end) -> token.copy(end = line.marksEnd(end), reason = ATTACHED)
            else -> withValues(token, parts, marker?.second, leadingZero)
        }
    }

    private fun attached(line: LineScan, end: Int): Boolean =
        !line.boundaryAfter(end) || (line.raw(end) == ':' && LineScan.isAsciiDigit(line.raw(end + 1)))

    /** Fractional seconds (`14:30:00.000`): a dot or comma and a digit after the time (finding E-02). */
    private fun fraction(line: LineScan, at: Int): Boolean =
        (line.raw(at) == '.' || line.raw(at) == ',') && LineScan.isAsciiDigit(line.raw(at + 1))

    /**
     * Ruling Q16: `H.MM` before an am/pm marker and `HhMM` are claimed and
     * abstained. A bare `H.MM` stays a number (`12.50 EUR`).
     */
    private fun otherSeparator(line: LineScan, start: Int, at: Int): TimeToken? {
        val separator = line.ch(at)
        if ((separator != '.' && separator != 'h') || line.digits(at + 1, SHORT) != SHORT) return null
        val end = at + 1 + SHORT
        val marker = if (separator == '.') marker(line, end) ?: return null else null
        val tokenEnd = marker?.first ?: end
        if (marker == null && !line.boundaryAfter(end)) return null
        return TimeToken(start, tokenEnd, emptyList(), marker != null, AbstentionReason.AMBIGUOUS_FORM)
    }

    /**
     * A bare four-digit clock value (`1430`) is abstained, claimed, never dropped (ADR-0008 §4.2 "Abstained";
     * P3-06 follow-up D-2). Only a valid HHMM standing alone (line start or one space before; line end or `.;!?)`
     * after) is claimed, so phone groups, money and other digit runs are left alone.
     */
    private fun bareHhmm(line: LineScan, start: Int): TimeToken? {
        val end = start + BARE_HHMM
        val digits = line.digits(start, BARE_HHMM) == BARE_HHMM
        if (!digits || !aloneBefore(line, start) || !aloneAfter(line, end)) return null
        val hour = line.text.substring(start, start + SHORT).toInt()
        val minute = line.text.substring(start + SHORT, start + BARE_HHMM).toInt()
        if (hour > MAX_HOUR || minute > MAX_MINUTE) return null
        return TimeToken(start, end, emptyList(), english = false, reason = AbstentionReason.AMBIGUOUS_FORM)
    }

    /** Line start or one space, and not the tail of a space-grouped number (`7946 0018`). */
    private fun aloneBefore(line: LineScan, start: Int): Boolean {
        if (start == 0) return true
        if (!LineScan.isSpace(line.raw(start - 1))) return false
        return start < 2 || !LineScan.isAsciiDigit(line.raw(start - 2))
    }

    private fun aloneAfter(line: LineScan, end: Int): Boolean = end >= line.length || line.raw(end) in ALONE_END

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

    /** An am/pm marker after [at] and at most 4 space separators or tabs: its end offset and whether it is pm. */
    private fun marker(line: LineScan, at: Int): Pair<Int, Boolean>? {
        val gap = line.spaceRun(at)
        if (gap > LineScan.MAX_SPACE_RUN) return null
        val from = at + gap
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

    /** Zone words in any case (ruling 2026-11-26: military `Zulu`, as `Z`). */
    private const val ZULU = "zulu"

    /** The end of a zone token after [end] and at most 4 space separators or tabs, or null (finding E-08). */
    fun after(line: LineScan, end: Int): Int? {
        val gap = line.spaceRun(end)
        if (gap > LineScan.MAX_SPACE_RUN) return null
        val at = end + gap
        val char = line.ch(at)
        return when {
            char == 'Z' && line.boundaryAfter(at + 1) -> at + 1
            line.matchesIgnoreCase(at, ZULU) && line.boundaryAfter(at + ZULU.length) -> at + ZULU.length
            LineScan.isOffsetSign(char) -> offset(line, at)
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
        val zone = end - at in MIN_LETTERS..MAX_LETTERS && !line.letterAt(end) && word !in NOT_ZONES
        return if (zone) wordOffset(line, end) ?: end else null
    }

    /** A zone word takes a directly following offset of 1-2 or 4 digits (`GMT+01:00`, `UTC-5`; finding E-02). */
    private fun wordOffset(line: LineScan, sign: Int): Int? {
        if (!LineScan.isOffsetSign(line.ch(sign))) return null
        val digits = line.digits(sign + 1, LONG_OFFSET)
        val hoursEnd = sign + 1 + digits
        return when {
            digits == LONG_OFFSET -> hoursEnd
            digits !in 1..OFFSET_DIGITS -> null
            minutesAfter(line, hoursEnd) -> hoursEnd + 1 + OFFSET_DIGITS
            else -> hoursEnd
        }
    }

    private fun minutesAfter(line: LineScan, at: Int): Boolean =
        line.ch(at) == ':' && line.digits(at + 1, OFFSET_DIGITS) == OFFSET_DIGITS

    private fun annotation(line: LineScan, open: Int): Int? {
        val next = line.ch(open + 1)
        if (!LineScan.isAsciiLetter(next) && !LineScan.isOffsetSign(next)) return null
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
            val reason = token.reason ?: previous.reason
            out += if (reason != null) {
                out.removeAt(out.lastIndex)
                token.copy(start = previous.start, values = emptyList(), reason = reason, inRange = true)
            } else {
                token.copy(inRange = true)
            }
        }
        return out
    }

    private fun joined(line: LineScan, from: Int, to: Int): Boolean {
        if (to - from > MAX_GAP) return false
        val between = line.text.substring(from, to).trim(LineScan::isSpace)
        return (between.length == 1 && between[0] in DateNames.DASHES) ||
            DateNames.RANGE_WORDS.any { it.equals(between, ignoreCase = true) }
    }
}
