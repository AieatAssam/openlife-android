package org.openlife.app.ui.brand

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * P1-19-R9 (A-04): every button in OpenLife goes through these wrappers so it
 * gets the brief's 8 dp corner (VISUAL_IDENTITY "Shape"), not Material's
 * full pill. ButtonShapeSourceTest keeps bare Material buttons out of the app.
 */
val OpenLifeButtonShape: Shape = RoundedCornerShape(8.dp)

@Composable
fun OpenLifeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = OpenLifeButtonShape,
        colors = colors,
        content = content,
    )
}

@Composable
fun OpenLifeOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = OpenLifeButtonShape,
        colors = colors,
        border = border,
        content = content,
    )
}

@Composable
fun OpenLifeTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = OpenLifeButtonShape,
        colors = colors,
        content = content,
    )
}

@Composable
fun OpenLifeFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = OpenLifeButtonShape,
        colors = colors,
        content = content,
    )
}

/**
 * Review U-02: the screen's one primary action (VISUAL_IDENTITY §7) is an
 * extended FAB, a button with a filled container, so it takes the same 8 dp
 * corner instead of Material's 16 dp. Icon buttons have no container (only a
 * circular state layer), so they are not wrapped.
 */
@Composable
fun OpenLifeExtendedFloatingActionButton(
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = OpenLifeButtonShape,
        containerColor = containerColor,
        contentColor = contentColor,
        content = content,
    )
}
