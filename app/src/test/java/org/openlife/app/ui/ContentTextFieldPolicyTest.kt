package org.openlife.app.ui

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-02 (P1-19-R5): the parts of [ContentTextField] that decide what may leave
 * a content-bearing field. The composable wiring is covered by the
 * instrumented ContentTextFieldTest; these rules are pure.
 */
class ContentTextFieldPolicyTest {
    @Test
    fun theToolbarNeverOffersCopyOrCut() {
        val platform = RecordingToolbar()
        val toolbar = NoClipboardWriteTextToolbar(platform)

        toolbar.showMenu(
            Rect.Zero,
            onCopyRequested = {},
            onPasteRequested = {},
            onCutRequested = {},
            onSelectAllRequested = {},
        )

        assertNull("no Copy", platform.copy)
        assertNull("no Cut", platform.cut)
        assertNotNull("Paste may stay", platform.paste)
        assertNotNull("Select all may stay", platform.selectAll)

        toolbar.showMenu(Rect.Zero, {}, {}, {}, {}, {})
        assertNull("no Copy (autofill overload)", platform.copy)
        assertNull("no Cut (autofill overload)", platform.cut)
    }

    @Test
    fun theNewContextMenuDropsCopyAndCutComponents() {
        assertFalse(ContentTextPolicy.keepsContextMenuItem(TextContextMenuKeys.CopyKey))
        assertFalse(ContentTextPolicy.keepsContextMenuItem(TextContextMenuKeys.CutKey))
        assertTrue(ContentTextPolicy.keepsContextMenuItem(TextContextMenuKeys.PasteKey))
        assertTrue(ContentTextPolicy.keepsContextMenuItem(TextContextMenuKeys.SelectAllKey))
    }

    @Test
    fun theEditorInfoAsksForNoPersonalisedLearning() {
        val flags = ContentTextPolicy.imeOptions(EditorInfo.IME_ACTION_DONE)
        assertEquals(EditorInfo.IME_ACTION_DONE, flags and EditorInfo.IME_MASK_ACTION)
        assertTrue(flags and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }

    @Test
    fun theKeyboardCannotCopyOrCutThroughTheInputConnectionOrShortcuts() {
        assertTrue(ContentTextPolicy.blocksContextMenuAction(android.R.id.copy))
        assertTrue(ContentTextPolicy.blocksContextMenuAction(android.R.id.cut))
        assertFalse(ContentTextPolicy.blocksContextMenuAction(android.R.id.paste))
        assertFalse(ContentTextPolicy.blocksContextMenuAction(android.R.id.selectAll))

        assertTrue(ContentTextPolicy.isClipboardWriteShortcut(KeyEvent.KEYCODE_C, ctrl = true, shift = false))
        assertTrue(ContentTextPolicy.isClipboardWriteShortcut(KeyEvent.KEYCODE_X, ctrl = true, shift = false))
        assertTrue(ContentTextPolicy.isClipboardWriteShortcut(KeyEvent.KEYCODE_INSERT, ctrl = true, shift = false))
        assertTrue(ContentTextPolicy.isClipboardWriteShortcut(KeyEvent.KEYCODE_FORWARD_DEL, ctrl = false, shift = true))
        assertTrue(ContentTextPolicy.isClipboardWriteShortcut(KeyEvent.KEYCODE_COPY, ctrl = false, shift = false))
        assertTrue(ContentTextPolicy.isClipboardWriteShortcut(KeyEvent.KEYCODE_CUT, ctrl = false, shift = false))
        assertFalse(ContentTextPolicy.isClipboardWriteShortcut(KeyEvent.KEYCODE_V, ctrl = true, shift = false))
        assertFalse(ContentTextPolicy.isClipboardWriteShortcut(KeyEvent.KEYCODE_C, ctrl = false, shift = false))
    }

    private class RecordingToolbar : TextToolbar {
        var copy: (() -> Unit)? = null
        var paste: (() -> Unit)? = null
        var cut: (() -> Unit)? = null
        var selectAll: (() -> Unit)? = null
        override val status: TextToolbarStatus = TextToolbarStatus.Hidden

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            copy = onCopyRequested
            paste = onPasteRequested
            cut = onCutRequested
            selectAll = onSelectAllRequested
        }

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
            onAutofillRequested: (() -> Unit)?,
        ) = showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)

        override fun hide() = Unit
    }
}
