package org.openlife.app.ui

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.text.contextmenu.data.ProcessTextKey
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-02 (P1-19-R5, review Q-01): the parts of [ContentTextField] that decide
 * what may leave a content-bearing field. The composable wiring is covered by
 * the instrumented ContentTextFieldTest; these rules are pure.
 */
class ContentTextFieldPolicyTest {
    @Test
    fun theContextMenuKeepsOnlyPasteAndSelectAll() {
        assertTrue(ContentTextPolicy.keepsContextMenuItem(TextContextMenuKeys.PasteKey))
        assertTrue(ContentTextPolicy.keepsContextMenuItem(TextContextMenuKeys.SelectAllKey))

        assertFalse("Copy", ContentTextPolicy.keepsContextMenuItem(TextContextMenuKeys.CopyKey))
        assertFalse("Cut", ContentTextPolicy.keepsContextMenuItem(TextContextMenuKeys.CutKey))
        assertFalse("Autofill", ContentTextPolicy.keepsContextMenuItem(TextContextMenuKeys.AutofillKey))
        assertFalse("ACTION_PROCESS_TEXT item", ContentTextPolicy.keepsContextMenuItem(ProcessTextKey(1)))
        // Text-classification items and separators carry keys of their own; an
        // allow-list drops any key it does not name.
        assertFalse("unknown key", ContentTextPolicy.keepsContextMenuItem(Any()))
    }

    @Test
    fun theFieldClipboardDropsWritesAndKeepsReads(): Unit = runBlocking {
        val platform = CountingClipboard()
        val clipboard = ReadOnlyClipboard(platform)

        clipboard.setClipEntry(null)
        assertEquals("a write reached the platform clipboard", 0, platform.writes)

        assertNull(clipboard.getClipEntry())
        assertEquals("Paste must still read the platform clipboard", 1, platform.reads)
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

    private class CountingClipboard : Clipboard {
        var reads = 0
        var writes = 0

        override suspend fun getClipEntry(): ClipEntry? {
            reads++
            return null
        }

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            writes++
        }
    }
}
