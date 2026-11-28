package org.openlife.app.ui.facts

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import org.openlife.app.R
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.StaleReason

/**
 * P3-05-R6: the text a fact value is shown with, formatted for the reader's locale. The stored canonical text is only an
 * input here and is never changed. A value that cannot be parsed, or a money amount with a printed symbol instead of a
 * code, is shown as stored.
 */
internal object FactDisplay {
    fun value(kind: FactKind, canonicalText: String, locale: Locale): String = runCatching {
        when (kind) {
            FactKind.DATE -> LocalDate.parse(canonicalText).format(dateFormat(locale))
            FactKind.TIME -> LocalTime.parse(canonicalText).format(timeFormat(locale))
            FactKind.DATETIME -> LocalDate.parse(canonicalText.substringBefore(' '))
                .atTime(LocalTime.parse(canonicalText.substringAfter(' ')))
                .format(dateTimeFormat(locale))
            FactKind.MONEY -> money(canonicalText, locale)
            else -> canonicalText
        }
    }.getOrElse { canonicalText }

    /** P3-05-R4 / C2-R41: the sentence that says why a fact is greyed. */
    fun staleSentence(reason: StaleReason?): Int = when (reason) {
        StaleReason.READING_REJECTED -> R.string.facts_stale_rejected_reading
        StaleReason.SPAN_REJECTED -> R.string.facts_stale_rejected_line
        else -> R.string.facts_stale_changed
    }

    private fun dateFormat(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

    private fun timeFormat(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    private fun dateTimeFormat(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)

    /** The canonical form is `<amount> <ISO 4217 code>`; a symbol-only value is not a code and stays as stored. */
    private fun money(canonicalText: String, locale: Locale): String {
        val code = canonicalText.substringAfter(' ', "")
        if (code.length != ISO_CODE_LENGTH) return canonicalText
        val format = NumberFormat.getCurrencyInstance(locale)
        format.currency = Currency.getInstance(code)
        return format.format(BigDecimal(canonicalText.substringBefore(' ')))
    }

    private const val ISO_CODE_LENGTH = 3
}
