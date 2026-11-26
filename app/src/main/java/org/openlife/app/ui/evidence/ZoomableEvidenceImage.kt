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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import org.openlife.app.BuildConfig
import org.openlife.app.R
import org.openlife.vault.model.Orientation
import org.openlife.vault.ocr.OcrSpan
import java.util.UUID
import kotlin.math.roundToInt

/** Colours for the evidence overlay: the highlighter for evidence, muted ink for rejected spans. */
internal data class EvidenceColors(val highlighter: Color, val rejected: Color)

/** What the zoomable image shows: the source geometry and its spans (P2-06-R1/R3/R13). */
internal data class EvidenceContent(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val orientation: Orientation,
    val spans: List<OcrSpan>,
    val rejectedSpanIds: Set<UUID>,
) {
    /** Spans whose regions are evidence: grounded and not rejected. */
    val selectable: List<OcrSpan> = EvidenceSelection.selectableSpans(spans) { it.id in rejectedSpanIds }
}

/**
 * Pinch-zoom and pan over the sampled preview. v0.2.0 does not decode a larger
 * bitmap when the user zooms (P2-06-R7); the same pixels are scaled.
 *
 * The image and every region are measured at their zoomed size and placed by
 * the one transform in [EvidenceGeometry], so what is drawn and what a tap
 * hits agree at any zoom (P2-06-R8). While zoomed, pan is consumed here so the
 * viewer column does not scroll at the same time.
 */
@Composable
internal fun ZoomableEvidenceImage(
    bitmap: Bitmap,
    content: EvidenceContent,
    selectedSpanId: UUID?,
    onSelectSpan: (OcrSpan) -> Unit,
    colors: EvidenceColors,
    modifier: Modifier = Modifier,
    viewport: EvidenceViewport? = null,
) {
    val local = remember(content.sourceWidth, content.sourceHeight, content.orientation) { EvidenceViewport() }
    val state = viewport ?: local
    var canvas by remember { mutableStateOf(Size.Zero) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val view = EvidenceView(content, canvas, state.zoom, state.pan)
    Box(modifier.evidenceGestures(view, bringIntoView, onSelectSpan) { canvas = it }) {
        if (canvas != Size.Zero && content.sourceWidth > 0 && content.sourceHeight > 0) {
            EvidenceCanvas(bitmap, view, selectedSpanId, colors, onSelectSpan)
        }
    }
    RevealSelected(selectedSpanId, view, bringIntoView)
}

/**
 * P1-20-R9: the zoom and pan, hoisted so the viewer's ViewModel can keep them
 * across recreation as two numbers each (no content). Without one, the image
 * keeps its own.
 */
internal class EvidenceViewport(initialZoom: Float = EvidenceGeometry.MIN_ZOOM, initialPan: Offset = Offset.Zero) {
    val zoom: MutableFloatState = mutableFloatStateOf(initialZoom)
    val pan: MutableState<Offset> = mutableStateOf(initialPan)
}

/** The image's geometry with its live zoom and pan; the one transform drawing and hit-testing share. */
private class EvidenceView(
    val content: EvidenceContent,
    val canvas: Size,
    val zoom: MutableFloatState,
    val pan: MutableState<Offset>,
) {
    fun imageBox(): IntRect = EvidenceGeometry.pixelBox(
        EvidenceGeometry.contentRect(
            content.sourceWidth,
            content.sourceHeight,
            content.orientation,
            canvas,
            ContentScale.Fit,
            zoom.floatValue,
            pan.value,
        ),
    )

    fun regionBox(span: OcrSpan): IntRect? = span.evidenceRegion?.let { region ->
        EvidenceGeometry.pixelBox(
            EvidenceGeometry.regionToCanvas(
                region,
                content.sourceWidth,
                content.sourceHeight,
                content.orientation,
                canvas,
                ContentScale.Fit,
                zoom.floatValue,
                pan.value,
            ),
        )
    }

    /** Sets zoom and pan as a pinch does: zoom within its limits, pan kept over the image. */
    fun zoomTo(next: Float, nextPan: Offset = pan.value) {
        val nextZoom = next.coerceIn(EvidenceGeometry.MIN_ZOOM, EvidenceGeometry.MAX_ZOOM)
        zoom.floatValue = nextZoom
        pan.value = if (nextZoom == EvidenceGeometry.MIN_ZOOM || canvas == Size.Zero) {
            Offset.Zero
        } else {
            EvidenceGeometry.clampPan(
                nextPan,
                content.sourceWidth,
                content.sourceHeight,
                content.orientation,
                canvas,
                ContentScale.Fit,
                nextZoom,
            )
        }
    }

    /** An accessibility action that zooms to [target], the equivalent of a pinch (P1-10). */
    fun zoomAction(label: String, target: Float) = CustomAccessibilityAction(label) {
        zoomTo(target)
        true
    }

    /** The selectable span under [point], or null; rejected and ungrounded spans are never hit (R13). */
    fun spanAt(point: Offset): OcrSpan? = if (canvas == Size.Zero) {
        null
    } else {
        EvidenceGeometry.smallestSpanAt(
            point,
            content.selectable,
            content.sourceWidth,
            content.sourceHeight,
            content.orientation,
            canvas,
            ContentScale.Fit,
            zoom.floatValue,
            pan.value,
        )
    }
}

@Composable
private fun Modifier.evidenceGestures(
    view: EvidenceView,
    bringIntoView: BringIntoViewRequester,
    onSelectSpan: (OcrSpan) -> Unit,
    onCanvas: (Size) -> Unit,
): Modifier {
    val transformable = rememberZoomTransform(view)
    // P2-06-R9: the detector is started once. Zoom, pan, spans and callbacks
    // are read when a tap ends, so a pan never restarts it or loses a
    // pending double tap.
    val currentView by rememberUpdatedState(view)
    val currentOnSelectSpan by rememberUpdatedState(onSelectSpan)
    return this
        .fillMaxSize()
        .clipToBounds()
        .testTag("viewer_evidence_canvas")
        .onSizeChanged { onCanvas(Size(it.width.toFloat(), it.height.toFloat())) }
        .bringIntoViewRequester(bringIntoView)
        .transformable(
            state = transformable,
            lockRotationOnZoomPan = true,
            canPan = { view.zoom.floatValue > EvidenceGeometry.MIN_ZOOM },
        )
        .pointerInput(Unit) {
            val doubleTaps = DoubleTapClock(viewConfiguration.doubleTapTimeoutMillis, DOUBLE_TAP_SLOP.toPx())
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tap = TapGesture(down.id.value, down.position, viewConfiguration.touchSlop)
                // S-06: the down's own final pass; a parent that consumed it (a
                // touch that stops a fling) means this is not a tap.
                val downStep = tap.onFinal(awaitPointerEvent(PointerEventPass.Final).samples())
                val release = if (downStep == TapGesture.Step.Abandoned) null else awaitTapRelease(tap)
                if (release == null) {
                    doubleTaps.reset()
                    return@awaitEachGesture
                }
                when (doubleTaps.onTap(down.uptimeMillis, release.uptimeMillis, release.position)) {
                    DoubleTapClock.Kind.DOUBLE -> {
                        val zoom = currentView.zoom
                        zoom.floatValue = EvidenceGeometry.nextDoubleTapZoom(zoom.floatValue)
                        currentView.pan.value = Offset.Zero
                    }

                    DoubleTapClock.Kind.SINGLE -> currentView.spanAt(release.position)?.let(currentOnSelectSpan)
                }
            }
        }
}

