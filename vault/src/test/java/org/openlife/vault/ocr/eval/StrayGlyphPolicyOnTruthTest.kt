package org.openlife.vault.ocr.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.eval.ocr.core.FixtureCategory
import org.openlife.eval.ocr.core.FixtureTruth
import org.openlife.eval.ocr.core.ManifestCodec
import org.openlife.eval.ocr.core.PolicyCost
import org.openlife.eval.ocr.core.ScriptClass
import org.openlife.eval.ocr.core.ScriptPolicyCost
import org.openlife.eval.ocr.core.SpanObservation
import org.openlife.eval.ocr.core.TruthCodec
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrRunStateMachine
import org.openlife.vault.ocr.OcrScriptPolicy
import org.openlife.vault.ocr.OcrScriptStatus
import org.openlife.vault.ocr.OcrSpanDraft
import java.io.File

/**
 * P2-04-R5 (V-15) on ground truth: the production script rules applied to a
 * perfect recogniser's output, one span per truth line. This is the cost of
 * each policy when recognition is exact. It is not an engine measurement;
 * the engines' numbers come from the instrumented OcrEvaluationTest.
 */
class StrayGlyphPolicyOnTruthTest {
    private val setDir = File(System.getProperty("openlife.rootDir") ?: "..", "eval/ocr/v1")

    @Test
    fun productionPoliciesOnAPerfectRecogniser() {
        val manifest = ManifestCodec.decode(File(setDir, "manifest.json").readText())
        val truths = manifest.fixtures.map { TruthCodec.decode(File(setDir, it.truth).readText()) }
        val rows = truths.map { truth -> truth to costOnTruth(truth) }

        rows.forEach { (truth, row) ->
            val (abstained, comparison) = row
            val latin = truth.lines.count { it.script == ScriptClass.LATIN }
            assertEquals("${truth.id}: production abstains exactly on non-Latin text", truth.expectedAbstain, abstained)
            assertEquals(truth.id, 0, comparison.unmatchedLines)
            if (truth.expectedAbstain) {
                assertEquals(
                    "${truth.id}: whole revision loses every Latin line",
                    PolicyCost(latin, 0),
                    comparison.wholeRevision,
                )
            } else {
                assertEquals(truth.id, PolicyCost(0, 0), comparison.wholeRevision)
            }
            assertEquals("${truth.id}: per span loses no Latin line", PolicyCost(0, 0), comparison.perSpan)
        }

        val strayReceipts = rows.filter { (truth, _) ->
            truth.category == FixtureCategory.RECEIPT && truth.scriptClass == ScriptClass.MIXED
        }
        assertEquals(2, strayReceipts.size)
        strayReceipts.forEach { (truth, row) -> assertTrue(truth.id, row.second.wholeRevision.latinLinesLost >= 10) }
        val currency = truths.filter { truth -> truth.lines.any { line -> line.text.any { it in "€£¥" } } }
        assertTrue(currency.isNotEmpty())
        currency.filterNot { it.expectedAbstain }.forEach { assertFalse(it.id, costOnTruth(it).first) }

        println("P2-04-R5 policy cost on ground truth (perfect recogniser, production classifier):")
        rows.filter { it.first.expectedAbstain }.forEach { (truth, row) ->
            val c = row.second
            println(
                "${truth.id} ${truth.scriptClass.key} latin=${c.latinLines} nonLatin=${c.nonLatinLines} " +
                    "whole(lost=${c.wholeRevision.latinLinesLost},accepted=${c.wholeRevision.nonLatinAccepted}) " +
                    "perSpan(lost=${c.perSpan.latinLinesLost},accepted=${c.perSpan.nonLatinAccepted})",
            )
        }
    }

    private fun costOnTruth(truth: FixtureTruth): Pair<Boolean, org.openlife.eval.ocr.core.ScriptPolicyComparison> {
        val drafts = truth.lines.map {
            OcrSpanDraft(it.text, null, OcrEvidenceRegion(it.box.left, it.box.top, it.box.right, it.box.bottom))
        }
        val decision = OcrRunStateMachine.decide(OcrRunStateMachine.EngineOutcome.Output(drafts))
        val abstained = decision is OcrRunStateMachine.Decision.Terminal &&
            decision.reason == OcrFailureReason.UNSUPPORTED_SCRIPT
        val comparison = ScriptPolicyCost.compare(
            truth = truth.lines,
            rawSpans = truth.lines.map { SpanObservation(it.text, it.box) },
            wholeRevisionAbstained = abstained,
            minIou = 0.5,
            spanIsUnsupported = { OcrScriptPolicy.classify(it) == OcrScriptStatus.UNSUPPORTED },
        )
        return abstained to comparison
    }
}
