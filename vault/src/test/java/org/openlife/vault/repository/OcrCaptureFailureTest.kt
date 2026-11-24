package org.openlife.vault.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openlife.vault.ocr.OcrFailureReason

/** P1-18-R8 (V-05) on the JVM: a transient check is never reported as a corrupt source. */
class OcrCaptureFailureTest {

    @Test
    fun transientCheckIsRetryableNotSourceCorrupt() {
        assertEquals(OcrFailureReason.SOURCE_TEMPORARILY_UNAVAILABLE, ocrCaptureFailureFor(ArtefactCheck.Transient))
    }

    @Test
    fun onlyEvidenceAboutTheBytesIsSourceCorrupt() {
        assertEquals(OcrFailureReason.SOURCE_CORRUPT, ocrCaptureFailureFor(ArtefactCheck.Corrupt))
        assertEquals(OcrFailureReason.SOURCE_CORRUPT, ocrCaptureFailureFor(ArtefactCheck.Missing))
        assertNull(ocrCaptureFailureFor(ArtefactCheck.Verified(ByteArray(1))))
    }
}
