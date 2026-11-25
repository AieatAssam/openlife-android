package org.openlife.eval.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openlife.eval.ocr.core.FixtureCategory
import org.openlife.eval.ocr.core.GateCodec
import org.openlife.eval.ocr.core.JsonFormatException
import java.io.File

/**
 * P2-04-R4: the thresholds live in eval/ocr/v1/gate.json. This test restates
 * P2-04-R3 so that changing a threshold needs a reviewed diff in two places.
 */
class GateFileTest {
    private val gateFile = File(System.getProperty("openlife.rootDir") ?: "..", "eval/ocr/v1/gate.json")

    @Test
    fun committedGateIsP204R3() {
        val gate = GateCodec.decode(gateFile.readText())
        assertEquals(0.5, gate.lineMatchIou, 0.0)
        assertEquals(0.08, gate.cerMeanMax.getValue(FixtureCategory.SCREENSHOT), 0.0)
        assertEquals(0.15, gate.cerMeanMax.getValue(FixtureCategory.RECEIPT), 0.0)
        assertEquals(0.10, gate.cerMeanMax.getValue(FixtureCategory.LETTER), 0.0)
        assertEquals(0.90, gate.lineMatchRateMin, 0.0)
        assertEquals(1.0, gate.abstentionCorrectRateMin, 0.0)
        // Acting-owner ruling 2026-11-25 (review X-03): one abstention on a Latin fixture fails the engine.
        assertEquals(0L, gate.latinAbstentionsMax)
        assertEquals(0L, gate.timeoutsMax)
        assertEquals(6_000L, gate.durationP95MsMax)
        assertEquals("120 MiB in KiB, the unit of Debug.getPss()", 120L * 1024L, gate.peakPssDeltaKibMax)
        // Review X-05: TesseractOcrEngine.MODEL_VERSION and MlKitOcrEngine.modelVersion (vault GatePinsTest).
        assertEquals(
            listOf("tesseract-eng-fast" to "t4a-4.9.0+tessdata_fast-4.1.0-7d4322bd", "mlkit-latin" to "16.0.1"),
            gate.engines.map { it.id to it.modelVersion },
        )
    }

    @Test
    fun aGateFileMissingAThresholdIsRejected() {
        val text = gateFile.readText()
        val broken = listOf(
            text.replace("\"duration_p95_ms_max\"", "\"duration_p95_ms_maximum\""),
            text.replace("\"letter\"", "\"letters\""),
            text.replace("\"schema\": \"openlife-ocr-gate/2\"", "\"schema\": \"openlife-ocr-gate/1\""),
            text.replace("\"latin_abstentions_max\"", "\"latin_abstention_max\""),
            text.replace("\"model_version\": \"16.0.1\"", "\"version\": \"16.0.1\""),
            text.replace("0.08", "\"0.08\""),
        )
        broken.forEachIndexed { index, candidate ->
            assertTrue("mutation $index applied", candidate != text)
            try {
                GateCodec.decode(candidate)
                fail("mutation $index must be rejected")
            } catch (expected: JsonFormatException) {
                // A partial gate never evaluates.
            }
        }
    }
}
