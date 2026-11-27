package org.openlife.vault.facts.extract

import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR-0008 §4.1 (rules-date-v1); matrix C2-01, C2-02. */
class DateExtractorTest {
    @Test
    fun tableDriven() = FactFixtures.check("dates.tsv", minimumRows = 100)

    @Test
    fun ambiguousNumericDateIsACandidatePairAndIsoIsSingle() {
        val pair = only("03/04/2026")
        assertEquals("""{"candidates":[{"date":"2026-03-04"},{"date":"2026-04-03"}]}""", pair.valueJson)
        assertEquals(true, pair.needsClarification)
        val iso = only("2026-09-16")
        assertEquals("""{"date":"2026-09-16"}""", iso.valueJson)
        assertEquals(false, iso.needsClarification)
        assertEquals(emptyList<ExtractedCandidate>(), extract("31/04/2026"))
    }

    @Test
    fun localeIsEnglishOnlyWhenAMonthOrWeekdayNameMatched() {
        assertEquals("en", only("16 September 2026").locale)
        assertEquals("en", only("Wed 03/04/2026").locale)
        assertEquals("und", only("2026-09-16").locale)
        assertEquals("und", only("03/04/2026").locale)
    }

    @Test
    fun candidateCarriesItsRuleAndSpan() {
        val span = ExtractionSpan(spanId = "span-7", correctionId = "corr-2", ordinal = 4, text = "On 2026-09-16.")
        val candidate = FactExtractor.extract(listOf(span)).candidates.single()
        assertEquals(ExtractedKind.DATE, candidate.kind)
        assertEquals("span-7", candidate.spanId)
        assertEquals("corr-2", candidate.correctionId)
        assertEquals(3, candidate.startOffset)
        assertEquals(13, candidate.endOffset)
        assertEquals("rules-date", candidate.ruleId)
        assertEquals("v1", candidate.ruleVersion)
        assertEquals("rules-date+correction", candidate.methodId)
        assertEquals("2026-09-16", candidate.canonicalText)
    }

    private fun extract(text: String) = FactExtractor.extract(FactFixtures.spansOf(text)).candidates

    private fun only(text: String) = extract(text).single()
}
