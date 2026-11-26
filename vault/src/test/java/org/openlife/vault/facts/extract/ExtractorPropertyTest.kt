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
        // Review 2026-11-26: zero-led groups, fractional seconds, sign look-alikes, Cf/Mn marks, Unicode spaces
        "000", "05", ".000", ",5", "\u2212", "\u2014", "±", "\u200B", "\u200D", "\u0301", "\u0305", "\t",
        "\u2009", "\u202F", "\u00A0", "GMT", "UTC", "h", "_", "xn--", "-.",
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

    /**
     * Guards against a vacuous pass: known positives embedded in noise are
     * found, or abstained where the rules say. Only the amount is fenced
     * with `;`: a dash or mark beside an amount abstains it (E-04, Q10). The
     * other positives touch the noise directly through one character, and
     * the expectation follows the rules: a space separator or tab (A-8)
     * still reads; a mark abstains, covering the mark (A-6); a dash or minus
     * before a time stops it (A-2); before any other kind it does not.
     */
    @Test
    fun knownPositivesSurviveSurroundingNoise() {
        val random = Random(SEED + 2)
        val failures = mutableListOf<String>()
        var reads = 0
        repeat(ITERATIONS / 4) {
            val positive = positives[random.nextInt(positives.size)]
            val left = touches[random.nextInt(touches.size)]
            val right = touches[random.nextInt(touches.size)]
            val before = noise(random) + if (positive.kind == ExtractedKind.MONEY) "; " else left
            val line = before + positive.text + (if (positive.kind == ExtractedKind.MONEY) " ;" else right) +
                noise(random)
            val spans = listOf(ExtractionSpan("s", null, 1, line))
            val result = FactExtractor.extract(spans)
            checkInvariants(spans, result)
            val found = result.candidates.any { it.canonicalText == positive.expected }
            val expected = positive.kind == ExtractedKind.MONEY || readsBeside(positive.kind, left, right)
            if (found) reads++
            if (found != expected) failures += "<$line> found=$found"
            val marks = listOfNotNull(
                (before.length - 1).takeIf { left == MARK && positive.kind != ExtractedKind.PHONE },
                (before.length + positive.text.length).takeIf { right == MARK && positive.kind != ExtractedKind.MONEY },
            )
            val uncovered = marks.filterNot { at -> result.abstentions.any { at in it.startOffset until it.endOffset } }
            if (uncovered.isNotEmpty()) failures += "<$line> mark at $uncovered not covered by an abstention"
        }
        assertEquals(emptyList<String>(), failures.take(FAILURES_SHOWN))
        assertTrue("only $reads positives read", reads > ITERATIONS / 8)
    }

    /** An adjacent NBSP, thin space or tab is a boundary, and the token still reads (A-8). */
    @Test
    fun spaceSeparatorsBesideTokensStillRead() {
        val cases = listOf(
            "Due:\u00A02026-09-16\t" to "2026-09-16",
            "at\t14:30\u00A0" to "14:30",
            "Total\u00A0€12.50\t" to "12.50 EUR",
            "see\u2009https://example.org/a\u00A0" to "https://example.org/a",
            "mail\tann@example.org\u202F" to "ann@example.org",
            "Tel:\u00A001632 960123\t" to "01632960123",
        )
        for ((text, expected) in cases) {
            val result = FactExtractor.extract(listOf(ExtractionSpan("s", null, 1, text)))
            assertEquals(text, listOf(expected), result.candidates.map { it.canonicalText })
        }
    }

    private fun readsBeside(kind: ExtractedKind, left: String, right: String): Boolean = when {
        left == MARK || right == MARK -> false
        kind == ExtractedKind.TIME && left in DASHES -> false
        else -> true
    }

    private data class Positive(val kind: ExtractedKind, val text: String, val expected: String)

    private val positives = listOf(
        Positive(ExtractedKind.DATE, "2026-09-16", "2026-09-16"),
        Positive(ExtractedKind.TIME, "14:30", "14:30"),
        Positive(ExtractedKind.MONEY, "€12.50", "12.50 EUR"),
        Positive(ExtractedKind.URL, "https://example.org/a", "https://example.org/a"),
        Positive(ExtractedKind.EMAIL, "ann@example.org", "ann@example.org"),
        Positive(ExtractedKind.PHONE, "Tel: 01632 960123", "01632960123"),
    )

    private val touches = listOf(" ", "\u00A0", "\t", "\u2009", MARK, "\u2212", "\u2014")

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

    private val noiseWords = listOf("Total", "ref", "item", "and", "—", ";", "\u2212", "\u200B", "\u00A0", "\t")

    private fun noise(random: Random): String = buildString {
        repeat(random.nextInt(0, 4)) { append(noiseWords.random(random)) }
    }

    private companion object {
        const val SEED = 20261126
        const val ITERATIONS = 4_000
        const val FAILURES_SHOWN = 10
        const val MARK = "\u200B"
        val DASHES = setOf("\u2212", "\u2014")
    }
}
