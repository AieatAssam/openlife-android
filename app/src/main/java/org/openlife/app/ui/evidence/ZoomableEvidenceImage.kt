package org.openlife.app.ui.evidence

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.openlife.app.BuildConfig
import org.openlife.app.R
import org.openlife.vault.model.Orientation
import org.openlife.vault.ocr.OcrSpan
import kotlin.math.roundToInt

/**
 * Pinch-zoom and pan over the sampled preview. v0.2.0 does not decode a larger
 * bitmap when the user zooms (P2-06-R7); the same pixels are scaled.
 *
 * The image sits in a fixed box. While zoomed, pan is consumed here so the
 * viewer column does not scroll at the same time.
 */
@Composable
@Suppress("LongParameterList")
internal fun ZoomableEvidenceImage(
    bitmap: Bitmap,
    sourceWidth: Int,
    sourceHeight: Int,
    orientation: Orientation,
    spans: List<OcrSpan>,
    selectedSpanId: java.util.UUID?,
    onSelectSpan: (OcrSpan) -> Unit,
    highlighter: Color,
    modifier: Modifier = Modifier,
) {
    val zoom = remember(sourceWidth, sourceHeight, orientation) { mutableFloatStateOf(EvidenceGeometry.MIN_ZOOM) }
    val pan = remember(sourceWidth, sourceHeight, orientation) { mutableStateOf(Offset.Zero) }
    var canvas by remember { mutableStateOf(Size.Zero) }
    val bringIntoView = remember { BringIntoViewRequester() }
    Box(
        modifier.evidenceGestures(
            sourceWidth,
            sourceHeight,
            orientation,
            spans,
            canvas,
            zoom,
            pan,
            bringIntoView,
            onSelectSpan,
        ) { canvas = it },
    ) {
        if (canvas != Size.Zero && sourceWidth > 0 && sourceHeight > 0) {
            EvidenceCanvas(
                bitmap,
                sourceWidth,
                sourceHeight,
                orientation,
                canvas,
                zoom.floatValue,
                pan.value,
                spans,
                selectedSpanId,
                highlighter,
                onSelectSpan,
            )
        }
    }
    RevealSelected(
        selectedSpanId,
        spans,
        sourceWidth,
        sourceHeight,
        orientation,
        canvas,
        zoom,
        pan,
        bringIntoView,
    )
}

@Composable
@Suppress("LongParameterList")
private fun Modifier.evidenceGestures(
    sourceWidth: Int,
    sourceHeight: Int,
    orientation: Orientation,
    spans: List<OcrSpan>,
    canvas: Size,
    zoom: MutableFloatState,
    pan: MutableState<Offset>,
    bringIntoView: BringIntoViewRequester,
    onSelectSpan: (OcrSpan) -> Unit,
    onCanvas: (Size) -> Unit,
): Modifier {
    val transformable = rememberZoomTransform(
        sourceWidth,
        sourceHeight,
        orientation,
        canvas,
        zoom,
        pan,
    )
    val zoomedIn = zoom.floatValue > EvidenceGeometry.MIN_ZOOM
    return this
        .fillMaxSize()
        .clipToBounds()
        .testTag("viewer_evidence_canvas")
        .onSizeChanged { onCanvas(Size(it.width.toFloat(), it.height.toFloat())) }
        .bringIntoViewRequester(bringIntoView)
        .transformable(state = transformable, lockRotationOnZoomPan = true, canPan = { zoomedIn })
        .pointerInput(spans, canvas, zoom.floatValue, pan.value) {
            detectEvidenceTaps(
                onDoubleTap = {
                    zoom.floatValue = EvidenceGeometry.nextDoubleTapZoom(zoom.floatValue)
                    pan.value = Offset.Zero
                },
                onTap = { offset ->
                    if (canvas == Size.Zero) return@detectEvidenceTaps
                    EvidenceGeometry.smallestSpanAt(
                        offset,
                        spans,
                        sourceWidth,
                        sourceHeight,
                        orientation,
                        canvas,
                        ContentScale.Fit,
                        zoom.floatValue,
                        pan.value,
                    )?.let(onSelectSpan)
                },
            )
        }
}

/**
 * A single tap selects immediately. A second tap inside the double-tap
 * window zooms. [androidx.compose.foundation.gestures.detectTapGestures]
 * holds the single tap until that window closes, which drops the selection
 * when a test reads the tree on the next statement.
 */
private suspend fun PointerInputScope.detectEvidenceTaps(onTap: (Offset) -> Unit, onDoubleTap: () -> Unit) {
    var lastUpMillis = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val up = awaitRelease(down.id) ?: return@awaitEachGesture
        val doubleTap = up.uptimeMillis - lastUpMillis <= viewConfiguration.doubleTapTimeoutMillis
        lastUpMillis = up.uptimeMillis
        if (doubleTap) onDoubleTap() else onTap(up.position)
    }
}

