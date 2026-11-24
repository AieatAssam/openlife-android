package org.openlife.app.ui.evidence

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntRect
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

    /**
     * P2-06-R14 (E-07): an independent oracle. Source 200×100, region
     * (20,10)-(60,30) in source pixels, canvas 400×400. The display rect for
     * each EXIF orientation was worked out by hand from the EXIF definitions
     * (not from OcrCoordinateMapper or the code under test):
     *
     * - upright orientations show 200×100, fit ×2 to 400×200, letterboxed
     *   100 px from the top: canvas = (2l, 100 + 2t, 2r, 100 + 2b);
     * - quarter-turn orientations show 100×200, fit ×2 to 200×400,
     *   pillarboxed 100 px from the left: canvas = (100 + 2l, 2t, 100 + 2r, 2b).
     */
    @Test
    fun allEightOrientationsMatchHandComputedLetterboxedRects() {
        val expected = mapOf(
            // display (20,10,60,30)
            Orientation.NORMAL to Rect(40f, 120f, 120f, 160f),
            // mirror x' = 200 - x: display (140,10,180,30)
            Orientation.FLIP_HORIZONTAL to Rect(280f, 120f, 360f, 160f),
            // x' = 200 - x, y' = 100 - y: display (140,70,180,90)
            Orientation.ROTATE_180 to Rect(280f, 240f, 360f, 280f),
            // mirror y' = 100 - y: display (20,70,60,90)
            Orientation.FLIP_VERTICAL to Rect(40f, 240f, 120f, 280f),
            // x' = y, y' = x: display (10,20,30,60) in 100×200
            Orientation.TRANSPOSE to Rect(120f, 40f, 160f, 120f),
            // 90° clockwise, x' = 100 - y, y' = x: display (70,20,90,60)
            Orientation.ROTATE_90 to Rect(240f, 40f, 280f, 120f),
            // x' = 100 - y, y' = 200 - x: display (70,140,90,180)
            Orientation.TRANSVERSE to Rect(240f, 280f, 280f, 360f),
            // 270° clockwise, x' = y, y' = 200 - x: display (10,140,30,180)
            Orientation.ROTATE_270 to Rect(120f, 280f, 160f, 360f),
        )
        assertEquals("every orientation has an expected rect", Orientation.entries.toSet(), expected.keys)
        expected.forEach { (orientation, rect) ->
            val actual = EvidenceGeometry.regionToCanvas(
                OcrEvidenceRegion(20, 10, 60, 30),
                sourceW = 200,
                sourceH = 100,
                orientation = orientation,
                canvasSize = Size(400f, 400f),
                zoom = 1f,
                pan = Offset.Zero,
            )
            assertClose(rect, actual, message = "$orientation")
        }
    }

    /** P2-06-R14 (E-07): the helpers the zoomable image relies on, with hand-computed values. */
    @Test
    fun clampPanPanToRevealSmallestSpanAtAndNextDoubleTapZoom() {
        val square = Size(100f, 100f)
        // clampPan: at 3x a 100 px canvas shows a 300 px image, so pan is limited to ±100.
        assertEquals(Offset(100f, -100f), EvidenceGeometry.clampPan(Offset(250f, -250f), 100, 100, Orientation.NORMAL, square, zoom = 3f))
        assertEquals(Offset.Zero, EvidenceGeometry.clampPan(Offset(30f, 30f), 100, 100, Orientation.NORMAL, square, zoom = 1f))
        // Pillarboxed 100×1000 in 1000×320: at 2x the image is 64×640, so only vertical pan (±160).
        assertEquals(
            Offset(0f, 160f),
            EvidenceGeometry.clampPan(Offset(50f, 500f), 100, 1000, Orientation.NORMAL, Size(1000f, 320f), zoom = 2f),
        )

        // panToReveal at 3x, pan 0: (45,45)-(55,55) draws at 35..65 and is already visible.
        assertEquals(
            Offset.Zero,
            EvidenceGeometry.panToReveal(OcrEvidenceRegion(45, 45, 55, 55), 100, 100, Orientation.NORMAL, square, zoom = 3f, pan = Offset.Zero),
        )
        // (80,80)-(90,90) draws at 140..170; centring needs -105, clamped to -100.
        assertEquals(
            Offset(-100f, -100f),
            EvidenceGeometry.panToReveal(OcrEvidenceRegion(80, 80, 90, 90), 100, 100, Orientation.NORMAL, square, zoom = 3f, pan = Offset.Zero),
        )
        // (0,0)-(100,50) is wider than the canvas at 3x (-100..200, -100..50): centred to x 0, y +75.
        assertEquals(
            Offset(0f, 75f),
            EvidenceGeometry.panToReveal(OcrEvidenceRegion(0, 0, 100, 50), 100, 100, Orientation.NORMAL, square, zoom = 3f, pan = Offset.Zero),
        )

        // smallestSpanAt: the smallest containing region wins, ties keep the earlier ordinal.
        val revisionId = java.util.UUID.randomUUID()
        fun span(ordinal: Int, region: OcrEvidenceRegion?) = org.openlife.vault.ocr.OcrSpan(
            java.util.UUID.randomUUID(), revisionId, ordinal, "s$ordinal", null,
            org.openlife.vault.ocr.OcrCoordinateSystem.SOURCE_PIXELS, region,
        )
        val wide = span(0, OcrEvidenceRegion(0, 0, 100, 100))
        val small = span(1, OcrEvidenceRegion(40, 40, 60, 60))
        val ungrounded = span(2, null)
        val twinLater = span(4, OcrEvidenceRegion(5, 5, 15, 15))
        val twinEarlier = span(3, OcrEvidenceRegion(5, 5, 15, 15))
        val spans = listOf(wide, small, ungrounded, twinLater, twinEarlier)
        fun at(x: Float, y: Float, zoom: Float = 1f) =
            EvidenceGeometry.smallestSpanAt(Offset(x, y), spans, 100, 100, Orientation.NORMAL, square, zoom = zoom, pan = Offset.Zero)
        assertEquals(small, at(50f, 50f))
        assertEquals(wide, at(30f, 30f))
        assertEquals(twinEarlier, at(10f, 10f))
        assertEquals(null, at(150f, 150f))
        // At 3x canvas (10,10) is source (36.7,36.7): only the wide region contains it.
        assertEquals(wide, at(10f, 10f, zoom = 3f))
        // At 3x the small region covers canvas 20..80.
        assertEquals(small, at(25f, 25f, zoom = 3f))

        // nextDoubleTapZoom toggles between 1x and 3x around the midpoint 2x.
        assertEquals(3f, EvidenceGeometry.nextDoubleTapZoom(1f), 0f)
        assertEquals(3f, EvidenceGeometry.nextDoubleTapZoom(1.9f), 0f)
        assertEquals(1f, EvidenceGeometry.nextDoubleTapZoom(2f), 0f)
        assertEquals(1f, EvidenceGeometry.nextDoubleTapZoom(3f), 0f)
        assertEquals(1f, EvidenceGeometry.nextDoubleTapZoom(8f), 0f)
    }

    /**
     * P2-06-R8 (E-01): at 3x the image and its regions are measured at their
     * zoomed size from the same transform, not limited to the canvas.
     */
    @Test
    fun zoomedImageIsMeasuredAtItsZoomedSizeNotTheCanvas() {
        val canvas = Size(100f, 100f)
        val oneX = EvidenceGeometry.pixelBox(
            EvidenceGeometry.contentRect(100, 100, Orientation.NORMAL, canvas, zoom = 1f, pan = Offset.Zero),
        )
        val threeX = EvidenceGeometry.pixelBox(
            EvidenceGeometry.contentRect(100, 100, Orientation.NORMAL, canvas, zoom = 3f, pan = Offset.Zero),
        )
        assertEquals(IntRect(0, 0, 100, 100), oneX)
        assertEquals("3x is three times the fit and larger than the canvas", IntRect(-100, -100, 200, 200), threeX)
        val region = EvidenceGeometry.pixelBox(
            EvidenceGeometry.regionToCanvas(OcrEvidenceRegion(40, 40, 60, 60), 100, 100, Orientation.NORMAL, canvas, zoom = 3f, pan = Offset.Zero),
        )
        assertEquals(IntRect(20, 20, 80, 80), region)
        assertTrue("region box lies inside the image box", threeX.contains(region.topLeft) && region.right <= threeX.right)
        // Edges round independently, so adjacent boxes share an edge.
        assertEquals(IntRect(0, 1, 11, 20), EvidenceGeometry.pixelBox(Rect(0.4f, 0.6f, 10.5f, 20.4f)))
    }

    private fun assertClose(expected: Rect, actual: Rect, delta: Float = 0.05f, message: String = "") {
        assertEquals("$message left", expected.left, actual.left, delta)
        assertEquals("$message top", expected.top, actual.top, delta)
        assertEquals("$message right", expected.right, actual.right, delta)
        assertEquals("$message bottom", expected.bottom, actual.bottom, delta)
    }
}
