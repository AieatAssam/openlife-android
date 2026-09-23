package org.openlife.app.ui

import org.openlife.vault.repository.ReadyReadResult
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState

/**
 * Deterministic accessibility regressions for the Compose boundary.
 *
 * These checks do not pretend to replace a TalkBack run: they verify that
 * controls expose actions/labels to Android's accessibility tree and that
 * critical actions remain reachable when the font scale is enlarged.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun firstRunExplanationKeepsContinueActionReachableAtLargeFontScale() {
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides androidx.compose.ui.unit.Density(
                    density = baseDensity.density,
                    fontScale = 2f,
                ),
            ) {
                FirstRunExplanationScreen(onContinue = {})
            }
        }

        composeRule.onNodeWithText("Before you import anything").assertIsDisplayed()
        composeRule.onNodeWithText("I understand")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun previewActionsKeepSaveReachableAtLargeFontScale() {
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides androidx.compose.ui.unit.Density(
                    density = baseDensity.density,
                    fontScale = 2f,
                ),
            ) {
                IntakeScreen(
                    state = IntakeUiState.Preview(
                        sourceId = UUID.randomUUID(),
                        format = ImageFormat.JPEG,
                        width = 100,
                        height = 100,
                        byteCount = 1,
                        previewBytes = tinyJpeg(),
                    ),
                    onSave = {},
                    onCancel = {},
                    onDone = {},
                )
            }
        }

        composeRule.onNodeWithText("Save")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithText("Cancel")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun primaryActionStaysAboveBottomInsetInSmallWindow() {
        composeRule.setContent {
            Box(
                modifier = Modifier
                    .requiredSize(400.dp, 700.dp)
                    .padding(bottom = 48.dp),
            ) {
                IntakeScreen(
                    state = IntakeUiState.Preview(
                        sourceId = UUID.randomUUID(),
                        format = ImageFormat.JPEG,
                        width = 100,
                        height = 100,
                        byteCount = 1,
                        previewBytes = tinyJpeg(),
                    ),
                    onSave = {},
                    onCancel = {},
                    onDone = {},
                )
            }
        }

        val bounds = composeRule.onNodeWithTag("primary_action").getUnclippedBoundsInRoot()
        assertTrue("primary action is above the 48dp bottom inset: $bounds", bounds.bottom <= 652.dp)
        assertTrue("primary action is above the window: $bounds", bounds.top >= 0.dp)
        assertTrue("primary action is left of the window: $bounds", bounds.left >= 0.dp)
        assertTrue("primary action is right of the window: $bounds", bounds.right <= 400.dp)
    }

    @Test
    fun firstRunExplanationKeepsContinueActionReachableAt13FontScale() {
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides androidx.compose.ui.unit.Density(
                    density = baseDensity.density,
                    fontScale = 1.3f,
                ),
            ) {
                FirstRunExplanationScreen(onContinue = {})
            }
        }

        composeRule.onNodeWithText("I understand")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun listActionStaysReachableAt2xFontScale() {
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides androidx.compose.ui.unit.Density(
                    density = baseDensity.density,
                    fontScale = 2f,
                ),
            ) {
                SourceListScreen(
                    state = SourceListUiState.Loaded(emptyList()),
                    loadThumbnail = { null },
                    onOpen = {},
                    onDelete = {},
                    onImportFromPhotoPicker = {},
                )
            }
        }

        composeRule.onNodeWithText("Import from photos")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun viewerActionsStayReachableAt2xFontScale() {
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides androidx.compose.ui.unit.Density(
                    density = baseDensity.density,
                    fontScale = 2f,
                ),
            ) {
                ViewerScreen(
                    source = readySource(),
                    loadContent = { ReadyReadResult.Loaded(tinyJpeg()) },
                    onBack = {},
                    onDeleteRequested = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithContentDescription("Delete").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun viewerImageExposesPrivacyPreservingAccessibleDescription() {
        composeRule.setContent {
            ViewerScreen(
                source = readySource(),
                loadContent = { ReadyReadResult.Loaded(tinyJpeg()) },
                onBack = {},
                onDeleteRequested = {},
            )
        }

        composeRule.onNodeWithContentDescription("Saved image").assertIsDisplayed()
    }

    @Test
    fun listImportAndRetryActionsExposeAccessibleLabels() {
        val source = Source(
            id = UUID.randomUUID(),
            state = SourceState.DELETING,
            importedAt = 1L,
            intakeKind = IntakeKind.SHARE,
            mimeType = null,
            byteCount = null,
            sha256 = null,
            width = null,
            height = null,
            orientation = null,
            wrappedDek = null,
            artefactVersion = null,
        )

        composeRule.setContent {
            SourceListScreen(
                state = SourceListUiState.Loaded(listOf(source)),
                loadThumbnail = { null },
                onOpen = {},
                onDelete = {},
                onImportFromPhotoPicker = {},
            )
        }

        composeRule.onNodeWithText("Import from photos")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithContentDescription("Retry deletion")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun screensMirrorCorrectlyUnderForcedRtl() {
        val source = readySource()
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SourceListScreen(
                    state = SourceListUiState.Loaded(listOf(source)),
                    loadThumbnail = { null },
                    onOpen = {},
                    onDelete = {},
                    onImportFromPhotoPicker = {},
                )
            }
        }

        composeRule.onNodeWithText("Import from photos")
            .assertIsDisplayed()
            .assertHasClickAction()
        // Clickable rows merge label text into the row node, so measure the
        // unmerged Text and Delete icon. "Before" is visual left in RTL.
        val deleteBounds = composeRule
            .onNodeWithContentDescription("Delete", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val labelBounds = composeRule
            .onNodeWithTag("source_row_label", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "RTL should place the delete action before the source label: delete=$deleteBounds label=$labelBounds",
            deleteBounds.right < labelBounds.left,
        )
    }

    private fun tinyJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun readySource(): Source = Source(
        id = UUID.randomUUID(),
        state = SourceState.READY,
        importedAt = 1L,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = 32,
        height = 24,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )
}