private suspend fun AwaitPointerEventScope.awaitRelease(pointerId: PointerId): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.find { it.id == pointerId } ?: return null
        if (!change.pressed) return change
    }
}

@Suppress("LongParameterList")
@Composable
private fun rememberZoomTransform(
    sourceWidth: Int,
    sourceHeight: Int,
    orientation: Orientation,
    canvas: Size,
    zoom: MutableFloatState,
    pan: MutableState<Offset>,
): TransformableState = rememberTransformableState { _, zoomChange, panChange, _ ->
    val nextZoom = (zoom.floatValue * zoomChange).coerceIn(EvidenceGeometry.MIN_ZOOM, EvidenceGeometry.MAX_ZOOM)
    zoom.floatValue = nextZoom
    pan.value = if (nextZoom == EvidenceGeometry.MIN_ZOOM || canvas == Size.Zero) {
        Offset.Zero
    } else {
        EvidenceGeometry.clampPan(
            pan.value + panChange,
            sourceWidth,
            sourceHeight,
            orientation,
            canvas,
            ContentScale.Fit,
            nextZoom,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun RevealSelected(
    selectedSpanId: java.util.UUID?,
    spans: List<OcrSpan>,
    sourceWidth: Int,
    sourceHeight: Int,
    orientation: Orientation,
    canvas: Size,
    zoom: MutableFloatState,
    pan: MutableState<Offset>,
    bringIntoView: BringIntoViewRequester,
) {
    LaunchedEffect(selectedSpanId, canvas) {
        val region = spans.find { it.id == selectedSpanId }?.evidenceRegion ?: return@LaunchedEffect
        if (canvas == Size.Zero) return@LaunchedEffect
        pan.value = EvidenceGeometry.panToReveal(
            region,
            sourceWidth,
            sourceHeight,
            orientation,
            canvas,
            ContentScale.Fit,
            zoom.floatValue,
            pan.value,
        )
        bringIntoView.bringIntoView()
    }
}

@Composable
@Suppress("LongParameterList")
private fun EvidenceCanvas(
    bitmap: Bitmap,
    sourceWidth: Int,
    sourceHeight: Int,
    orientation: Orientation,
    canvas: Size,
    zoom: Float,
    pan: Offset,
    spans: List<OcrSpan>,
    selectedSpanId: java.util.UUID?,
    highlighter: Color,
    onSelectSpan: (OcrSpan) -> Unit,
) {
    val imageRect = EvidenceGeometry.contentRect(
        sourceWidth,
        sourceHeight,
        orientation,
        canvas,
        ContentScale.Fit,
        zoom,
        pan,
    )
    PlacedImage(bitmap, imageRect)
    val grounded = spans.filter { it.evidenceRegion != null }
        .sortedByDescending { span ->
            val region = span.evidenceRegion ?: return@sortedByDescending 0
            (region.right - region.left) * (region.bottom - region.top)
        }
    var selectedRect: Rect? = null
    grounded.forEach { span ->
        val region = span.evidenceRegion ?: return@forEach
        val rect = EvidenceGeometry.regionToCanvas(
            region,
            sourceWidth,
            sourceHeight,
            orientation,
            canvas,
            ContentScale.Fit,
            zoom,
            pan,
        )
        if (span.id == selectedSpanId) selectedRect = rect
        RegionNode(
            span = span,
            rect = rect,
            selected = span.id == selectedSpanId,
            highlighter = highlighter,
            onSelect = { onSelectSpan(span) },
        )
    }
    if (BuildConfig.DEBUG) {
        val message = boundsMessage(imageRect, selectedRect)
        SideEffect {
            android.util.Log.d("EvidenceBounds", message)
        }
    }
}

@Composable
private fun PlacedImage(bitmap: Bitmap, rect: Rect) {
    val density = LocalDensity.current
    if (rect.width <= 0f || rect.height <= 0f) return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.viewer_saved_image_content_description),
        modifier = Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .testTag("viewer_evidence_image"),
        contentScale = ContentScale.FillBounds,
    )
}

@Composable
private fun RegionNode(span: OcrSpan, rect: Rect, selected: Boolean, highlighter: Color, onSelect: () -> Unit) {
    val density = LocalDensity.current
    if (rect.width <= 0f || rect.height <= 0f) return
    val preview = evidenceSpanPreview(span.text)
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .then(if (selected) Modifier.testTag("viewer_evidence_overlay") else Modifier)
            .semantics {
                role = Role.Button
                contentDescription = preview
                onClick(label = preview) {
                    onSelect()
                    true
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (selected) drawRect(highlighter.copy(alpha = HIGHLIGHT_ALPHA))
            drawRect(color = highlighter, style = Stroke(width = STROKE_WIDTH.toPx()))
        }
    }
}

private fun boundsMessage(image: Rect, overlay: Rect?): String {
    fun format(rect: Rect) = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    return "image=${format(image)} overlay=${overlay?.let(::format) ?: "none"}"
}

private const val HIGHLIGHT_ALPHA = 0.30f
private val STROKE_WIDTH = 3.dp
