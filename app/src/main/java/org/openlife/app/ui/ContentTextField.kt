package org.openlife.app.ui

import android.annotation.SuppressLint
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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.KeyboardType

/**
 * P1-19-R5 (A-02, review Q-01): a text field for user content (OCR text,
 * corrections).
 *
 * OpenLife never writes user content to the clipboard (AGENTS.md). Checked
 * against the compiled foundation 1.12.0/1.12.1 classes (see
 * docs/verification/P1-19-app-audit-remediation.md):
 * - Every clipboard write of this field (menu Copy/Cut, Ctrl+C/X, the
 *   keyboard's copy/cut request, the accessibility CopyText/CutText actions)
 *   ends in `Clipboard.setClipEntry` on the field's `LocalClipboard`. Inside
 *   this field that local is [ReadOnlyClipboard], which drops every write;
 *   reads (Paste) still reach the platform.
 * - The context menu keeps only Paste and Select all ([ContentTextPolicy]),
 *   so Copy, Cut, Autofill, ACTION_PROCESS_TEXT apps (Translate and the like)
 *   and text-classification actions (Call, Map) are not offered.
 * - Copy/Cut shortcuts and the keyboard's copy/cut requests are refused
 *   before they reach the field, so a Cut never deletes text it could not
 *   copy.
 * - Autocorrect is off and the keyboard is asked not to learn from what is
 *   typed (IME_FLAG_NO_PERSONALIZED_LEARNING).
 * Smart selection, which sends selected text to the system TextClassifier,
 * is switched off app-wide in OpenLifeApp.
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
    val platformClipboard = LocalClipboard.current
    val clipboard = remember(platformClipboard) { ReadOnlyClipboard(platformClipboard) }
    CompositionLocalProvider(LocalClipboard provides clipboard) {
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
    /** An allow-list: anything not named here (Copy, Cut, Autofill, process-text, classification) is dropped. */
    fun keepsContextMenuItem(key: Any): Boolean =
        key == TextContextMenuKeys.PasteKey || key == TextContextMenuKeys.SelectAllKey

    fun imeOptions(current: Int): Int = current or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

    fun blocksContextMenuAction(id: Int): Boolean = id == android.R.id.copy || id == android.R.id.cut

    fun isClipboardWriteShortcut(keyCode: Int, ctrl: Boolean, shift: Boolean): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_COPY, KeyEvent.KEYCODE_CUT -> true
        KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_INSERT -> ctrl
        KeyEvent.KEYCODE_FORWARD_DEL -> shift && !ctrl
        else -> false
    }
}

/**
 * The clipboard a content field sees: reads go to the platform, writes are
 * dropped. It implements AndroidClipboard because foundation's paste check
 * (`ClipboardUtils.hasText`) requires one and reads only the primary clip's
 * description through it. AndroidClipboard is marked @VisibleForTesting in
 * ui 1.12 because it exists for substitute clipboards like this one.
 */
@SuppressLint("VisibleForTests")
internal class ReadOnlyClipboard(private val platform: Clipboard) : AndroidClipboard {
    override val clipboardManager get() = (platform as AndroidClipboard).clipboardManager

    override suspend fun getClipEntry(): ClipEntry? = platform.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
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
