package org.openlife.vault.ocr.eval

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openlife.eval.ocr.core.GateCodec
import org.openlife.vault.ocr.MlKitOcrEngine
import org.openlife.vault.ocr.TesseractOcrEngine
import java.io.File

/**
 * P2-04 review X-05: eval/ocr/v1/gate.json pins each engine to the model
 * version production records. Changing an engine's model without updating
 * the gate fails here, and results measured with another model are INVALID.
 */
class GatePinsTest {
    @Test
    fun gatePinsTheModelVersionsProductionRecords() {
        val gate = GateCodec.decode(
            File(System.getProperty("openlife.rootDir") ?: "..", "eval/ocr/v1/gate.json").readText(),
        )
        val mlKit = MlKitOcrEngine()
        assertEquals(
            listOf(TesseractOcrEngine.ID to TesseractOcrEngine.MODEL_VERSION, mlKit.id to mlKit.modelVersion),
            gate.engines.map { it.id to it.modelVersion },
        )
    }
}
