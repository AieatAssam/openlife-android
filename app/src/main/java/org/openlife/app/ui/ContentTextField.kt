package org.openlife.app.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/** RED stub (P1-19-R5). */
internal class NoClipboardWriteTextToolbar(private val platform: TextToolbar) : TextToolbar {
    override val status: TextToolbarStatus get() = platform.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ): Unit = TODO("P1-19-R5")

    override fun hide() = platform.hide()
}

/** RED stub (P1-19-R5). */
internal object ContentTextPolicy {
    fun keepsContextMenuItem(key: Any): Boolean = TODO("P1-19-R5 $key")

    fun imeOptions(current: Int): Int = TODO("P1-19-R5 $current")

    fun blocksContextMenuAction(id: Int): Boolean = TODO("P1-19-R5 $id")

    fun isClipboardWriteShortcut(keyCode: Int, ctrl: Boolean, shift: Boolean): Boolean = TODO("P1-19-R5 $keyCode $ctrl $shift")
}

/** RED stub (P1-19-R5). */
@androidx.compose.runtime.Composable
fun ContentTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    label: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    singleLine: Boolean = false,
): Unit = TODO("P1-19-R5 $value $onValueChange $modifier $label $singleLine")
