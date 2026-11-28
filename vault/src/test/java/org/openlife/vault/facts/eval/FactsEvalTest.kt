package org.openlife.vault.facts.eval

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-06-R2: the facts gate over eval/facts/v1/text.tsv. The thresholds are the R2 numbers, pinned
 * against eval/facts/v1/gate.json by FactsGatePinTest.
 */
class FactsEvalTest {
    @Test
    fun gateThresholdsAreMet() {
        val report = FactsEval.measure()
        val failures = mutableListOf<String>()
        atLeast(failures, "DATE precision", report.precision["DATE"], 0.97)
        atLeast(failures, "DATE recall", report.recall["DATE"], 0.85)
        atLeast(failures, "MONEY precision", report.precision["MONEY"], 0.95)
        atLeast(failures, "URL precision", report.precision["URL"], 0.95)
        atLeast(failures, "EMAIL precision", report.precision["EMAIL"], 0.95)
        atLeast(failures, "PHONE precision", report.precision["PHONE"], 0.95)
        atLeast(failures, "ambiguous d/m flagged", report.ambiguousDmFlagged, 1.0)
        if (report.bareCurrencyInferred != 0) {
            failures += "currency inferred from a bare symbol: ${report.bareCurrencyInferred} (must be 0)"
        }
        assertTrue("facts gate failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun atLeast(failures: MutableList<String>, name: String, rate: Rate?, minimum: Double) {
        val value = rate?.value
        if (value == null) {
            failures += "$name: no measurement (n=${rate?.total ?: 0})"
        } else if (value < minimum) {
            failures += "$name ${"%.3f".format(value)} < $minimum (n=${rate.total})"
        }
    }
}
