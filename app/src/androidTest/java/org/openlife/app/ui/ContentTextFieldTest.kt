package org.openlife.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.theme.OpenLifeTheme

/**
 * A-02 (P1-19-R5, review Q-01/Q-08): the OCR correction field holds user
 * content. It must offer Paste but not Copy or Cut, must not write the
 * clipboard through keyboard shortcuts or the accessibility Copy action, and
 * must ask the keyboard not to learn from what is typed. The test writes a
 * sentinel to the device clipboard and restores the previous clip afterwards
 * when it could read one. If the read returns null (an empty clipboard, or
 * one the test could not read) the sentinel is left in place: the test never
 * clears a clipboard it could not read.
 */
@RunWith(AndroidJUnit4::class)
class ContentTextFieldTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val clipboard = instrumentation.targetContext.getSystemService(ClipboardManager::class.java)

    /** The device clipboard as it was before this test, if it could be read; put back in [restoreClipboard]. */
    private var savedClip: ClipData? = null

    @After
    fun restoreClipboard() {
        val saved = savedClip ?: return
        instrumentation.runOnMainSync { clipboard.setPrimaryClip(saved) }
    }

    @Test
    fun correctionFieldOffersPasteButNoCopyOrCutAndNeverWritesTheClipboard() {
        composeRule.setContent {
            OpenLifeTheme {
                var text by remember { mutableStateOf(SYNTHETIC_CONTENT) }
                ContentTextField(value = text, onValueChange = { text = it }, modifier = Modifier.testTag(FIELD))
            }
        }
        val field = composeRule.onNodeWithTag(FIELD)
        field.performClick()
        composeRule.waitForIdle()
        // Saved after the window has focus: from API 29 an unfocused app reads null.
        instrumentation.runOnMainSync {
            savedClip = clipboard.primaryClip
            clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", SENTINEL))
        }

        field.performTextInputSelection(TextRange(0, SYNTHETIC_CONTENT.length))
        field.performTouchInput { longClick() }
        composeRule.waitForIdle()
        // Positive control: the menu is shown and Paste is offered.
        assertTrue("Paste is not offered", device.wait(Until.hasObject(By.text("Paste")), WAIT_MS))
        assertFalse("a Copy item is offered", device.hasObject(By.text("Copy")))
        assertFalse("a Cut item is offered", device.hasObject(By.text("Cut")))
        device.pressBack()

        field.performTextInputSelection(TextRange(0, SYNTHETIC_CONTENT.length))
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.X) } }
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Insert) } }
        field.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Delete) } }
        composeRule.waitForIdle()
        assertEquals("a shortcut wrote the clipboard", SENTINEL, primaryClipText())

        // The accessibility route (TalkBack / Voice Access "Copy").
        field.performTextInputSelection(TextRange(0, SYNTHETIC_CONTENT.length))
        field.performSemanticsAction(SemanticsActions.CopyText)
        composeRule.waitForIdle()
        assertEquals("the accessibility Copy action wrote the clipboard", SENTINEL, primaryClipText())
    }

    private fun primaryClipText(): String? {
        var clip: CharSequence? = null
        instrumentation.runOnMainSync { clip = clipboard.primaryClip?.getItemAt(0)?.text }
        return clip?.toString()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun correctionFieldDisablesAutocorrectAndPersonalisedLearning() {
        var captured: EditorInfo? = null
        composeRule.setContent {
            OpenLifeTheme {
                var text by remember { mutableStateOf(SYNTHETIC_CONTENT) }
                // The test's interceptor is outside ContentTextField's own, so it
                // sees the request after ContentTextField adjusted it.
                InterceptPlatformTextInput(
                    interceptor = { request, nextHandler ->
                        val info = EditorInfo()
                        request.createInputConnection(info)
                        captured = info
                        nextHandler.startInputMethod(request)
                    },
                ) {
                    ContentTextField(value = text, onValueChange = { text = it }, modifier = Modifier.testTag(FIELD))
                }
            }
        }
        composeRule.onNodeWithTag(FIELD).performClick()
        composeRule.waitUntil(WAIT_MS) { captured != null }

        val info = assertNotNullAndGet(captured)
        assertTrue(
            "IME_FLAG_NO_PERSONALIZED_LEARNING missing from imeOptions=${info.imeOptions}",
            info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0,
        )
        assertEquals(
            "autocorrect must be off",
            0,
            info.inputType and android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT,
        )
    }

    private fun assertNotNullAndGet(info: EditorInfo?): EditorInfo {
        assertNotNull("no EditorInfo was requested", info)
        return info!!
    }

    private companion object {
        const val FIELD = "content-field"
        const val SENTINEL = "synthetic clipboard sentinel"
        const val SYNTHETIC_CONTENT = "Synthetic appointment 14 October"
        const val WAIT_MS = 10_000L
    }
}
