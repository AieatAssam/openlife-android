package org.openlife.app

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.app.ui.OcrTrim
import org.openlife.app.ui.OcrUiState
import java.util.UUID

/**
 * P1-16-R3: memory pressure while visible (RUNNING_LOW or worse) and the UI
 * being hidden both release decoded content and extracted OCR text.
 * Background LRU levels (MODERATE and above) come after UI_HIDDEN, which has
 * already cleared everything.
 */
class TrimMemoryPolicyTest {
    @Test
    fun uiHiddenAndRunningLowClearSensitiveCaches() {
        assertTrue(SensitiveContentTrimPolicy.shouldClear(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertTrue(SensitiveContentTrimPolicy.shouldClear(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(
            "critical is more severe than low",
            SensitiveContentTrimPolicy.shouldClear(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL),
        )
        assertFalse(SensitiveContentTrimPolicy.shouldClear(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))

        val ready = UUID.randomUUID()
        val running = UUID.randomUUID()
        val failed = UUID.randomUUID()
        val trimmed = OcrTrim.dropExtractedText(
            mapOf(
                ready to OcrUiState.Ready(ready, UUID.randomUUID(), emptyList()),
                running to OcrUiState.Running(running),
                failed to OcrUiState.Failed(failed, org.openlife.vault.ocr.OcrFailureReason.TIMEOUT),
            ),
        )
        assertEquals("extracted text is dropped; run state is kept", setOf(running, failed), trimmed.keys)
    }
}
