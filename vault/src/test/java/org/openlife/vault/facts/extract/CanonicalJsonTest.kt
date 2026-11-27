package org.openlife.vault.facts.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0008 §2 escaping and ordering; C2-R5 (equal values are equal bytes). */
class CanonicalJsonTest {
    @Test
    fun escapingIsExact() {
        val raw = "a\"b\\c\b\t\n\u000C\r\u0001\u001f/\u007Fé€"
        val json = CanonicalJson.of(TextValue(ExtractedKind.URL, "n", raw), emptyList())
        assertEquals(
            "{\"normalized\":\"n\",\"raw\":\"a\\\"b\\\\c\\b\\t\\n\\f\\r\\u0001\\u001f/\u007Fé€\"}",
            json,
        )
    }

    @Test
    fun nullsAndKeyOrderAreFixed() {
        assertEquals(
            """{"amount":"12.00","currency":null,"rawSymbol":"$"}""",
            CanonicalJson.of(MoneyValue("12.00", null, "$"), emptyList()),
        )
        assertEquals(
            """{"amount":"33.10","currency":"USD","rawSymbol":null}""",
            CanonicalJson.of(MoneyValue("33.10", "USD", null), emptyList()),
        )
    }

    @Test
    fun unpairedSurrogatesAreDetected() {
        assertTrue(CanonicalJson.hasUnpairedSurrogate("a\uD800b"))
        assertTrue(CanonicalJson.hasUnpairedSurrogate("\uDC00"))
        assertFalse(CanonicalJson.hasUnpairedSurrogate("😀"))
        assertFalse(CanonicalJson.hasUnpairedSurrogate("plain"))
    }
}
