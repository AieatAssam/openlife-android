package org.openlife.vault.facts

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * P3-02 review M-08 / C2-R5, C2-R38: the shape check a user value gets
 * before it is stored. DATE, TIME and DATETIME are checked completely:
 * byte-canonical JSON (C2 §6), a real calendar date or 24-hour time, and a
 * `canonicalText` derived from it. MONEY, URL, EMAIL and PHONE are checked
 * only for the leading key of their kind here; their full validation uses
 * the extractors' normalisers (C2-R38) and arrives with P3-03 and the
 * input screens of P3-05.
 */
object UserValueShape {
    private val DATE = Regex("""\{"date":"(\d{4}-\d{2}-\d{2})"\}""")
    private val TIME = Regex("""\{"time":"(\d{2}:\d{2}(?::\d{2})?)"\}""")
    private val DATETIME = Regex("""\{"date":"(\d{4}-\d{2}-\d{2})","time":"(\d{2}:\d{2}(?::\d{2})?)"\}""")

    fun matches(kind: FactKind, value: FactValue): Boolean = when (kind) {
        FactKind.DATE -> isDateValue(value)
        FactKind.TIME -> isTimeValue(value)
        FactKind.DATETIME -> isDateTimeValue(value)
        FactKind.MONEY -> value.valueJson.startsWith("{\"amount\":\"")
        FactKind.URL, FactKind.EMAIL, FactKind.PHONE -> value.valueJson.startsWith("{\"normalized\":\"")
        FactKind.UNRECOGNISED -> false
    }

    private fun isDateValue(value: FactValue): Boolean {
        val date = DATE.matchEntire(value.valueJson)?.groupValues?.get(1) ?: return false
        return isDate(date) && value.canonicalText == date
    }

    private fun isTimeValue(value: FactValue): Boolean {
        val time = TIME.matchEntire(value.valueJson)?.groupValues?.get(1) ?: return false
        return isTime(time) && value.canonicalText == time
    }

    private fun isDateTimeValue(value: FactValue): Boolean {
        val (date, time) = DATETIME.matchEntire(value.valueJson)?.destructured ?: return false
        return isDate(date) && isTime(time) && value.canonicalText == "$date $time"
    }

    private fun isDate(text: String): Boolean = parses { LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE) }

    private fun isTime(text: String): Boolean = parses { LocalTime.parse(text, DateTimeFormatter.ISO_LOCAL_TIME) }

    private inline fun parses(parse: () -> Any): Boolean = try {
        parse()
        true
    } catch (_: DateTimeParseException) {
        false
    }
}
