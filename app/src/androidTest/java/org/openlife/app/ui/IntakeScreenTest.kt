package org.openlife.app.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.model.ImageFormat

@RunWith(AndroidJUnit4::class)
class IntakeScreenTest {

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
}
