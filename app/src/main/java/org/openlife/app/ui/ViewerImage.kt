package org.openlife.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openlife.app.R
import org.openlife.app.ui.theme.LocalOpenLifeBrandColors
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.repository.ReadyReadResult

/**
 * Loads and decodes the saved image off the main thread. [attempt] restarts
 * the load for "Try again". The authenticated plaintext is zeroed once it has
 * been decoded; the decoded bitmap is recycled when this state is replaced or
 * leaves composition, because no other holder can see it.
 */
@Composable
internal fun rememberViewerContent(
    source: Source,
    loadContent: suspend () -> ReadyReadResult,
    attempt: Int,
): ViewerContentState {
    val content by produceState<ViewerContentState>(
        ViewerContentState.Loading,
        source.id,
        source.state,
        attempt,
    ) {
        value = if (source.state == SourceState.CORRUPT) {
            ViewerContentState.Unreadable
        } else {
            when (val result = loadContent()) {
                is ReadyReadResult.Loaded -> decodeForViewer(result.bytes, source.orientation ?: Orientation.NORMAL)

                ReadyReadResult.Transient -> ViewerContentState.Transient

                ReadyReadResult.Corrupt,
                ReadyReadResult.Missing,
                ReadyReadResult.Unavailable,
                -> ViewerContentState.Unreadable
            }
        }
    }
    // Capture the value: reading the delegated property inside onDispose would
    // see the state that replaced it and recycle the bitmap being shown.
    val current = content
    DisposableEffect(current) {
        onDispose {
            (current as? ViewerContentState.Shown)?.bitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }
    return current
}

private suspend fun decodeForViewer(bytes: ByteArray, orientation: Orientation): ViewerContentState =
    withContext(Dispatchers.Default) {
        try {
            SampledBitmapDecoder.decode(bytes, orientation)
        } finally {
            bytes.fill(0)
        }
    }?.let(ViewerContentState::Shown) ?: ViewerContentState.Unreadable

/**
 * The saved image, sized from its own displayed aspect ratio and capped at a
 * fraction of the window height, so the image fills its box and the evidence
 * overlay covers exactly the drawn pixels (P1-17-R4). Failures are explained
 * in place with the action that fits them (design §11: explain unreadable
 * content and offer deletion).
 */
@Composable
internal fun ViewerImage(
    source: Source,
    content: ViewerContentState,
    selectedRegion: OcrEvidenceRegion?,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    when (content) {
        ViewerContentState.Unreadable -> ViewerLoadProblem(
            message = stringResource(R.string.viewer_saved_content_unreadable_explained),
        ) {
            OutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("viewer_unreadable_delete"),
            ) { Text(stringResource(R.string.delete_action)) }
        }

        ViewerContentState.Transient -> ViewerLoadProblem(
            message = stringResource(R.string.viewer_read_transient),
        ) {
            Button(onClick = onRetry) { Text(stringResource(R.string.viewer_try_again)) }
        }

        ViewerContentState.Loading, is ViewerContentState.Shown -> ViewerImageArea(source, content, selectedRegion)
    }
}

@Composable
private fun ViewerLoadProblem(message: String, action: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center)
        Box(modifier = Modifier.padding(top = 16.dp)) { action() }
    }
}

@Composable
private fun ViewerImageArea(source: Source, content: ViewerContentState, selectedRegion: OcrEvidenceRegion?) {
    val brand = LocalOpenLifeBrandColors.current
    val maxImageHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height * MAX_IMAGE_HEIGHT_FRACTION).toDp()
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .heightIn(max = maxImageHeight)
                .aspectRatio(displayAspectRatio(source))
                .testTag("viewer_image_area"),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = (content as? ViewerContentState.Shown)?.bitmap
            if (bitmap == null) {
                Text(stringResource(R.string.viewer_verifying))
            } else {
                // A generic description preserves privacy while still giving a
                // screen reader a useful stop in the source evidence flow. The
                // box already has the image's aspect ratio, so Fit fills it
                // exactly and the evidence overlay below covers the drawn image.
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.viewer_saved_image_content_description),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                if (selectedRegion != null) EvidenceOverlay(source, selectedRegion, brand.highlighter)
            }
        }
    }
}

@Composable
private fun BoxScope.EvidenceOverlay(source: Source, region: OcrEvidenceRegion, highlighter: Color) {
    val sourceWidth = source.width ?: return
    val sourceHeight = source.height ?: return
    Canvas(modifier = Modifier.matchParentSize()) {
        val left = region.left.toFloat() / sourceWidth * size.width
        val top = region.top.toFloat() / sourceHeight * size.height
        val right = region.right.toFloat() / sourceWidth * size.width
        val bottom = region.bottom.toFloat() / sourceHeight * size.height
        drawRect(
            color = highlighter.copy(alpha = 0.30f),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
        )
        drawRect(
            color = highlighter,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

/** Width over height as displayed; quarter-turn transforms swap the axes. */
private fun displayAspectRatio(source: Source): Float {
    val width = source.width ?: return FALLBACK_ASPECT_RATIO
    val height = source.height ?: return FALLBACK_ASPECT_RATIO
    if (width <= 0 || height <= 0) return FALLBACK_ASPECT_RATIO
    val swapsAxes = when (source.orientation) {
        Orientation.ROTATE_90, Orientation.ROTATE_270, Orientation.TRANSPOSE, Orientation.TRANSVERSE -> true
        else -> false
    }
    return if (swapsAxes) height.toFloat() / width else width.toFloat() / height
}

private const val MAX_IMAGE_HEIGHT_FRACTION = 0.6f
private const val FALLBACK_ASPECT_RATIO = 4f / 3f
