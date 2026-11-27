package org.openlife.vault.facts.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ADR-0008 §5 (rules-money-v1); matrix C2-01, C2-02. */
class MoneyExtractorTest {
    @Test
    fun tableDriven() = FactFixtures.check("money.tsv", minimumRows = 80)

    /** Design §4: no currency is inferred from a symbol without enough context. */
    @Test
    fun symbolWithoutCodeYieldsNullCurrency() {
        for (text in listOf("$12.00", "£5", "¥500", "₩1000", "500 kr")) {
            val candidate = only(text)
            val value = candidate.canonicalValue as MoneyValue
            assertNull(text, value.currency)
            assertEquals(text, false, candidate.needsClarification)
        }
        assertEquals("""{"amount":"12.00","currency":null,"rawSymbol":"$"}""", only("$12.00").valueJson)
        assertEquals("""{"amount":"12.50","currency":"EUR","rawSymbol":"€"}""", only("€12.50").valueJson)
        assertEquals("""{"amount":"33.10","currency":"USD","rawSymbol":null}""", only("USD 33.10").valueJson)
    }

    /** No context code ever yields a definite currency (ADR-0008 §5.4). */
    @Test
    fun aContextCodeOnlyOffersCandidatesBesideNotStated() {
        val candidate = only("SSP £116.75")
        assertEquals(
            """{"candidates":[{"amount":"116.75","currency":null,"rawSymbol":"£"},""" +
                """{"amount":"116.75","currency":"SSP","rawSymbol":"£"}]}""",
            candidate.valueJson,
        )
        assertEquals(true, candidate.needsClarification)
    }

    /** Design §4: amounts are decimal strings with the printed scale, never binary floating point. */
    @Test
    fun decimalStringNeverUsesBinaryFloat() {
        val cases = linkedMapOf(
            "€0.10" to "0.10",
            "€0.20" to "0.20",
            "€0.30" to "0.30",
            "€0.1" to "0.1",
            "€1.10" to "1.10",
            "€1,234.675" to "1234.675",
            "€100.00" to "100.00",
            "€999999999999999.99" to "999999999999999.99",
            "€123456789012345" to "123456789012345",
            "€9007199254740993" to null,
        )
        for ((text, amount) in cases) {
            val value = FactExtractor.extract(FactFixtures.spansOf(text)).candidates.singleOrNull()?.canonicalValue
            assertEquals(text, amount, (value as MoneyValue?)?.amount)
        }
    }

    @Test
    fun inspectedNeighbourSpansAreCurrencyContextEvidence() {
        val spans = listOf(
            ExtractionSpan("a", null, 1, "Prices in AUD"),
            ExtractionSpan("b", "corr-b", 2, "Total $12.00"),
            ExtractionSpan("c", null, 3, "Thanks"),
        )
        val candidate = FactExtractor.extract(spans).candidates.single()
        assertEquals("b", candidate.spanId)
        assertEquals(
            listOf(CurrencyContext("a", null, 10, 13), CurrencyContext("c", null, null, null)),
            candidate.currencyContext,
        )
        assertEquals("rules-money+correction", candidate.methodId)
    }

    @Test
    fun contextIsReadOnlyFromOrdinalNeighbours() {
        val spans = listOf(ExtractionSpan("a", null, 1, "Prices in AUD"), ExtractionSpan("b", null, 3, "$12.00"))
        val candidate = FactExtractor.extract(spans).candidates.single()
        assertEquals("12.00 $", candidate.canonicalText)
        assertEquals(emptyList<CurrencyContext>(), candidate.currencyContext)
    }

    @Test
    fun aDefiniteMarkerInspectsNoContext() {
        val spans = listOf(ExtractionSpan("a", "corr-a", 1, "Prices in AUD"), ExtractionSpan("b", null, 2, "€12.00"))
        val candidate = FactExtractor.extract(spans).candidates.single()
        assertEquals(emptyList<CurrencyContext>(), candidate.currencyContext)
        assertEquals("rules-money", candidate.methodId)
    }

    private fun only(text: String) = FactExtractor.extract(FactFixtures.spansOf(text)).candidates.single()
}
