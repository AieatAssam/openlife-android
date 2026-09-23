package org.openlife.app.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.settings.ResetVaultFlow
import org.openlife.vault.storage.VaultUnavailableCause
import org.openlife.vault.repository.VaultResetResult
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.vault.storage.VaultBootstrapper
import org.openlife.vault.crypto.Envelope
import org.openlife.vault.crypto.EnvelopeDomain
import org.openlife.vault.crypto.KeystoreWrapper
import java.security.ProviderException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VaultUnavailableFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun corruptKeyFileShowsUnrecoverableCopyWithoutResetButtonAndSettingsResetRequiresTypedConfirmation() {
        val showReset = mutableStateOf(false)
        var resetCount = 0
        composeRule.setContent {
            if (showReset.value) {
                ResetVaultFlow(onResetVault = { resetCount++; VaultResetResult.COMPLETED })
            } else {
                VaultUnavailableScreen(
                    cause = VaultUnavailableCause.KEY_FILE_CORRUPT,
                    onRetry = {},
                    onOpenResetSettings = {},
                )
            }
        }
        composeRule.onNodeWithText("The key file is damaged. OpenLife has not changed or deleted your saved data.")
            .assertExists()
        composeRule.onNodeWithText("Reset vault").assertDoesNotExist()

        composeRule.runOnIdle { showReset.value = true }
        composeRule.onNodeWithText("Reset vault").performClick()
        composeRule.onNodeWithText("Type RESET to permanently delete this vault and its saved items.").assertExists()
        composeRule.onNodeWithTag("reset-word").performTextInput("RESET")
        composeRule.onNodeWithText("Continue to reset").performClick()
        composeRule.onNodeWithText("This cannot be undone. OpenLife has no backup. Delete the vault now?").assertExists()
        composeRule.onNodeWithTag("final-reset-action").performClick()
        composeRule.runOnIdle { assertEquals(1, resetCount) }
    }

    @Test
    fun temporaryKeystoreFailureOffersRetryAndSucceedsAfterRetry() {
        val application = ApplicationProvider.getApplicationContext<OpenLifeApp>()
        var bootstrapAttempts = 0
        val failingWrapper = object : KeystoreWrapper() {
            private var failed = false

            override fun wrap(plaintext: ByteArray, domain: EnvelopeDomain, sourceId: UUID?): Envelope {
                if (!failed) {
                    failed = true
                    throw ProviderException("synthetic temporary keystore failure")
                }
                return super.wrap(plaintext, domain, sourceId)
            }

            override fun unwrap(envelope: Envelope, domain: EnvelopeDomain, sourceId: UUID?): ByteArray {
                if (!failed) {
                    failed = true
                    throw ProviderException("synthetic temporary keystore failure")
                }
                return super.unwrap(envelope, domain, sourceId)
            }
        }
        application.setBootstrapOverrideForTest { paths, wrapper ->
            bootstrapAttempts++
            if (bootstrapAttempts == 1) {
                VaultBootstrapper.bootstrap(paths, failingWrapper)
            } else {
                VaultBootstrapper.bootstrap(paths, wrapper)
            }
        }
        runBlocking {
            assertEquals(VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE,
                (application.vault() as VaultAccess.Unavailable).cause)
        }
        var retriedAccess: VaultAccess? = null
        composeRule.setContent {
            VaultUnavailableScreen(
                cause = VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE,
                onRetry = {
                    runBlocking {
                        application.retryVault()
                        retriedAccess = application.vault()
                    }
                },
                onOpenResetSettings = {},
            )
        }
        composeRule.onNodeWithText("Try again").assertExists().performClick()
        composeRule.runOnIdle {
            assertEquals(2, bootstrapAttempts)
            assertTrue(retriedAccess is VaultAccess.Ready)
            application.setBootstrapOverrideForTest(null)
        }
    }
}
