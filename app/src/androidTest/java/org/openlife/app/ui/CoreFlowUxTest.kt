package org.openlife.app.ui

import org.openlife.vault.repository.ReadyReadResult
import kotlinx.coroutines.awaitCancellation
import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.test.StrictModeRule
import org.openlife.app.settings.SettingsScreen
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState

/** P1-17: the core C0 flow is reachable, confirmed and ordered by what the user came for. */
@RunWith(AndroidJUnit4::class)
class CoreFlowUxTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun viewerDeleteAsksForConfirmationBeforeDeleting() {
        var deleteRequests = 0
        composeRule.setContent {
            OpenLifeTheme {
                ViewerScreen(
                    source = readySource(),
                    loadContent = { awaitCancellation() },
                    onBack = {},
                    onDeleteRequested = { deleteRequests++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Delete").performClick()
        assertEquals("delete must wait for confirmation", 0, deleteRequests)
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals("cancel must leave the item untouched", 0, deleteRequests)

        composeRule.onNodeWithContentDescription("Delete").performClick()
        composeRule.onNode(hasText("Delete") and hasClickAction()).performClick()
        assertEquals(1, deleteRequests)
    }

    @Test
    fun emptyListOffersOneImportAction() {
        var imports = 0
        composeRule.setContent {
            OpenLifeTheme {
                SourceListScreen(
                    state = SourceListUiState.Loaded(emptyList()),
                    loadThumbnail = { null },
                    onOpen = {},
                    onDelete = {},
                    onImportFromPhotoPicker = { imports++ },
                )
            }
        }

        composeRule.onNodeWithText("Nothing kept yet").assertIsDisplayed()
        composeRule.onAllNodesWithText("Import from photos").assertCountEquals(1)
        composeRule.onNodeWithText("Import from photos").performClick()
        assertEquals(1, imports)
    }

    @Test
    fun populatedListOffersImportAsPrimaryAction() {
        var imports = 0
        composeRule.setContent {
            OpenLifeTheme {
                SourceListScreen(
                    state = SourceListUiState.Loaded(listOf(readySource())),
                    loadThumbnail = { null },
                    onOpen = {},
                    onDelete = {},
                    onImportFromPhotoPicker = { imports++ },
                )
            }
        }

        composeRule.onAllNodesWithText("Import from photos").assertCountEquals(1)
        composeRule.onNodeWithText("Import from photos").assertIsDisplayed().performClick()
        assertEquals(1, imports)
    }

    @Test
    fun loadingListHasNoImportAction() {
        composeRule.setContent {
            OpenLifeTheme {
                SourceListScreen(
                    state = SourceListUiState.Loading,
                    loadThumbnail = { null },
                    onOpen = {},
                    onDelete = {},
                    onImportFromPhotoPicker = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Import from photos").assertCountEquals(0)
    }

    @Test
    fun overflowMenuReachesSettingsAndAbout() {
        var settings = 0
        var about = 0
        composeRule.setContent {
            OpenLifeTheme {
                SourceListScreen(
                    state = SourceListUiState.Loaded(emptyList()),
                    loadThumbnail = { null },
                    onOpen = {},
                    onDelete = {},
                    onImportFromPhotoPicker = {},
                    onOpenSettings = { settings++ },
                    onOpenAbout = { about++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("About").performClick()
        assertEquals(1, settings)
        assertEquals(1, about)
    }

    @Test
    fun settingsOffersBack() {
        var back = false
        composeRule.setContent {
            OpenLifeTheme {
                SettingsScreen(
                    onResetVault = { throw AssertionError("reset must not run") },
                    onResetComplete = {},
                    onBack = { back = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(back)
    }

    @Test
    fun aboutOffersBack() {
        var back = false
        composeRule.setContent {
            OpenLifeTheme { AboutScreen(onBack = { back = true }) }
        }

        composeRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(back)
    }

    @Test
    fun viewerShowsExtractedTextBeforeDetails() {
        composeRule.setContent {
            OpenLifeTheme {
                ViewerScreen(
                    source = readySource(),
                    loadContent = { awaitCancellation() },
                    onBack = {},
                    onDeleteRequested = {},
                )
            }
        }

        val extracted = composeRule.onNodeWithText("Extracted text", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val details = composeRule.onNodeWithText("Details", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("extracted=$extracted details=$details", extracted.top < details.top)
    }

    @Test
    fun viewerImageAreaFollowsSourceAspectRatio() {
        composeRule.setContent {
            OpenLifeTheme {
                ViewerScreen(
                    source = readySource(width = 200, height = 100),
                    loadContent = { ReadyReadResult.Loaded(jpeg(200, 100)) },
                    onBack = {},
                    onDeleteRequested = {},
                )
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Saved image"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        val area = composeRule.onNodeWithTag("viewer_image_area").getUnclippedBoundsInRoot()
        val ratio = (area.right - area.left).value / (area.bottom - area.top).value
        assertEquals("image area $area should follow the 2:1 source", 2f, ratio, 0.05f)
    }

    @Test
    fun viewerDetailsShowImportTimeOnce() {
        composeRule.setContent {
            OpenLifeTheme {
                ViewerScreen(
                    source = readySource(),
                    loadContent = { awaitCancellation() },
                    onBack = {},
                    onDeleteRequested = {},
                )
            }
        }

        composeRule.onAllNodes(hasText("Imported: Imported", substring = true), useUnmergedTree = true)
            .assertCountEquals(0)
        composeRule.onAllNodes(hasText("Imported: ", substring = true), useUnmergedTree = true)
            .assertCountEquals(1)
    }

    @Test
    fun savedStateOffersDone() {
        var done = false
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            OpenLifeTheme {
                IntakeScreen(
                    state = IntakeUiState.Saved(UUID.randomUUID()),
                    onSave = {},
                    onCancel = {},
                    onDone = { done = true },
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(100)

        assertFalse("saved must not auto-return immediately", done)
        composeRule.onNodeWithText("Done").performClick()
        assertTrue(done)
    }

    private fun readySource(width: Int = 100, height: Int = 100) = Source(
        id = UUID(0L, 7L),
        state = SourceState.READY,
        importedAt = 1L,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = width,
        height = height,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
