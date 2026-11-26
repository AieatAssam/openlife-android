package org.openlife.vault.facts.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Properties over seeded random text (C2-R26, C2-R28): no exception, offsets
 * inside the input, raw text equal to the slice, no two outputs sharing a
 * character, candidate sets ordered and duplicate-free, determinism.
 */
class ExtractorPropertyTest {
    private val pieces = listOf(
        "0", "1", "2", "3", "9", "12", "2026", "26", "/", "-", ".", ",", ":", " ", "  ", "'", " ", " ",
        "€", "$", "£", "kr", "US$", "USD", "AUD", "SSP", "CUP", "TRY", "EUR", "am", "pm", "PM", "CET", "Z", "+",
        "(", ")", "–", "to", "and", "&", "Tel: ", "Phone ", "tel:", "Wed ", "Sep ", "April ", "May", "th",
        "http://", "https://", "www.", "@", "mailto:", "example", ".org", "a", "B", "٣", "１", "T",
        "\n", "x", "é", "😀", "\uD800",
    )

    @Test
    fun offsetsAlwaysSliceToRawText() {
        val random = Random(SEED)
        repeat(ITERATIONS) {
            val spans = List(random.nextInt(1, 4)) { index ->
                ExtractionSpan("s$index", null, index + 1, randomText(random))
            }
            val result = FactExtractor.extract(spans)
            checkInvariants(spans, result)
        }
    }

    @Test
    fun extractionIsDeterministic() {
        val random = Random(SEED + 1)
        repeat(ITERATIONS / 4) {
            val spans = listOf(ExtractionSpan("s", null, 1, randomText(random)))
            assertEquals(FactExtractor.extract(spans), FactExtractor.extract(spans))
        }
    }

    /** Guards against a vacuous pass: known positives embedded in noise are still found. */
    @Test
    fun knownPositivesSurviveSurroundingNoise() {
        val positives = listOf(
            "2026-09-16" to "2026-09-16",
            "14:30" to "14:30",
            "€12.50" to "12.50 EUR",
            "https://example.org/a" to "https://example.org/a",
            "ann@example.org" to "ann@example.org",
            "Tel: 01632 960123" to "01632960123",
        )
        val random = Random(SEED + 2)
        var found = 0
        repeat(ITERATIONS / 4) {
            val (text, expected) = positives[random.nextInt(positives.size)]
            val line = "${noise(random)} $text ${noise(random)}"
            val result = FactExtractor.extract(listOf(ExtractionSpan("s", null, 1, line)))
            checkInvariants(listOf(ExtractionSpan("s", null, 1, line)), result)
            if (result.candidates.any { it.canonicalText == expected }) found++
        }
        assertEquals(ITERATIONS / 4, found)
    }

    private fun checkInvariants(spans: List<ExtractionSpan>, result: ExtractionResult) {
        for (span in spans) {
            val text = span.text
            val candidateRanges = result.candidates.filter { it.spanId == span.spanId }
                .map { it.startOffset until it.endOffset }
            val abstentionRanges = result.abstentions.filter { it.spanId == span.spanId }
                .map { it.startOffset until it.endOffset }
            val ranges = candidateRanges + abstentionRanges
            for (candidate in result.candidates.filter { it.spanId == span.spanId }) {
                assertTrue(candidate.startOffset >= 0 && candidate.endOffset <= text.length)
                assertEquals(text.substring(candidate.startOffset, candidate.endOffset), candidate.rawText)
                checkCandidateSet(candidate)
            }
            for (abstention in result.abstentions.filter { it.spanId == span.spanId }) {
                assertTrue(abstention.startOffset in 0 until abstention.endOffset)
                assertTrue(abstention.endOffset <= text.length)
            }
            val sorted = ranges.sortedBy { it.first }
            for (index in 1 until sorted.size) {
                assertTrue("overlap in <$text>: $sorted", sorted[index - 1].last < sorted[index].first)
            }
        }
    }

    private fun checkCandidateSet(candidate: ExtractedCandidate) {
        val values = candidate.ambiguity
        if (values.isEmpty()) return
        assertTrue(values.size in 2..6)
        assertEquals(values.distinct(), values)
        assertTrue(values.all { it.kind == candidate.kind })
        assertEquals(values.map { it.canonicalText() }.joinToString(" | "), candidate.canonicalText)
    }

    private fun randomText(random: Random): String = buildString {
        repeat(random.nextInt(0, 24)) { append(pieces[random.nextInt(pieces.size)]) }
    }

    private fun noise(random: Random): String = buildString {
        repeat(random.nextInt(0, 4)) { append(listOf("Total", "ref", "item", "and", "—", ";").random(random)) }
    }

    private companion object {
        const val SEED = 20261126
        const val ITERATIONS = 4_000
    }
}
