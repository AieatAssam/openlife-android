package org.openlife.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.lock.AppLockPolicy
import org.openlife.app.lock.LockAvailability
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.settings.AppLockSettings
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.repository.VaultResetResult

/**
 * P1-16-R1: at expanded width the list and the viewer are two panes of one
 * screen; at compact width opening an item replaces the list. P1-16-R4: a
 * low-RAM device is told about the supported memory floor.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun expandedWidthShowsTwoPanes() {
        setHost(DpSize(1280.dp, 800.dp))

        composeRule.onNodeWithText(PLACEHOLDER).assertIsDisplayed()
        composeRule.onNodeWithText("Imported", substring = true).performClick()

        // The viewer's own top bar is in the detail pane (its content scrolls below).
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText(PLACEHOLDER).assertDoesNotExist()
        // The list, with its import action, is still on screen beside the viewer.
        composeRule.onNodeWithText("Import from photos").assertIsDisplayed()
    }

    @Test
    fun compactWidthOpensTheViewerInPlaceOfTheList() {
        setHost(DpSize(400.dp, 800.dp))

        composeRule.onNodeWithText("Imported", substring = true).performClick()

        composeRule.onNodeWithText("Details").assertIsDisplayed()
        composeRule.onNodeWithText("Import from photos").assertDoesNotExist()
    }

    @Test
    fun aboutWarnsOnLowRamDevice() {
        composeRule.setContent { OpenLifeTheme { AboutScreen(lowRamDevice = true) } }

        composeRule.onNodeWithText("low-memory device", substring = true).assertIsDisplayed()
    }

    private fun setHost(size: DpSize) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                OpenLifeTheme { Host() }
            }
        }
    }

    @Composable
    private fun Host() {
        OpenLifeNavHost(
            listState = SourceListUiState.Loaded(listOf(readySource())),
            thumbnailGeneration = 0L,
            ocrState = { OcrUiState.Idle },
            loadThumbnail = { null },
            loadReadyContent = { awaitCancellation() },
            delete = { _, _ -> },
            extractText = {},
            cancelOcr = {},
            correct = { _, _, _ -> },
            review = { _, _ -> },
            onImportFromPhotoPicker = {},
            onRetryVault = {},
            onFinishReset = {},
            onResetVault = { VaultResetResult.FAILED },
            onVerifyAll = { null },
            appLock = AppLockSettings(AppLockPolicy(), { LockAvailability.AVAILABLE }, {}),
            onResetComplete = {},
        )
    }

    private fun readySource() = Source(
        id = UUID(0L, 16L),
        state = SourceState.READY,
        importedAt = 1L,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = 100,
        height = 100,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )

    private companion object {
        const val PLACEHOLDER = "Select an item to see it here."
    }
}
