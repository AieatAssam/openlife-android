package org.openlife.app.ui

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.input.KeyboardType

/**
 * P1-19-R5 (A-02): a text field for user content (OCR text, corrections).
 *
 * OpenLife never writes user content to the clipboard (AGENTS.md). A plain
 * Material text field offers Copy and Cut in its selection toolbar, in the
 * new context menu, through Ctrl+C-style shortcuts, and to the keyboard via
 * InputConnection.performContextMenuAction. This field removes every one of
 * those paths, keeps Paste and Select all, turns autocorrect off, and asks
 * the keyboard not to learn from what is typed
 * (IME_FLAG_NO_PERSONALIZED_LEARNING).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContentTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    val platformToolbar = LocalTextToolbar.current
    val toolbar = remember(platformToolbar) { NoClipboardWriteTextToolbar(platformToolbar) }
    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        InterceptPlatformTextInput(
            interceptor = { request, next -> next.startInputMethod(NoClipboardWriteInputRequest(request)) },
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = modifier
                    .filterTextContextMenuComponents { ContentTextPolicy.keepsContextMenuItem(it.key) }
                    .onPreviewKeyEvent { event ->
                        ContentTextPolicy.isClipboardWriteShortcut(
                            event.key.nativeKeyCode,
                            ctrl = event.isCtrlPressed,
                            shift = event.isShiftPressed,
                        )
                    },
                label = label,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text),
            )
        }
    }
}

/** Pure rules behind [ContentTextField], tested on the JVM. */
internal object ContentTextPolicy {
    fun keepsContextMenuItem(key: Any): Boolean =
        key != TextContextMenuKeys.CopyKey && key != TextContextMenuKeys.CutKey

    fun imeOptions(current: Int): Int = current or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

    fun blocksContextMenuAction(id: Int): Boolean = id == android.R.id.copy || id == android.R.id.cut

    fun isClipboardWriteShortcut(keyCode: Int, ctrl: Boolean, shift: Boolean): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_COPY, KeyEvent.KEYCODE_CUT -> true
        KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_INSERT -> ctrl
        KeyEvent.KEYCODE_FORWARD_DEL -> shift && !ctrl
        else -> false
    }
}

/** Shows the platform selection toolbar without Copy or Cut. */
internal class NoClipboardWriteTextToolbar(private val platform: TextToolbar) : TextToolbar {
    override val status: TextToolbarStatus get() = platform.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = platform.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested)

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        onAutofillRequested: (() -> Unit)?,
    ) = platform.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested, onAutofillRequested)

    override fun hide() = platform.hide()
}

/** Adds the no-learning flag and refuses keyboard-initiated Copy and Cut. */
private class NoClipboardWriteInputRequest(private val delegate: PlatformTextInputMethodRequest) :
    PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
        val connection = delegate.createInputConnection(outAttributes)
        outAttributes.imeOptions = ContentTextPolicy.imeOptions(outAttributes.imeOptions)
        return object : InputConnectionWrapper(connection, false) {
            override fun performContextMenuAction(id: Int): Boolean =
                if (ContentTextPolicy.blocksContextMenuAction(id)) false else super.performContextMenuAction(id)
        }
    }
}
