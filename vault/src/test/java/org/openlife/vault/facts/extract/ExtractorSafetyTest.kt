package org.openlife.vault.facts.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bounded work and untrusted text (P3-03-R3/R4, C2-R29, C2-R47). Every rule
 * stage runs alone, and all stages together, over 10,000-character
 * adversarial strings; the best of several warmed-up runs must be under
 * 50 ms. Time is the test thread's CPU time, so load from other processes
 * on a shared host does not count (wall time when the JVM cannot measure
 * it). CPU and wall times are printed for the verification record.
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
        "time-ann" to fill("1:00 ("),
        "url-many" to fill("www.a "),
        "mixed" to fill("Tel: +44 (0)20 7946 0018 3/4/26 €1,234 https://a.example/x a@b.example 2:30 pm "),
    )

    @Test
    fun adversarialInputsCompleteUnder50ms() {
        val stagesToTime = RuleStage.entries.map { setOf(it) } + listOf(RuleStage.entries.toSet())
        val slowest = mutableListOf<String>()
        for ((name, text) in adversarial) {
            assertTrue(name, text.length >= LENGTH)
            for (stages in stagesToTime) {
                val timing = bestOfRuns(text, stages)
                val millis = timing.cpu
                val label = if (stages.size == 1) stages.single().name else "ALL"
                val printed = "cpu ${"%.2f".format(millis)} ms wall ${"%.2f".format(timing.wall)} ms"
                println("ExtractorSafetyTest $name $label $printed")
                if (millis >= LIMIT_MILLIS) slowest += "$name/$label=${"%.2f".format(millis)}ms"
            }
        }
        assertEquals(emptyList<String>(), slowest)
    }

    /** Ten times the input must not cost much more than ten times the time (linear scanning). */
    @Test
    fun workGrowsLinearly() {
        for (name in listOf("digit-space", "label-run", "at", "groups", "months", "time-ann", "url-many")) {
            val small = adversarial.getValue(name)
            val large = small.repeat(SCALE)
            val all = RuleStage.entries.toSet()
            val ratio = bestOfRuns(large, all).cpu / bestOfRuns(small, all).cpu
            println("ExtractorSafetyTest linearity $name x$SCALE ratio ${"%.1f".format(ratio)}")
            assertTrue("$name grew by $ratio for ${SCALE}x input", ratio < SCALE * 2)
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

    /** The recorded rules version is the per-rule constants joined; they cannot drift apart (review E-10). */
    @Test
    fun rulesVersionJoinsEveryRuleConstant() {
        val rules = listOf(
            DateExtractor.RULE_ID to DateExtractor.RULES_VERSION,
            TimeExtractor.RULE_ID to TimeExtractor.RULES_VERSION,
            DateTimeExtractor.RULE_ID to DateTimeExtractor.RULES_VERSION,
            MoneyExtractor.RULE_ID to MoneyExtractor.RULES_VERSION,
            UrlExtractor.RULE_ID to UrlExtractor.RULES_VERSION,
            EmailExtractor.RULE_ID to EmailExtractor.RULES_VERSION,
            PhoneExtractor.RULE_ID to PhoneExtractor.RULES_VERSION,
        )
        assertEquals(rules.joinToString(",") { (id, version) -> "$id-$version" }, FactExtractor.RULES_VERSION)
    }

    /** §5.4 reads neighbours by ordinal, so two spans sharing one is a caller bug and fails loudly. */
    @Test
    fun duplicateSpanOrdinalsAreRejected() {
        val spans = listOf(ExtractionSpan("a", null, 1, "USD"), ExtractionSpan("b", null, 1, "$5"))
        var rejected = false
        try {
            FactExtractor.extract(spans)
        } catch (expected: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("duplicate ordinals were accepted", rejected)
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

    /** Best (minimum) CPU and wall milliseconds over [RUNS] warmed-up runs. */
    private fun bestOfRuns(text: String, stages: Set<RuleStage>): Timing {
        val spans = listOf(ExtractionSpan("s", null, 1, text))
        repeat(WARMUP) { FactExtractor.extract(spans, ExtractionDeadline.NONE, stages) }
        val runs = (1..RUNS).map {
            val cpuStart = cpuNanos()
            val wallStart = System.nanoTime()
            FactExtractor.extract(spans, ExtractionDeadline.NONE, stages)
            val wall = System.nanoTime() - wallStart
            val cpu = if (cpuStart < 0) wall else cpuNanos() - cpuStart
            Timing(cpu / NANOS_PER_MILLI, wall / NANOS_PER_MILLI)
        }
        return Timing(runs.minOf { it.cpu }, runs.minOf { it.wall })
    }

    /**
     * The test thread's CPU time in nanoseconds, or -1 when the JVM cannot
     * measure it. Read through reflection: unit tests compile against
     * android.jar, which has no java.lang.management, but run on the host JVM.
     */
    private fun cpuNanos(): Long = THREAD_CPU?.let { (bean, method) -> method.invoke(bean) as Long } ?: -1

    private data class Timing(val cpu: Double, val wall: Double)

    private fun codeOnly(text: String): String = text.lines().filterNot { line ->
        val trimmed = line.trim()
        trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/**")
    }.joinToString("\n")

    private class DeadlineExpired : RuntimeException()

    private companion object {
        val THREAD_CPU = runCatching {
            val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
            val type = Class.forName("java.lang.management.ThreadMXBean")
            val supported = type.getMethod("isCurrentThreadCpuTimeSupported").invoke(bean) as Boolean
            if (supported) bean to type.getMethod("getCurrentThreadCpuTime") else null
        }.getOrNull()
        const val LENGTH = 10_000
        const val LIMIT_MILLIS = 50.0
        const val WARMUP = 3
        const val RUNS = 5
        const val SCALE = 10
        const val NANOS_PER_MILLI = 1_000_000.0
    }
}
