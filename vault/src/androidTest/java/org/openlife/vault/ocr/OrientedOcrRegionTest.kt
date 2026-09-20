package org.openlife.vault.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.model.Orientation

@RunWith(AndroidJUnit4::class)
class OrientedOcrRegionTest {
    @Test
    fun regionsMapBackToSourcePixelsForRotatedInput() {
        val sourceRegion = OcrEvidenceRegion(10, 20, 30, 40)
        val displayedRegion = OcrEvidenceRegion(20, 70, 40, 90)

        assertEquals(
            sourceRegion,
            OcrCoordinateMapper.toSourcePixels(displayedRegion, 100, 60, Orientation.ROTATE_90),
        )
    }
}
