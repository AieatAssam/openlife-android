package org.openlife.app.ui.evidence

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.OrientationTransform
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrSpan

/**
 * Maps a source-pixel evidence region onto the letterboxed, orientation-corrected
 * image inside a canvas, then applies zoom and pan (P2-06-R1, F-27).
 *
 * Zoom is a scale around the canvas centre after [ContentScale] fit. Pan is a
 * translation in canvas pixels and is not clamped here; [clampPan] does that.
 */
object EvidenceGeometry {
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 8f
    const val DOUBLE_TAP_ZOOM = 3f

    @Suppress("LongParameterList")
    fun regionToCanvas(
        region: OcrEvidenceRegion,
        sourceW: Int,
        sourceH: Int,
        orientation: Orientation,
        canvasSize: Size,
        contentScale: ContentScale = ContentScale.Fit,
        zoom: Float,
        pan: Offset,
    ): Rect {
        val place = placement(sourceW, sourceH, orientation, canvasSize, contentScale, zoom, pan)
        val display = displayBounds(region, sourceW, sourceH, orientation)
        return Rect(
            left = place.origin.x + (display.left / place.displaySize.width) * place.zoomed.width,
            top = place.origin.y + (display.top / place.displaySize.height) * place.zoomed.height,
            right = place.origin.x + (display.right / place.displaySize.width) * place.zoomed.width,
            bottom = place.origin.y + (display.bottom / place.displaySize.height) * place.zoomed.height,
        )
    }

    /** The fitted image rectangle after the same zoom and pan as [regionToCanvas]. */
    @Suppress("LongParameterList")
    fun contentRect(
        sourceW: Int,
        sourceH: Int,
        orientation: Orientation,
        canvasSize: Size,
        contentScale: ContentScale = ContentScale.Fit,
        zoom: Float,
        pan: Offset,
    ): Rect {
        val place = placement(sourceW, sourceH, orientation, canvasSize, contentScale, zoom, pan)
        return Rect(
            place.origin.x,
            place.origin.y,
            place.origin.x + place.zoomed.width,
            place.origin.y + place.zoomed.height,
        )
    }

