package org.openlife.app.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.repository.VaultResetResult

/**
 * P1-08 (tdd.red, with P1-06's VaultUnavailableFlowTest, which types the
 * word and resets): Continue stays disabled until exactly the required word
 * is typed, and nothing is reset before the final confirmation.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun resetVaultRequiresTypedConfirmation() {
        var resets = 0
        composeRule.setContent {
            OpenLifeTheme {
                ResetVaultFlow(onResetVault = {
                    resets++
                    VaultResetResult.COMPLETED
                })
            }
        }
        composeRule.onNodeWithText("Reset vault").performClick()
        listOf("", "RES", "reset", "RESET ", " RESET", "RESETS").forEach { word ->
            composeRule.onNodeWithTag("reset-word").performTextReplacement(word)
            composeRule.onNodeWithText("Continue to reset").assertIsNotEnabled()
        }
        composeRule.onNodeWithTag("reset-word").performTextReplacement("RESET")
        composeRule.onNodeWithText("Continue to reset").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Confirm vault deletion").assertExists()
        composeRule.runOnIdle { assertEquals("reset before the final confirmation", 0, resets) }

        composeRule.onNodeWithTag("final-reset-action").performClick()
        composeRule.runOnIdle { assertEquals(1, resets) }
    }
}