private class TapRelease(val position: Offset, val uptimeMillis: Long)

/**
 * As `waitForUpOrCancellation()`, checking each event in the main pass and
 * again in the final pass, but every decision is [TapGesture]'s: it also
 * abandons the tap past touch slop or when a second pointer goes down, which
 * `waitForUpOrCancellation()` does not.
 */
private suspend fun AwaitPointerEventScope.awaitTapRelease(tap: TapGesture): TapRelease? {
    while (true) {
        val main = awaitPointerEvent(PointerEventPass.Main)
        when (val step = tap.onMain(main.samples())) {
            TapGesture.Step.Abandoned -> return null
            is TapGesture.Step.Released -> return TapRelease(step.position, main.changes.maxOf { it.uptimeMillis })
            TapGesture.Step.Pending -> Unit
        }
        if (tap.onFinal(awaitPointerEvent(PointerEventPass.Final).samples()) == TapGesture.Step.Abandoned) return null
    }
}

private fun PointerEvent.samples(): List<PointerSample> =
    changes.map { PointerSample(it.id.value, it.position, it.pressed, it.isConsumed) }

@Composable
private fun rememberZoomTransform(view: EvidenceView): TransformableState =
    rememberTransformableState { _, zoomChange, panChange, _ ->
        view.zoomTo(view.zoom.floatValue * zoomChange, view.pan.value + panChange)
    }

@Composable
private fun RevealSelected(selectedSpanId: UUID?, view: EvidenceView, bringIntoView: BringIntoViewRequester) {
    LaunchedEffect(selectedSpanId, view.canvas) {
        val content = view.content
        val region = content.selectable.find { it.id == selectedSpanId }?.evidenceRegion ?: return@LaunchedEffect
        if (view.canvas == Size.Zero) return@LaunchedEffect
        view.pan.value = EvidenceGeometry.panToReveal(
            region,
            content.sourceWidth,
            content.sourceHeight,
            content.orientation,
            view.canvas,
            ContentScale.Fit,
            view.zoom.floatValue,
            view.pan.value,
        )
        bringIntoView.bringIntoView()
    }
}

