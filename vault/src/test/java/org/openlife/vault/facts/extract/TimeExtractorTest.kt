package org.openlife.vault.facts.extract

import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR-0008 §4.2-§4.3 (rules-time-v1, rules-datetime-v1); matrix C2-01, C2-02. */
class TimeExtractorTest {
    @Test
    fun tableDriven() = FactFixtures.check("times.tsv", minimumRows = 80)

    @Test
    fun canonicalBytesOfTimesAndDateTimes() {
        assertEquals("""{"time":"14:30"}""", only("14:30").valueJson)
        assertEquals("""{"time":"14:30:05"}""", only("14:30:05").valueJson)
        assertEquals("""{"candidates":[{"time":"00:00"},{"time":"12:00"}]}""", only("12 pm").valueJson)
        val dateTime = only("2026-09-16T14:30")
        assertEquals(ExtractedKind.DATETIME, dateTime.kind)
        assertEquals("""{"date":"2026-09-16","time":"14:30"}""", dateTime.valueJson)
        assertEquals("rules-datetime", dateTime.ruleId)
        assertEquals("en", only("16 Sep 2026 at 14:30").locale)
    }

    @Test
    fun aRangeIsTwoTimesEachReadFromItsOwnText() {
        val times = FactExtractor.extract(FactFixtures.spansOf("11:30 - 1:30 pm")).candidates
        assertEquals(listOf("11:30 | 23:30", "13:30"), times.map { it.canonicalText })
        assertEquals(listOf("rules-time", "rules-time"), times.map { it.ruleId })
    }

    private fun only(text: String) = FactExtractor.extract(FactFixtures.spansOf(text)).candidates.single()
}
