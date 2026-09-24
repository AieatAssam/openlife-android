package org.openlife.app

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

        // The OCR half: the trim clearer is OcrPresenter.clearTransient, which drops every
        // held text (OcrViewModelTest.clearTransientDropsTextAndFlowRepopulates, P2-01).
    }
}
