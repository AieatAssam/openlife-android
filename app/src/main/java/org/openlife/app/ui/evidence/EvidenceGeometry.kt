package org.openlife.app.ui.evidence

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import org.openlife.vault.model.Orientation
import org.openlife.vault.ocr.OcrEvidenceRegion

/**
 * P2-06-R1 stub. The real mapping lands in the green commit; this signature
 * exists so the JVM tests compile and fail on the assertion.
 */
object EvidenceGeometry {
    fun regionToCanvas(
        region: OcrEvidenceRegion,
        sourceW: Int,
        sourceH: Int,
        orientation: Orientation,
        canvasSize: Size,
        contentScale: ContentScale = ContentScale.Fit,
        zoom: Float,
        pan: Offset,
    ): Rect = Rect.Zero
}
