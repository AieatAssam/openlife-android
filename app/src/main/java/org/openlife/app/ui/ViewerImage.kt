package org.openlife.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.app.ui.theme.LocalOpenLifeBrandColors
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrEvidenceRegion

/**
 * The saved image, sized from its own displayed aspect ratio and capped at a
 * fraction of the window height, so the image fills its box and the evidence
 * overlay covers exactly the drawn pixels (P1-17-R4).
 */
@Composable
internal fun ViewerImage(source: Source, bytes: ByteArray?, selectedRegion: OcrEvidenceRegion?) {
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
            ViewerImageContent(source, bytes, selectedRegion, brand.highlighter)
        }
    }
}

@Composable
private fun BoxScope.ViewerImageContent(
    source: Source,
    bytes: ByteArray?,
    selectedRegion: OcrEvidenceRegion?,
    highlighter: Color,
) {
    val decoded = remember(bytes, source.orientation) {
        bytes?.let { SampledBitmapDecoder.decode(it, source.orientation ?: Orientation.NORMAL) }
    }
    DisposableEffect(decoded) {
        onDispose {
            if (decoded != null && !decoded.isRecycled) decoded.recycle()
        }
    }
    when {
        source.state == SourceState.CORRUPT -> Text(stringResource(R.string.viewer_saved_content_unreadable))

        bytes == null -> Text(stringResource(R.string.viewer_verifying))

        decoded == null -> Text(stringResource(R.string.viewer_saved_content_unreadable))

        // A generic description preserves privacy while still giving a
        // screen reader a useful stop in the source evidence flow.
        // The box already has the image's aspect ratio, so Fit fills it
        // exactly and the evidence overlay below covers the drawn image.
        else -> Image(
            decoded.asImageBitmap(),
            contentDescription = stringResource(R.string.viewer_saved_image_content_description),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
    val hasDimensions = source.width != null && source.height != null
    if (decoded != null && selectedRegion != null && hasDimensions) {
        val sourceWidth = source.width!!
        val sourceHeight = source.height!!
        Canvas(modifier = Modifier.matchParentSize()) {
            val region = selectedRegion
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
