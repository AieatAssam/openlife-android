package org.openlife.vault.facts.extract

/**
 * `rules-datetime-v1` (ADR-0008 §4.3): a date and a time are one DATETIME
 * only when each has exactly one value, neither is part of a range, and
 * only whitespace, a comma, `at`, `@`, `-` or a joining `T` lies between them.
 * Otherwise each is emitted on its own. A zone after the time abstains the
 * time; a date joined to it by `T` abstains with it.
 */
object DateTimeExtractor {
    const val RULE_ID = "rules-datetime"
    const val RULES_VERSION = "v1"
    private val RULE = RuleRef(RULE_ID, RULES_VERSION)

    private const val MAX_GAP = 6
    private val SEPARATORS = setOf("", ",", "@", "-", "at")

    /** Pairs, emits and claims the scanned dates and times of one line, in text order. */
    internal fun emit(line: LineScan, dates: List<DateToken>, times: List<TimeToken>) {
        val pairing = Pairing()
        var cursor = 0
        times.forEachIndexed { timeIndex, time ->
            while (cursor < dates.size && dates[cursor].end <= time.start) cursor++
            val pair = partner(line, dates, cursor, time)?.takeIf { it !in pairing.dates }
            if (pair != null) pairing.judge(line, pair, dates[pair], timeIndex, time)
        }
        val timeStarts = times.mapTo(HashSet()) { it.start }
        dates.forEachIndexed { index, date ->
            if (index !in pairing.dates) emitDate(line, date, index in pairing.zoned, date.end + 1 in timeStarts)
        }
        times.forEachIndexed { index, time -> if (index !in pairing.times) emitTime(line, time) }
    }

    /** The index of the date just before or just after [time] across an allowed gap, if any. */
    private fun partner(line: LineScan, dates: List<DateToken>, cursor: Int, time: TimeToken): Int? {
        val before = dates.getOrNull(cursor - 1)
        val after = dates.getOrNull(cursor)
        return when {
            before != null && gapAllowed(line, before, time.start, dateFirst = true) -> cursor - 1
            after != null && after.start >= time.end && gapAllowed(line, after, time.end, dateFirst = false) -> cursor
            else -> null
        }
    }

    /** Which dates and times were merged, and which dates abstain with a zoned time. */
    private class Pairing {
        val dates = HashSet<Int>()
        val times = HashSet<Int>()
        val zoned = HashSet<Int>()

        fun judge(line: LineScan, dateIndex: Int, date: DateToken, timeIndex: Int, time: TimeToken) {
            if (time.reason == AbstentionReason.ZONE_OR_ANNOTATION && date.joinedByT) {
                zoned += dateIndex
            } else if (pairable(date, time)) {
                emitPair(line, date, time)
                dates += dateIndex
                times += timeIndex
            }
        }
    }

    private fun pairable(date: DateToken, time: TimeToken): Boolean =
        time.reason == null && !time.inRange && date.values.size == 1 && time.values.size == 1

    private fun gapAllowed(line: LineScan, date: DateToken, timeEdge: Int, dateFirst: Boolean): Boolean {
        val from = if (dateFirst) date.end else timeEdge
        val to = if (dateFirst) timeEdge else date.start
        if (to - from > MAX_GAP || to < from) return false
        val gap = line.text.substring(from, to)
        if (gap == "T") return dateFirst && date.joinedByT
        val core = gap.trim(' ')
        val spaced = gap.isNotEmpty() && (core.isEmpty() || core == "," || gap.startsWith(' '))
        return spaced && core.lowercase() in SEPARATORS && !date.joinedByT
    }

    private fun emitPair(line: LineScan, date: DateToken, time: TimeToken) {
        val value = DateTimeValue(DateValue(date.values.single()), time.values.single())
        val output = LineScan.Output(
            ExtractedKind.DATETIME,
            minOf(date.start, time.start),
            maxOf(date.end, time.end),
            listOf(value),
            RULE,
            english = date.english || time.english,
        )
        line.emit(output)
    }

    private fun emitDate(line: LineScan, date: DateToken, zoned: Boolean, timeFollows: Boolean) {
        val reason = when {
            zoned -> AbstentionReason.ZONE_OR_ANNOTATION
            date.joinedByT && !timeFollows -> AbstentionReason.ATTACHED_CHARACTERS
            else -> null
        }
        if (reason != null) {
            line.abstain(ExtractedKind.DATE, date.start, date.end, reason)
            return
        }
        val values = date.values.map(::DateValue)
        line.emit(LineScan.Output(ExtractedKind.DATE, date.start, date.end, values, DateExtractor.RULE, date.english))
    }

    private fun emitTime(line: LineScan, time: TimeToken) {
        val reason = time.reason
        if (reason != null) {
            line.abstain(ExtractedKind.TIME, time.start, time.end, reason)
        } else {
            val kind = ExtractedKind.TIME
            line.emit(LineScan.Output(kind, time.start, time.end, time.values, TimeExtractor.RULE, time.english))
        }
    }
}
