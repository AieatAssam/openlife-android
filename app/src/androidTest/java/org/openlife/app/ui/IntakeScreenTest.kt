package org.openlife.app.ui

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.test.StrictModeRule
import org.openlife.vault.model.ImageFormat

@RunWith(AndroidJUnit4::class)
class IntakeScreenTest {
    @get:Rule
    val strictMode = StrictModeRule()


    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun saveIsUnavailableWhenAuthenticatedPreviewCannotBeLoaded() {
        composeRule.setContent {
            IntakeScreen(
                state = IntakeUiState.Preview(
                    sourceId = UUID.randomUUID(),
                    format = ImageFormat.JPEG,
                    width = 100,
                    height = 100,
                    byteCount = 1,
                    previewBytes = null,
                ),
                onSave = {},
                onCancel = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    /** P1-02-R8 / F-47 / design §3 R3: picked items may come from a cloud provider OpenLife does not control. */
    @Test
    fun photoPickerPreviewExplainsCloudProviders() {
        composeRule.setContent {
            IntakeScreen(
                state = IntakeUiState.Preview(
                    sourceId = UUID.randomUUID(),
                    format = ImageFormat.JPEG,
                    width = 100,
                    height = 100,
                    byteCount = 1,
                    previewBytes = null,
                    intakeKind = org.openlife.vault.model.IntakeKind.PHOTO_PICKER,
                ),
                onSave = {},
                onCancel = {},
                onDone = {},
            )
        }

        composeRule.onNodeWithText("may come from a cloud photo service", substring = true).assertExists()
        composeRule.onNodeWithText("OpenLife never uploads", substring = true).assertExists()
    }

    /** The preview's Save and Cancel stay on screen however long the explanatory text is. */
    @Test
    fun previewActionsStayVisibleOnASmallScreenWithTheCloudNote() {
        composeRule.setContent {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.size(width = 320.dp, height = 480.dp),
            ) {
                IntakeScreen(
                    state = IntakeUiState.Preview(
                        sourceId = UUID.randomUUID(),
                        format = ImageFormat.JPEG,
                        width = 100,
                        height = 100,
                        byteCount = 1,
                        previewBytes = null,
                        intakeKind = org.openlife.vault.model.IntakeKind.PHOTO_PICKER,
                    ),
                    onSave = {},
                    onCancel = {},
                    onDone = {},
                )
            }
        }

        composeRule.onNodeWithText("Save").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun duplicateOffersOpenExistingOrCancel() {
        val existingId = UUID.randomUUID()
        var openedId: UUID? = null
        var cancelled = false

        composeRule.setContent {
            IntakeScreen(
                state = IntakeUiState.Duplicate(existingId),
                onSave = {},
                onCancel = {},
                onDone = { cancelled = true },
                onOpenExisting = { openedId = it },
            )
        }

        composeRule.onNodeWithText("Open existing").performClick()
        assertEquals(existingId, openedId)

        composeRule.onNodeWithText("Cancel").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun storageUnavailableStateExplainsAndOffersDone() {
        var done = false
        composeRule.setContent {
            IntakeScreen(
                state = IntakeUiState.StorageUnavailable,
                onSave = {},
                onCancel = {},
                onDone = { done = true },
            )
        }

        composeRule.onNodeWithText("Storage is unavailable. Nothing was saved because there may not be enough free space.")
            .assertExists()
        composeRule.onNodeWithText("Done").performClick()
        assertTrue(done)
    }
}
