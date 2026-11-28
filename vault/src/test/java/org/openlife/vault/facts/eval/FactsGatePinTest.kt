package org.openlife.vault.facts.eval

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * P3-06-R2: eval/facts/v1/gate.json holds exactly the R2 numbers. Loosening one needs a reviewed
 * diff here and an ADR reference (the step notes forbid loosening without an ADR).
 */
class FactsGatePinTest {
    @Test
    fun gateFileHoldsTheSpecifiedThresholds() {
        val text = File(System.getProperty("openlife.rootDir") ?: "..", "eval/facts/v1/gate.json").readText()
        val pinned = Regex("\"(\\w+)\"\\s*:\\s*([0-9.]+)").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].toDouble() }
        assertEquals(
            mapOf(
                "date_precision_min" to 0.97,
                "date_recall_min" to 0.85,
                "money_precision_min" to 0.95,
                "url_precision_min" to 0.95,
                "email_precision_min" to 0.95,
                "phone_precision_min" to 0.95,
                "ambiguous_dm_flagged_min" to 1.0,
                "bare_currency_inferred_max" to 0.0,
            ),
            pinned,
        )
    }
}
