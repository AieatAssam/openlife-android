package org.openlife.vault.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-08-R4: the matched words are highlighted in a bounded, plain-text excerpt. */
class SearchSnippetTest {
    @Test
    fun highlightsEveryWordStartThatMatchesIgnoringCase() {
        val snippet = SearchSnippet.of("Dentist: see the dentist", listOf("dentist"))
        assertEquals("Dentist: see the dentist", snippet.text)
        assertEquals(listOf(0..6, 17..23), snippet.highlights)
    }

    @Test
    fun theLastTokenMatchesAsAPrefixAndEarlierOnesAsWholeWords() {
        val snippet = SearchSnippet.of("pay at reception, paying", listOf("pay", "recep"))
        assertEquals(listOf(0..2, 7..11), snippet.highlights)
    }

    @Test
    fun diacriticsFoldWithoutShiftingOffsets() {
        val snippet = SearchSnippet.of("Le Café Lumière", listOf("cafe", "lumiere"))
        assertEquals(listOf(3..6, 8..14), snippet.highlights)
        assertEquals("Café", snippet.text.substring(3..6))
        assertEquals("Lumière", snippet.text.substring(8..14))
    }

    @Test
    fun decomposedTextKeepsItsOwnOffsets() {
        val decomposed = "café bar"
        val snippet = SearchSnippet.of(decomposed, listOf("cafe"))
        assertEquals(listOf(0..4), snippet.highlights)
    }

    @Test
    fun longTextIsCutAroundTheFirstMatch() {
        val text = "a".repeat(400) + " needle " + "b".repeat(400)
        val snippet = SearchSnippet.of(text, listOf("needle"))
        assertTrue(snippet.text.length <= SearchSnippet.MAX_LENGTH + 2)
        assertTrue(snippet.text.startsWith("…") && snippet.text.endsWith("…"))
        val range = snippet.highlights.single()
        assertEquals("needle", snippet.text.substring(range))
    }

    /** W-02: a substring search highlights the match inside a longer run of text. */
    @Test
    fun substringModeHighlightsInsideWords() {
        assertEquals(listOf(4..6), SearchSnippet.of("東京都の請求書", listOf("請求書"), wholeWords = false).highlights)
        assertEquals(emptyList<IntRange>(), SearchSnippet.of("東京都の請求書", listOf("請求書")).highlights)
    }

    @Test
    fun noMatchStillGivesABoundedExcerpt() {
        val snippet = SearchSnippet.of("x".repeat(1_000), listOf("zzz"))
        assertEquals(emptyList<IntRange>(), snippet.highlights)
        assertTrue(snippet.text.length <= SearchSnippet.MAX_LENGTH + 2)
    }
}