    /** Keeps the zoomed image covering the canvas; at 1x a letterboxed image cannot be panned. */
    @Suppress("LongParameterList")
    fun clampPan(
        pan: Offset,
        sourceW: Int,
        sourceH: Int,
        orientation: Orientation,
        canvasSize: Size,
        contentScale: ContentScale = ContentScale.Fit,
        zoom: Float,
    ): Offset {
        val placed = placement(sourceW, sourceH, orientation, canvasSize, contentScale, zoom, Offset.Zero)
        val maxX = ((placed.zoomed.width - canvasSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((placed.zoomed.height - canvasSize.height) / 2f).coerceAtLeast(0f)
        return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
    }

    /** Shifts [pan] so [region] is fully on the canvas when that fits, otherwise centred, then clamped. */
    @Suppress("LongParameterList")
    fun panToReveal(
        region: OcrEvidenceRegion,
        sourceW: Int,
        sourceH: Int,
        orientation: Orientation,
        canvasSize: Size,
        contentScale: ContentScale = ContentScale.Fit,
        zoom: Float,
        pan: Offset,
    ): Offset {
        val rect = regionToCanvas(
            region,
            sourceW,
            sourceH,
            orientation,
            canvasSize,
            contentScale,
            zoom,
            pan,
        )
        val inside = rect.left >= 0f &&
            rect.top >= 0f &&
            rect.right <= canvasSize.width &&
            rect.bottom <= canvasSize.height
        if (inside) {
            return clampPan(pan, sourceW, sourceH, orientation, canvasSize, contentScale, zoom)
        }
        val delta = Offset(
            canvasSize.width / 2f - rect.center.x,
            canvasSize.height / 2f - rect.center.y,
        )
        return clampPan(pan + delta, sourceW, sourceH, orientation, canvasSize, contentScale, zoom)
    }

    /** Smallest region that contains [point]. Equal areas keep the earlier span. */
    @Suppress("LongParameterList")
    fun smallestSpanAt(
        point: Offset,
        spans: List<OcrSpan>,
        sourceW: Int,
        sourceH: Int,
        orientation: Orientation,
        canvasSize: Size,
        contentScale: ContentScale = ContentScale.Fit,
        zoom: Float,
        pan: Offset,
    ): OcrSpan? = spans.mapNotNull { span ->
        val region = span.evidenceRegion ?: return@mapNotNull null
        val rect = regionToCanvas(
            region,
            sourceW,
            sourceH,
            orientation,
            canvasSize,
            contentScale,
            zoom,
            pan,
        )
        if (point.x in rect.left..rect.right && point.y in rect.top..rect.bottom) {
            span to rect.width * rect.height
        } else {
            null
        }
    }.minWithOrNull(compareBy<Pair<OcrSpan, Float>>({ it.second }, { it.first.ordinal }))?.first

    fun nextDoubleTapZoom(current: Float): Float =
        if (current < (MIN_ZOOM + DOUBLE_TAP_ZOOM) / 2f) DOUBLE_TAP_ZOOM else MIN_ZOOM

    @Suppress("LongParameterList")
    private fun placement(
        sourceW: Int,
        sourceH: Int,
        orientation: Orientation,
        canvasSize: Size,
        contentScale: ContentScale,
        zoom: Float,
        pan: Offset,
    ): Placement {
        require(sourceW > 0 && sourceH > 0) { "Source dimensions must be positive" }
        val displayW = OrientationTransform.displayWidth(sourceW, sourceH, orientation).toFloat()
        val displayH = OrientationTransform.displayHeight(sourceW, sourceH, orientation).toFloat()
        val factor = contentScale.computeScaleFactor(Size(displayW, displayH), canvasSize)
        val fitted = Size(displayW * factor.scaleX, displayH * factor.scaleY)
        val zoomed = Size(fitted.width * zoom, fitted.height * zoom)
        val origin = Offset(
            (canvasSize.width - zoomed.width) / 2f + pan.x,
            (canvasSize.height - zoomed.height) / 2f + pan.y,
        )
        return Placement(Size(displayW, displayH), origin, zoomed)
    }

    private fun displayBounds(region: OcrEvidenceRegion, sourceW: Int, sourceH: Int, orientation: Orientation): Rect {
        val corners = listOf(
            displayPoint(region.left, region.top, sourceW, sourceH, orientation),
            displayPoint(region.right, region.top, sourceW, sourceH, orientation),
            displayPoint(region.left, region.bottom, sourceW, sourceH, orientation),
            displayPoint(region.right, region.bottom, sourceW, sourceH, orientation),
        )
        return Rect(
            left = corners.minOf { it.x },
            top = corners.minOf { it.y },
            right = corners.maxOf { it.x },
            bottom = corners.maxOf { it.y },
        )
    }

    /** Inverse of [OrientationTransform.sourcePointForDisplay] for one boundary point. */
    private fun displayPoint(
        sourceX: Int,
        sourceY: Int,
        sourceW: Int,
        sourceH: Int,
        orientation: Orientation,
    ): Offset {
        val (x, y) = when (orientation) {
            Orientation.NORMAL -> sourceX to sourceY
            Orientation.ROTATE_90 -> (sourceH - sourceY) to sourceX
            Orientation.ROTATE_180 -> (sourceW - sourceX) to (sourceH - sourceY)
            Orientation.ROTATE_270 -> sourceY to (sourceW - sourceX)
            Orientation.FLIP_HORIZONTAL -> (sourceW - sourceX) to sourceY
            Orientation.FLIP_VERTICAL -> sourceX to (sourceH - sourceY)
            Orientation.TRANSPOSE -> sourceY to sourceX
            Orientation.TRANSVERSE -> (sourceH - sourceY) to (sourceW - sourceX)
        }
        return Offset(x.toFloat(), y.toFloat())
    }

    private data class Placement(val displaySize: Size, val origin: Offset, val zoomed: Size)
}

/** One line, capped, for a region's accessibility description. Not a log line. */
internal fun evidenceSpanPreview(text: String): String {
    val collapsed = text.replace(WHITESPACE, " ").trim()
    return if (collapsed.length <= PREVIEW_LIMIT) collapsed else collapsed.take(PREVIEW_LIMIT - 1) + "…"
}

private val WHITESPACE = Regex("\\s+")
private const val PREVIEW_LIMIT = 40