@Composable
private fun EvidenceCanvas(
    bitmap: Bitmap,
    view: EvidenceView,
    selectedSpanId: UUID?,
    colors: EvidenceColors,
    onSelectSpan: (OcrSpan) -> Unit,
) {
    val imageBox = view.imageBox()
    PlacedImage(bitmap, imageBox, view)
    // Larger regions first, so a smaller region inside one is drawn, and reached, on top.
    val grounded = view.content.spans.mapNotNull { span -> view.regionBox(span)?.let { span to it } }
        .sortedByDescending { (_, box) -> box.width.toLong() * box.height }
    var selectedBox: IntRect? = null
    grounded.forEach { (span, box) ->
        val rejected = span.id in view.content.rejectedSpanIds
        val selected = span.id == selectedSpanId && !rejected
        if (selected) selectedBox = box
        RegionNode(span, box, selected, rejected, colors) { onSelectSpan(span) }
    }
    if (BuildConfig.DEBUG) {
        val message = boundsMessage(imageBox, selectedBox)
        SideEffect {
            android.util.Log.d("EvidenceBounds", message)
        }
    }
}

/**
 * Measures the node at exactly [box]'s size, even when that is larger than the
 * canvas (zoomed in), and places it at [box]'s origin in canvas pixels. The
 * node reports the canvas size to its parent so Compose never re-centres an
 * oversized child (E-01). Placement is absolute, as the geometry is.
 */
private fun Modifier.placeAt(box: IntRect): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints.fixed(box.width, box.height))
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
    layout(width, height) { placeable.place(box.left, box.top) }
}

@Composable
private fun PlacedImage(bitmap: Bitmap, box: IntRect, view: EvidenceView) {
    if (box.width <= 0 || box.height <= 0) return
    // P1-10: pinch and double tap have TalkBack and switch-access equivalents.
    val zoom = view.zoom.floatValue
    val zoomState = stringResource(R.string.viewer_zoom_state, (zoom * PERCENT).roundToInt())
    val zoomIn = stringResource(R.string.viewer_zoom_in)
    val zoomOut = stringResource(R.string.viewer_zoom_out)
    val zoomReset = stringResource(R.string.viewer_zoom_reset)
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.viewer_saved_image_content_description),
        modifier = Modifier
            .placeAt(box)
            .testTag("viewer_evidence_image")
            .semantics {
                stateDescription = zoomState
                customActions = buildList {
                    if (zoom < EvidenceGeometry.MAX_ZOOM) {
                        add(view.zoomAction(zoomIn, EvidenceGeometry.zoomInStep(zoom)))
                    }
                    if (zoom > EvidenceGeometry.MIN_ZOOM) {
                        add(view.zoomAction(zoomOut, EvidenceGeometry.zoomOutStep(zoom)))
                        add(view.zoomAction(zoomReset, EvidenceGeometry.MIN_ZOOM))
                    }
                }
            },
        contentScale = ContentScale.FillBounds,
    )
}

@Composable
private fun RegionNode(
    span: OcrSpan,
    box: IntRect,
    selected: Boolean,
    rejected: Boolean,
    colors: EvidenceColors,
    onSelect: () -> Unit,
) {
    if (box.width <= 0 || box.height <= 0) return
    val preview = evidenceSpanPreview(span.text)
    val rejectedDescription = stringResource(R.string.viewer_region_rejected_description, preview)
    Box(
        Modifier
            .placeAt(box)
            .then(if (selected) Modifier.testTag("viewer_evidence_overlay") else Modifier)
            .semantics {
                if (rejected) {
                    // Drawn so the user sees what was rejected, but not evidence: no click (P2-06-R13).
                    contentDescription = rejectedDescription
                } else {
                    role = Role.Button
                    contentDescription = preview
                    // The highlight fill is not the only cue: TalkBack says "selected" (P1-10).
                    this.selected = selected
                    onClick(label = preview) {
                        onSelect()
                        true
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = STROKE_WIDTH.toPx()
            if (rejected) {
                val dash = floatArrayOf(stroke * 2f, stroke * 2f)
                val dashed = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(dash))
                drawRect(color = colors.rejected, style = dashed)
            } else {
                if (selected) drawRect(colors.highlighter.copy(alpha = HIGHLIGHT_ALPHA))
                drawRect(color = colors.highlighter, style = Stroke(width = stroke))
            }
        }
    }
}

private fun boundsMessage(image: IntRect, overlay: IntRect?): String {
    fun format(box: IntRect) = "${box.left},${box.top},${box.right},${box.bottom}"
    return "image=${format(image)} overlay=${overlay?.let(::format) ?: "none"}"
}

private const val HIGHLIGHT_ALPHA = 0.30f
private const val PERCENT = 100

// As android.view.ViewConfiguration's double-tap slop.
private val DOUBLE_TAP_SLOP = 100.dp
private val STROKE_WIDTH = 3.dp
