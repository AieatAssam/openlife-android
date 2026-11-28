package org.openlife.app.ui.facts

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.app.R
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactValue
import org.openlife.vault.facts.StaleReason

/** P3-05-R6: dates, times and amounts read in the reader's locale; the stored canonical value is never changed. */
class FactDisplayTest {
    @Test
    fun aDateIsFormattedForTheLocaleAndTheStoredValueIsUnchanged() {
        val stored = FactValue("{\"date\":\"2026-04-03\"}", "2026-04-03")

        val uk = FactDisplay.value(FactKind.DATE, stored.canonicalText, Locale.UK)
        val germany = FactDisplay.value(FactKind.DATE, stored.canonicalText, Locale.GERMANY)

        assertNotEquals("a date reads differently in two locales", uk, germany)
        assertEquals("2026-04-03", stored.canonicalText)
        assertEquals("{\"date\":\"2026-04-03\"}", stored.valueJson)
    }

    @Test
    fun aTimeAndADateTimeAreFormattedForTheLocale() {
        val twelveHour = FactDisplay.value(FactKind.TIME, "14:05", Locale.US)
        val twentyFourHour = FactDisplay.value(FactKind.TIME, "14:05", Locale.UK)
        val dateTime = FactDisplay.value(FactKind.DATETIME, "2026-04-03 14:05", Locale.UK)

        assertNotEquals("a 12-hour locale reads the time differently", twelveHour, twentyFourHour)
        assertTrue("a 12-hour time shows the meridiem: $twelveHour", twelveHour.contains("PM"))
        assertTrue("a date and time keeps the year: $dateTime", dateTime.contains("2026"))
    }

    @Test
    fun anAmountUsesTheLocaleCurrencyFormatAndKeepsItsCode() {
        val stored = FactValue("{\"amount\":\"12.50\",\"currency\":\"GBP\"}", "12.50 GBP")

        val uk = FactDisplay.value(FactKind.MONEY, stored.canonicalText, Locale.UK)
        val germany = FactDisplay.value(FactKind.MONEY, stored.canonicalText, Locale.GERMANY)

        assertTrue("UK formatting shows the amount: $uk", uk.contains("12.50"))
        assertNotEquals("the amount's punctuation follows the locale", uk, germany)
        assertEquals("12.50 GBP", stored.canonicalText)
    }

    @Test
    fun aValueThatCannotBeParsedOrHasOnlyASymbolIsShownAsStored() {
        assertEquals("not a date", FactDisplay.value(FactKind.DATE, "not a date", Locale.UK))
        assertEquals("12.50 \u00a3", FactDisplay.value(FactKind.MONEY, "12.50 \u00a3", Locale.UK))
        assertEquals("https://example.org", FactDisplay.value(FactKind.URL, "https://example.org", Locale.UK))
    }

    @Test
    fun aStaleFactSaysWhyInItsOwnWords() {
        assertEquals(R.string.facts_stale_changed, FactDisplay.staleSentence(StaleReason.READING_REPLACED))
        assertEquals(R.string.facts_stale_changed, FactDisplay.staleSentence(null))
        assertEquals(R.string.facts_stale_rejected_reading, FactDisplay.staleSentence(StaleReason.READING_REJECTED))
        assertEquals(R.string.facts_stale_rejected_line, FactDisplay.staleSentence(StaleReason.SPAN_REJECTED))
    }
}
