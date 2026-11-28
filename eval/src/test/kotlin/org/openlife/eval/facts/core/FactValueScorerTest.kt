package org.openlife.eval.facts.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P3-06 follow-up, item 2 (finding F1): an image candidate is correct on kind and canonical value only.
 * A raw-text difference caused by OCR (a dropped or inserted space) is reported, never scored as wrong.
 */
class FactValueScorerTest {
    private fun truth(kind: String, raw: String, vararg readings: String) =
        TruthUnit(kind, raw, readings.toSet(), clarification = false)

    private fun emit(kind: String, raw: String, vararg readings: String) =
        EmittedValue(kind, raw, readings.toSet(), clarification = false)

    @Test
    fun droppedSpaceWithTheTrueValueIsExactAndCountedAsRawDifference() {
        // Tesseract img-16: "9:15 am" read "9:15am", canonical 09:15 is correct.
        val row = FactValueScorer.score(listOf(truth("TIME", "9:15 am", "09:15")), listOf(emit("TIME", "9:15am", "09:15")))
        assertEquals(ValueOutcome.EXACT, row.values.single().outcome)
        assertEquals(true, row.values.single().rawDiffers)
        assertEquals(listOf(TruthStatus.EXACT), row.units)
    }

    @Test
    fun droppedSpaceInAPhoneNumberIsExact() {
        // Tesseract img-20: "+1 555 123 4567" read "+1555 123 4567", canonical +15551234567 is correct.
        val row = FactValueScorer.score(
            listOf(truth("PHONE", "+1 555 123 4567", "+15551234567")),
            listOf(emit("PHONE", "+1555 123 4567", "+15551234567")),
        )
        assertEquals(ValueOutcome.EXACT, row.values.single().outcome)
        assertEquals(true, row.values.single().rawDiffers)
    }

    @Test
    fun identicalRawAndValueIsExactWithoutRawDifference() {
        val row = FactValueScorer.score(listOf(truth("MONEY", "€12.50", "12.50 EUR")), listOf(emit("MONEY", "€12.50", "12.50 EUR")))
        assertEquals(ValueOutcome.EXACT, row.values.single().outcome)
        assertEquals(false, row.values.single().rawDiffers)
    }

    @Test
    fun wrongValueWithTheSameRawIsWrongValue() {
        val row = FactValueScorer.score(listOf(truth("MONEY", "€3.50", "3.50 EUR")), listOf(emit("MONEY", "€3.50", "-3.50 EUR")))
        assertEquals(ValueOutcome.WRONG_VALUE, row.values.single().outcome)
        assertEquals(false, row.values.single().rawDiffers)
        assertEquals(listOf(TruthStatus.WRONG), row.units)
    }

    @Test
    fun wrongValueWithADroppedSpaceIsWrongValueNotUnexpected() {
        // The raw texts differ only by a space and the value is wrong (a truncated value).
        val wrong = FactValueScorer.score(
            listOf(truth("URL", "https://example.org/a?b=1 &c=2", "https://example.org/a?b=1&c=2")),
            listOf(emit("URL", "https://example.org/a?b=1&c=2", "https://example.org/a?b=1")),
        )
        assertEquals(ValueOutcome.WRONG_VALUE, wrong.values.single().outcome)
        assertEquals(true, wrong.values.single().rawDiffers)
        assertEquals(listOf(TruthStatus.WRONG), wrong.units)
    }

    @Test
    fun clarificationWithTheTruthAsASupersetIsSuperset() {
        val truthUnit = TruthUnit("DATE", "10-11-2026", setOf("2026-10-11", "2026-11-10"), clarification = true)
        val emitted = EmittedValue("DATE", "10-11-2026", setOf("2026-10-11", "2026-11-10", "2026-12-10"), clarification = true)
        val row = FactValueScorer.score(listOf(truthUnit), listOf(emitted))
        assertEquals(ValueOutcome.SUPERSET, row.values.single().outcome)
    }

    @Test
    fun clarificationWithTheSameReadingsIsExactWhateverTheSpacing() {
        val truthUnit = TruthUnit("DATE", "1.2.2026", setOf("2026-01-02", "2026-02-01"), clarification = true)
        val emitted = EmittedValue("DATE", "1. 2.2026", setOf("2026-01-02", "2026-02-01"), clarification = true)
        val row = FactValueScorer.score(listOf(truthUnit), listOf(emitted))
        assertEquals(ValueOutcome.EXACT, row.values.single().outcome)
        assertEquals(true, row.values.single().rawDiffers)
    }

    @Test
    fun kindMismatchNeverPairsAndIsUnexpected() {
        val row = FactValueScorer.score(listOf(truth("DATE", "5.00", "5.00 EUR")), listOf(emit("MONEY", "5.00", "5.00 EUR")))
        assertEquals(ValueOutcome.UNEXPECTED, row.values.single().outcome)
        assertEquals(listOf(TruthStatus.OPEN), row.units)
    }

    @Test
    fun aTruthUnitIsPairedOnce() {
        val row = FactValueScorer.score(
            listOf(truth("TIME", "9:15 am", "09:15")),
            listOf(emit("TIME", "9:15am", "09:15"), emit("TIME", "9:15 am", "09:15")),
        )
        assertEquals(listOf(ValueOutcome.EXACT, ValueOutcome.UNEXPECTED), row.values.map { it.outcome })
    }

    @Test
    fun noTruthMakesEveryCandidateUnexpected() {
        val row = FactValueScorer.score(emptyList(), listOf(emit("EMAIL", "a@example.org", "a@example.org")))
        assertEquals(ValueOutcome.UNEXPECTED, row.values.single().outcome)
    }
}
