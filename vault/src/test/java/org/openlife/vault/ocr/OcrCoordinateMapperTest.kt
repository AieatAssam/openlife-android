package org.openlife.vault.ocr

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openlife.vault.model.Orientation

class OcrCoordinateMapperTest {
    private val sourceWidth = 100
    private val sourceHeight = 60
    private val displayRegion = OcrEvidenceRegion(left = 10, top = 20, right = 30, bottom = 40)

    @Test
    fun `normal orientation preserves source pixels`() {
        assertEquals(
            displayRegion,
            OcrCoordinateMapper.toSourcePixels(
                displayRegion,
                sourceWidth,
                sourceHeight,
                Orientation.NORMAL,
            ),
        )
    }

    @Test
    fun `quarter turn maps display rectangle back to source pixels`() {
        assertEquals(
            OcrEvidenceRegion(left = 20, top = 70, right = 40, bottom = 90),
            OcrCoordinateMapper.toSourcePixels(
                displayRegion,
                sourceWidth,
                sourceHeight,
                Orientation.ROTATE_90,
            ),
        )
    }

    @Test
    fun `transpose swaps the two axes`() {
        assertEquals(
            OcrEvidenceRegion(left = 20, top = 10, right = 40, bottom = 30),
            OcrCoordinateMapper.toSourcePixels(
                displayRegion,
                sourceWidth,
                sourceHeight,
                Orientation.TRANSPOSE,
            ),
        )
    }
}
