package org.openlife.app.ui.brand

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.app.ui.theme.LocalOpenLifeBrandColors
import org.openlife.app.ui.theme.OpenLifeTheme

/** A labeled state stamp; there is deliberately no unreviewed variant. */
@Composable
fun StampBadge(state: StampState, modifier: Modifier = Modifier, motionScale: Float? = null) {
    val brand = LocalOpenLifeBrandColors.current
    val label = stringResource(
        when (state) {
            StampState.Confirmed -> R.string.stamp_confirmed
            StampState.Verified -> R.string.stamp_verified
            StampState.Stale -> R.string.stamp_stale
            StampState.Saved -> R.string.stamp_saved
        },
    )
    val scale = remember { Animatable(1f) }
    // Compose carries MotionDurationScale through the animation coroutine;
    // Android's system scaleFactor == 0f makes this hero moment snap.
    val duration = if ((motionScale ?: DEFAULT_MOTION_SCALE) == ZERO_MOTION_SCALE) {
        0
    } else {
        HERO_MOTION_DURATION_MILLIS
    }
    LaunchedEffect(state, duration) {
        if (duration == 0) {
            scale.snapTo(1f)
        } else {
            scale.snapTo(1f)
            scale.animateTo(STAMP_PRESS_SCALE, tween(durationMillis = duration / 2))
            scale.animateTo(STAMP_REST_SCALE, tween(durationMillis = duration / 2))
        }
    }
    val stampColor = when (state) {
        StampState.Confirmed, StampState.Verified -> MaterialTheme.colorScheme.tertiary
        StampState.Stale -> brand.attention
        StampState.Saved -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .rotate(STAMP_ROTATION_DEGREES)
            .border(BorderStroke(1.5.dp, stampColor), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = label },
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = stampColor,
                textDecoration = if (state == StampState.Stale) TextDecoration.LineThrough else null,
            ),
        )
    }
}

@Composable
fun FoldedCornerCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val brand = LocalOpenLifeBrandColors.current
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawFold(brand.fold, brand.rule),
        ) {
            Column(content = content)
        }
    }
}

private fun Modifier.drawFold(foldColor: Color, ruleColor: Color): Modifier = drawWithCache {
    val foldSize = 12.dp.toPx()
    onDrawWithContent {
        drawContent()
        val fold = Path().apply {
            moveTo(size.width - foldSize, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, foldSize)
            close()
        }
        drawPath(fold, foldColor)
        drawLine(
            color = ruleColor,
            start = androidx.compose.ui.geometry.Offset(size.width - foldSize, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width, foldSize),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private const val DEFAULT_MOTION_SCALE = 1f
private const val ZERO_MOTION_SCALE = 0f
private const val HERO_MOTION_DURATION_MILLIS = 160
private const val STAMP_PRESS_SCALE = 0.96f
private const val STAMP_REST_SCALE = 1f
private const val STAMP_ROTATION_DEGREES = -3f

@Composable
fun PerforationDivider(
    modifier: Modifier = Modifier,
    description: String = stringResource(R.string.perforation_content_description),
) {
    val brand = LocalOpenLifeBrandColors.current
    val density = LocalDensity.current
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .semantics { contentDescription = description },
    ) {
        drawLine(
            color = brand.rule,
            start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
            strokeWidth = with(density) { 1.dp.toPx() },
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
        )
    }
}

@Preview(name = "Brand components", showBackground = true)
@Composable
private fun BrandComponentsPreview() {
    OpenLifeTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            StampBadge(StampState.Confirmed)
            StampBadge(StampState.Saved, modifier = Modifier.padding(top = 12.dp))
            FoldedCornerCard(modifier = Modifier.padding(top = 16.dp)) {
                Text(
                    stringResource(R.string.list_title),
                    modifier = Modifier.padding(16.dp),
                )
            }
            PerforationDivider(modifier = Modifier.padding(top = 8.dp))
        }
    }
}
