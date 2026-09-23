package org.openlife.app.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.settings.ResetVaultFlow
import org.openlife.vault.storage.VaultUnavailableCause

@RunWith(AndroidJUnit4::class)
class VaultUnavailableFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun corruptKeyFileShowsUnrecoverableCopyWithoutResetButtonAndSettingsResetRequiresTypedConfirmation() {
        composeRule.setContent {
            VaultUnavailableScreen(
                cause = VaultUnavailableCause.KEY_FILE_CORRUPT,
                onRetry = {},
                onOpenResetSettings = {},
            )
        }
        composeRule.onNodeWithText("The key file is damaged. OpenLife has not changed or deleted your saved data.")
            .assertExists()
        composeRule.onNodeWithText("Reset vault").assertDoesNotExist()

        var resetCount = 0
        composeRule.setContent { ResetVaultFlow(onReset = { resetCount++ }) }
        composeRule.onNodeWithText("Type RESET to permanently delete this vault and its saved items.").assertExists()
        composeRule.onNodeWithTag("reset-word").performTextInput("RESET")
        composeRule.onNodeWithText("Continue to reset").performClick()
        composeRule.onNodeWithText("This cannot be undone. OpenLife has no backup. Delete the vault now?").assertExists()
        composeRule.onNodeWithText("Reset vault").performClick()
        composeRule.runOnIdle { assertEquals(1, resetCount) }
    }

    @Test
    fun temporaryKeystoreFailureOffersRetryAndSucceedsAfterRetry() {
        var attempts = 0
        composeRule.setContent {
            VaultUnavailableScreen(
                cause = VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE,
                onRetry = { attempts++ },
                onOpenResetSettings = {},
            )
        }
        composeRule.onNodeWithText("Try again").assertExists().performClick()
        composeRule.runOnIdle { assertEquals(1, attempts) }
    }
}
