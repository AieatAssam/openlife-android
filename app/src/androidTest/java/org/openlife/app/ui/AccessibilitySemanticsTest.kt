package org.openlife.app.ui

import org.openlife.vault.repository.ReadyReadResult
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
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
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.evidence.evidenceSpanPreview
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
    val strictMode = StrictModeRule()


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

        // Save is pinned below the scrolling details, so it is on screen
        // without scrolling at 2x font scale.
        composeRule.onNodeWithText("Save")
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

    /** A-08 (P1-19-R11): the perforation is decoration; TalkBack must not stop on it. */
    @Test
    fun perforationDividerIsNotAnnounced() {
        composeRule.setContent {
            org.openlife.app.ui.theme.OpenLifeTheme {
                Box(Modifier.testTag("perforation-host")) { org.openlife.app.ui.brand.PerforationDivider() }
            }
        }
        listOf(true, false).forEach { unmerged ->
            val described = composeRule.onAllNodes(
                androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("perforation-host"))
                    .and(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)),
                useUnmergedTree = unmerged,
            ).fetchSemanticsNodes()
            assertTrue("the divider is announced (unmerged=$unmerged): ${described.size}", described.isEmpty())
        }
    }

    /**
     * A-08 (P1-19-R11): every per-span action names the span it acts on, so a
     * TalkBack user hearing "Correct" knows which line it corrects.
     */
    @Test
    fun ocrRowActionsNameTheirSpan() {
        val sourceId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val grounded = syntheticSpan(revisionId, 0, "Synthetic appointment 14 October", grounded = true)
        val rejected = syntheticSpan(revisionId, 1, "Synthetic clinic room 3", grounded = true)
        composeRule.setContent {
            org.openlife.app.ui.theme.OpenLifeTheme {
                Box {
                    OcrSection(
                        source = readyAccessibilitySource(sourceId),
                        state = OcrUiState.Ready(
                            sourceId,
                            revisionId,
                            listOf(grounded, rejected),
                            spanReviews = mapOf(rejected.id to org.openlife.vault.ocr.OcrReviewState.REJECTED),
                        ),
                        onExtractText = {},
                        onCancel = {},
                        onSelectSpan = {},
                        onCorrect = {},
                        onReview = {},
                        onRejectSpan = {},
                        onUndoRejectSpan = {},
                    )
                }
            }
        }
        fun actionsNaming(span: org.openlife.vault.ocr.OcrSpan) = composeRule.onAllNodes(
            androidx.compose.ui.test.hasContentDescription(evidenceSpanPreview(span.text), substring = true)
                .and(androidx.compose.ui.test.hasClickAction()),
        ).fetchSemanticsNodes()
        assertTrue("Show region, Correct and Reject span name the span", actionsNaming(grounded).size >= 3)
        assertTrue("Correct and Undo reject name the rejected span", actionsNaming(rejected).size >= 2)
    }

    /**
     * A-08 (P1-19-R12, suspected): StampBadge set a contentDescription on its
     * Box and also drew the label as Text, which may expose the label twice.
     */
    @Test
    fun stampBadgeLabelIsAnnouncedOnce() {
        composeRule.setContent {
            org.openlife.app.ui.theme.OpenLifeTheme {
                Box(Modifier.testTag("stamp-host")) {
                    org.openlife.app.ui.brand.StampBadge(org.openlife.app.ui.brand.StampState.Confirmed)
                }
            }
        }
        listOf(false, true).forEach { unmerged ->
            val nodes = composeRule.onAllNodes(
                androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("stamp-host")),
                useUnmergedTree = unmerged,
            ).fetchSemanticsNodes()
            val announcing = nodes.count { node ->
                val config = node.config
                config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                    .orEmpty().any { it.isNotBlank() } ||
                    config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                        .orEmpty().any { it.text.isNotBlank() }
            }
            assertTrue("stamp label announced by $announcing nodes (unmerged=$unmerged)", announcing == 1)
        }
    }

    private fun syntheticSpan(revisionId: UUID, ordinal: Int, text: String, grounded: Boolean) =
        org.openlife.vault.ocr.OcrSpan(
            id = UUID.randomUUID(),
            revisionId = revisionId,
            ordinal = ordinal,
            text = text,
            confidence = null,
            coordinateSystem = org.openlife.vault.ocr.OcrCoordinateSystem.SOURCE_PIXELS,
            evidenceRegion = if (grounded) org.openlife.vault.ocr.OcrEvidenceRegion(10, 10 + ordinal * 40, 80, 40 + ordinal * 40) else null,
        )

    private fun readyAccessibilitySource(id: UUID) = org.openlife.vault.model.Source(
        id = id,
        state = org.openlife.vault.model.SourceState.READY,
        importedAt = 1L,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = 48,
        height = 200,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )
}
