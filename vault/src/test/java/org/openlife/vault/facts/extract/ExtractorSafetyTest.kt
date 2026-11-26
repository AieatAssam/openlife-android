package org.openlife.vault.facts.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bounded work and untrusted text (P3-03-R3/R4, C2-R29, C2-R47). Every rule
 * stage runs alone, and all stages together, over 10,000-character
 * adversarial strings; the best of several warmed-up runs must be under
 * 50 ms. The timings are printed for the verification record.
 */
class ExtractorSafetyTest {
    private val adversarial: Map<String, String> = linkedMapOf(
        "digits" to fill("1"),
        "digit-slash" to fill("1/"),
        "digit-dot" to fill("1."),
        "digit-dash" to fill("1-"),
        "digit-colon" to fill("1:"),
        "digit-space" to fill("1 "),
        "groups" to fill("123 "),
        "commas" to fill("1,"),
        "symbols" to fill("€1 "),
        "dollar" to fill("$"),
        "codes" to fill("USD "),
        "plus" to fill("+1 "),
        "label-run" to "Tel " + fill("1 "),
        "parens" to fill("("),
        "paren-digits" to fill("(1)"),
        "at" to fill("a@"),
        "at-dots" to fill("@a."),
        "local" to fill("a").dropLast(1) + "@",
        "schemes" to fill("http://"),
        "hosts" to "https://" + fill("a."),
        "www" to fill("www."),
        "range-words" to fill("3 to "),
        "weekdays" to fill("Mon "),
        "months" to fill("1 April "),
        "times" to fill("10:30 - "),
        "markers" to fill("1 pm "),
        "uppercase" to "14:30 " + fill("A"),
        "non-ascii-digits" to fill("1\u0663"),
        "mixed" to fill("Tel: +44 (0)20 7946 0018 3/4/26 €1,234 https://a.example/x a@b.example 2:30 pm "),
    )

    @Test
    fun adversarialInputsCompleteUnder50ms() {
        val stagesToTime = RuleStage.entries.map { setOf(it) } + listOf(RuleStage.entries.toSet())
        val slowest = mutableListOf<String>()
        for ((name, text) in adversarial) {
            assertTrue(name, text.length >= LENGTH)
            for (stages in stagesToTime) {
                val millis = bestOfRuns(text, stages)
                val label = if (stages.size == 1) stages.single().name else "ALL"
                println("ExtractorSafetyTest $name $label ${"%.2f".format(millis)} ms")
                if (millis >= LIMIT_MILLIS) slowest += "$name/$label=${"%.2f".format(millis)}ms"
            }
        }
        assertEquals(emptyList<String>(), slowest)
    }

    /** Ten times the input must not cost much more than ten times the time (linear scanning). */
    @Test
    fun workGrowsLinearly() {
        for (name in listOf("digit-space", "label-run", "at", "groups", "months")) {
            val small = adversarial.getValue(name)
            val large = small.repeat(SCALE)
            val ratio = bestOfRuns(large, RuleStage.entries.toSet()) / bestOfRuns(small, RuleStage.entries.toSet())
            println("ExtractorSafetyTest linearity $name x$SCALE ratio ${"%.1f".format(ratio)}")
            assertTrue("$name grew by $ratio for ${SCALE}x input", ratio < SCALE * 4)
        }
    }

    @Test
    fun deadlineIsCheckedWhileScanning() {
        var checks = 0
        val text = fill("a")
        FactExtractor.extract(listOf(ExtractionSpan("s", null, 1, text)), ExtractionDeadline { checks++ })
        assertTrue("only $checks checks for $LENGTH characters", checks >= LENGTH / 1_024)
        val expired = ExtractionDeadline { throw DeadlineExpired() }
        var stopped = false
        try {
            FactExtractor.extract(listOf(ExtractionSpan("s", null, 1, "2026-09-16")), expired)
        } catch (expected: DeadlineExpired) {
            stopped = true
        }
        assertTrue(stopped)
    }

    /** C2-R47: hand-written scanners only; nothing that resolves, decodes or floats. */
    @Test
    fun noRegexResolvingOrFloatingPointApisInExtractors() {
        val forbidden = listOf(
            "Regex", "toRegex", "java.util.regex", "Pattern", ".matches(", "java.net.", "URL(", "URI(",
            "android.", "Linkify", "Patterns", "Double", "Float", "toDouble", "toFloat", "URLDecoder", "IDN",
        )
        val sources = File("src/main/java/org/openlife/vault/facts/extract").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("no extractor sources found", sources.size >= 5)
        val hits = sources.flatMap { file ->
            val code = codeOnly(file.readText())
            forbidden.filter { code.contains(it) }.map { "${file.name}: $it" }
        }
        assertEquals(emptyList<String>(), hits)
    }

    private fun fill(piece: String): String = piece.repeat(LENGTH / piece.length + 1).take(LENGTH)

    private fun bestOfRuns(text: String, stages: Set<RuleStage>): Double {
        val spans = listOf(ExtractionSpan("s", null, 1, text))
        repeat(WARMUP) { FactExtractor.extract(spans, ExtractionDeadline.NONE, stages) }
        return (1..RUNS).minOf {
            val start = System.nanoTime()
            FactExtractor.extract(spans, ExtractionDeadline.NONE, stages)
            (System.nanoTime() - start) / NANOS_PER_MILLI
        }
    }

    private fun codeOnly(text: String): String = text.lines().filterNot { line ->
        val trimmed = line.trim()
        trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/**")
    }.joinToString("\n")

    private class DeadlineExpired : RuntimeException()

    private companion object {
        const val LENGTH = 10_000
        const val LIMIT_MILLIS = 50.0
        const val WARMUP = 3
        const val RUNS = 5
        const val SCALE = 10
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}
