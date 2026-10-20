package org.openlife.app.ui.evidence

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.OrientationTransform
import org.openlife.vault.ocr.OcrCoordinateMapper
import org.openlife.vault.ocr.OcrEvidenceRegion

/**
 * P2-06-R1 / F-27: region highlights use the letterboxed, orientation-corrected
 * image rect. Expected values are independent of the implementation.
 */
class EvidenceGeometryTest {
    @Test
    fun letterboxedWideImageMapsRegionIntoFittedRect() {
        // 100×1000 image in a 1000×320 canvas: the fit is a 32×320 pillarbox.
        val full = EvidenceGeometry.regionToCanvas(
            OcrEvidenceRegion(0, 0, 100, 1000),
            sourceW = 100,
            sourceH = 1000,
            orientation = Orientation.NORMAL,
            canvasSize = Size(1000f, 320f),
            contentScale = ContentScale.Fit,
            zoom = 1f,
            pan = Offset.Zero,
        )
        assertClose(Rect(484f, 0f, 516f, 320f), full)
        assertTrue("highlight must not stretch across the canvas", full.width < 100f)

        val partial = EvidenceGeometry.regionToCanvas(
            OcrEvidenceRegion(0, 0, 50, 250),
            sourceW = 100,
            sourceH = 1000,
            orientation = Orientation.NORMAL,
            canvasSize = Size(1000f, 320f),
            zoom = 1f,
            pan = Offset.Zero,
        )
        assertClose(Rect(484f, 0f, 500f, 80f), partial)
    }

    @Test
    fun letterboxedTallImageMapsRegion() {
        // 1000×100 image in a 320×1000 canvas: the fit is a 320×32 letterbox.
        val rect = EvidenceGeometry.regionToCanvas(
            OcrEvidenceRegion(0, 0, 1000, 100),
            sourceW = 1000,
            sourceH = 100,
            orientation = Orientation.NORMAL,
            canvasSize = Size(320f, 1000f),
            zoom = 1f,
            pan = Offset.Zero,
        )
        assertClose(Rect(0f, 484f, 320f, 516f), rect)
        assertTrue(rect.height < 100f)
    }

    @Test
    fun rotate90MapsRegionThroughDisplayTransform() {
        // Source region produced by mapping display rect (10,20)-(30,40) through ROTATE_90.
        val rect = EvidenceGeometry.regionToCanvas(
            OcrEvidenceRegion(20, 30, 40, 50),
            sourceW = 100,
            sourceH = 60,
            orientation = Orientation.ROTATE_90,
            canvasSize = Size(60f, 100f),
            zoom = 1f,
            pan = Offset.Zero,
        )
        assertClose(Rect(10f, 20f, 30f, 40f), rect)
    }

    @Test
    fun zoomAndPanApplyAfterFit() {
        val zoomed = EvidenceGeometry.regionToCanvas(
            OcrEvidenceRegion(0, 0, 50, 50),
            sourceW = 100,
            sourceH = 100,
            orientation = Orientation.NORMAL,
            canvasSize = Size(100f, 100f),
            zoom = 2f,
            pan = Offset(10f, -4f),
        )
        assertClose(Rect(-40f, -54f, 60f, 46f), zoomed)

        // Zoom is around the fitted image, not the raw canvas.
        val letterboxed = EvidenceGeometry.regionToCanvas(
            OcrEvidenceRegion(0, 0, 100, 1000),
            sourceW = 100,
            sourceH = 1000,
            orientation = Orientation.NORMAL,
            canvasSize = Size(1000f, 320f),
            zoom = 2f,
            pan = Offset.Zero,
        )
        assertClose(Rect(468f, -160f, 532f, 480f), letterboxed, delta = 0.5f)
    }

    @Test
    fun allEightOrientationsRoundTripThroughTheDisplayTransform() {
        val sourceW = 120
        val sourceH = 80
        val displayRegion = OcrEvidenceRegion(8, 12, 40, 36)
        Orientation.entries.forEach { orientation ->
            val sourceRegion = OcrCoordinateMapper.toSourcePixels(
                displayRegion,
                sourceW,
                sourceH,
                orientation,
            )
            val canvas = Size(
                OrientationTransform.displayWidth(sourceW, sourceH, orientation).toFloat(),
                OrientationTransform.displayHeight(sourceW, sourceH, orientation).toFloat(),
            )
            val rect = EvidenceGeometry.regionToCanvas(
                sourceRegion,
                sourceW,
                sourceH,
                orientation,
                canvas,
                zoom = 1f,
                pan = Offset.Zero,
            )
            assertEquals("$orientation left", displayRegion.left.toFloat(), rect.left, 0.01f)
            assertEquals("$orientation top", displayRegion.top.toFloat(), rect.top, 0.01f)
            assertEquals("$orientation right", displayRegion.right.toFloat(), rect.right, 0.01f)
            assertEquals("$orientation bottom", displayRegion.bottom.toFloat(), rect.bottom, 0.01f)
        }
    }

    private fun assertClose(expected: Rect, actual: Rect, delta: Float = 0.05f) {
        assertEquals("left", expected.left, actual.left, delta)
        assertEquals("top", expected.top, actual.top, delta)
        assertEquals("right", expected.right, actual.right, delta)
        assertEquals("bottom", expected.bottom, actual.bottom, delta)
    }
}
