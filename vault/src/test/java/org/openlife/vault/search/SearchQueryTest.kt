package org.openlife.vault.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-08-R3: user input becomes quoted FTS5 strings (last one a prefix) or escaped LIKE patterns only. */
class SearchQueryTest {
    @Test
    fun wordsBecomeQuotedStringsWithAPrefixOnTheLast() {
        val tokens = SearchQuery.tokens("  Dentist   appoint ")
        assertEquals(listOf("Dentist", "appoint"), tokens)
        assertEquals("\"Dentist\" \"appoint\"*", SearchQuery.matchExpression(tokens))
    }

    @Test
    fun operatorsAndPunctuationAreNeverSyntax() {
        assertEquals(listOf("NEAR", "a", "b"), SearchQuery.tokens("NEAR(a b)"))
        assertEquals(listOf("parcel", "OR"), SearchQuery.tokens("parcel OR"))
        assertEquals(listOf("text", "parcel"), SearchQuery.tokens("text:parcel"))
        assertEquals(listOf("hello"), SearchQuery.tokens("\"hello\"*"))
        assertEquals(emptyList<String>(), SearchQuery.tokens("\" * ( ) ^ - + : { } ' ; \\ % _"))
        assertNull(SearchQuery.matchExpression(emptyList()))
        val expression = SearchQuery.matchExpression(SearchQuery.tokens("NOT parcel OR \"x\" NEAR("))!!
        assertEquals("\"NOT\" \"parcel\" \"OR\" \"x\" \"NEAR\"*", expression)
        // Every character outside a quoted string is a space or the one trailing '*'.
        val outside = expression.split('"').filterIndexed { index, _ -> index % 2 == 0 }.joinToString("")
        assertTrue(outside, outside.all { it == ' ' || it == '*' })
    }

    @Test
    fun anEmbeddedQuoteIsDoubledIfOneEverReachesTheExpression() {
        assertEquals("\"a\"\"b\"*", SearchQuery.matchExpression(listOf("a\"b")))
    }

    @Test
    fun lettersFromEveryScriptAndDigitsAreKept() {
        assertEquals(listOf("Café", "Łódź", "東京", "4471"), SearchQuery.tokens("Café, Łódź; 東京 #4471"))
    }

    @Test
    fun inputIsBounded() {
        val many = (1..20).joinToString(" ") { "w$it" }
        assertEquals(SearchQuery.MAX_TOKENS, SearchQuery.tokens(many).size)
        val long = "x".repeat(500)
        assertEquals(SearchQuery.MAX_TOKEN_LENGTH, SearchQuery.tokens(long).single().length)
        val wide = "ab ".repeat(300)
        assertTrue(SearchQuery.tokens(wide).size <= SearchQuery.MAX_TOKENS)
    }

    @Test
    fun likePatternsEscapeWildcardsAndLowercase() {
        assertEquals(listOf("%dentist%"), SearchQuery.likePatterns(listOf("Dentist")))
        assertEquals(listOf("%a\\%b\\_c\\\\d%"), SearchQuery.likePatterns(listOf("a%b_c\\d")))
    }

    /** W-05: only A-Z are lowercased, exactly as SQLite's lower() treats the column. */
    @Test
    fun likePatternsLowercaseOnlyAsciiLikeSqlite() {
        assertEquals(listOf("%Ärzte%"), SearchQuery.likePatterns(listOf("ÄRZTE")))
        assertEquals(listOf("%Ärzte%"), SearchQuery.likePatterns(listOf("Ärzte")))
        assertEquals("istanbul ı İ", SearchQuery.asciiLowercase("ISTANBUL ı İ"))
    }

    /** W-02: token characters follow unicode61 (letters, all numbers, private use). */
    @Test
    fun numberFormsAndPrivateUseStayInTheirToken() {
        assertEquals(listOf("Chapter", "Ⅻ", "x²", "½", "\uE000ab"), SearchQuery.tokens("Chapter Ⅻ, x² ½ \uE000ab"))
    }

    /** W-02: scripts the word index cannot match as typed are routed to substring search. */
    @Test
    fun unspacedAndIndicScriptsNeedSubstringSearch() {
        listOf("請求書", "とうきょう", "カタカナ", "รับเงิน", "ພາສາ", "ភាសា", "မြန်မာ", "བོད", "पत्र", "invoice 東京").forEach {
            assertTrue(it, SearchQuery.needsSubstringSearch(it))
        }
        listOf("Café", "Cafe\u0301", "Łódź", "Ärzte", "Москва", "Αθήνα", "كتاب", "4471", "x²").forEach {
            assertFalse(it, SearchQuery.needsSubstringSearch(it))
        }
    }

    @Test
    fun substringTermsKeepCombiningMarksInsideTheTerm() {
        assertEquals(listOf("ใบเสร็จรับเงิน"), SearchQuery.substringTerms("ใบเสร็จรับเงิน"))
        assertEquals(listOf("हिन्दी", "पत्र"), SearchQuery.substringTerms("हिन्दी पत्र!"))
        assertEquals(emptyList<String>(), SearchQuery.substringTerms("\u0301 \u0E31"))
    }
}
