package org.openlife.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.MainActivity
import org.openlife.app.ui.theme.OpenLifeTheme

/**
 * Review U-01: content never reaches an autofill service. The field reports
 * no autofill data type and sensitive data, and the windows that host
 * content (each activity, and the dialog the correction field opens in)
 * report not-important-for-autofill for every view below their root.
 * Read-only device check alongside it: `adb shell dumpsys autofill` while the
 * correction field is focused lists no OpenLife session or field value.
 */
@RunWith(AndroidJUnit4::class)
class AutofillExclusionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun contentFieldIsNotAutofillableAndItsDialogWindowIsExcluded() {
        var dialogView: View? = null
        compose.setContent {
            OpenLifeTheme {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    text = {
                        val view = LocalView.current
                        SideEffect { dialogView = view }
                        ContentTextField(
                            value = SYNTHETIC_CONTENT,
                            onValueChange = {},
                            modifier = Modifier.testTag(FIELD_TAG),
                            label = { Text("Correction") },
                        )
                    },
                )
            }
        }

        val config = compose.onNodeWithTag(FIELD_TAG).fetchSemanticsNode().config
        assertEquals(ContentDataType.None, config.getOrNull(SemanticsProperties.ContentDataType))
        assertEquals(true, config.getOrNull(SemanticsProperties.IsSensitiveData))
        compose.runOnIdle {
            val view = assertNotNullAndGet(dialogView)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, view.rootView.importantForAutofill)
            assertFalse("the dialog's compose view is excluded", view.isImportantForAutofill)
        }
    }

    @Test
    fun activityWindowIsExcludedFromAutofill() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                    activity.window.decorView.importantForAutofill,
                )
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val hosted = content.getChildAt(0)
                assertNotNull("setContent hosted a view", hosted)
                assertFalse("the hosted compose view is excluded", hosted.isImportantForAutofill)
            }
        }
    }

    private fun assertNotNullAndGet(view: View?): View {
        assertNotNull(view)
        return view!!
    }

    private companion object {
        const val FIELD_TAG = "autofill_probe_field"
        const val SYNTHETIC_CONTENT = "SYNTHETIC OCR LINE 42"
    }
}
