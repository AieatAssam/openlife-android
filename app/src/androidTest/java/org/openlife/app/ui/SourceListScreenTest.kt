package org.openlife.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.test.StrictModeRule
import org.openlife.app.OpenLifeApp
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.repository.DeleteResult

/**
 * F-28 / P1-13-R6: the thumbnail cache must never recycle a bitmap a visible
 * row may still draw. Each test renders a real saved thumbnail, triggers the
 * release path while the row is on screen, and forces frames with
 * captureToImage(); drawing a recycled bitmap throws on the main thread.
 */
@RunWith(AndroidJUnit4::class)
class SourceListScreenTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<OpenLifeApp>()
    private val darkTheme = mutableStateOf(false)

    @Test
    fun failedDeletionRemainsVisibleAsRetryableUnavailableItem() {
        val source = Source(
            id = UUID.randomUUID(),
            state = SourceState.DELETING,
            importedAt = 0L,
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

        composeRule.onNodeWithText("Deletion pending — retry").assertIsDisplayed()
        composeRule.onNodeWithText("Content unavailable").assertIsDisplayed()
    }

    @Test
    fun deletingAVisibleRowDoesNotCrashWithRecycledBitmap(): Unit = runBlocking {
        val sourceId = saveASource()
        val list = showListWithThumbnail(sourceId)

        val result = AtomicReference<DeleteResult?>(null)
        withContext(Dispatchers.Main) { list.delete(sourceId) { result.set(it) } }
        val deadline = System.currentTimeMillis() + 30_000
        while (result.get() == null && System.currentTimeMillis() < deadline) {
            // Toggling the theme re-records every row's drawing; a recycled
            // bitmap only throws when its draw call is recorded again.
            darkTheme.value = !darkTheme.value
            composeRule.onRoot().captureToImage()
            delay(16)
        }
        repeat(FRAMES_AFTER) {
            darkTheme.value = !darkTheme.value
            composeRule.onRoot().captureToImage()
        }
        assertTrue(result.get() is DeleteResult.Deleted)
    }

    @Test
    fun trimmingWhileRowsAreVisibleDoesNotDrawARecycledBitmap(): Unit = runBlocking {
        val sourceId = saveASource()
        val list = showListWithThumbnail(sourceId)

        composeRule.runOnUiThread { list.clearSensitiveContent() }
        repeat(FRAMES_AFTER) { composeRule.onRoot().captureToImage() }

        withContext(Dispatchers.Main) { list.delete(sourceId) {} }
    }

    private suspend fun showListWithThumbnail(sourceId: UUID): SourceListViewModel {
        val list = withContext(Dispatchers.Main) { SourceListViewModel(application) }
        composeRule.setContent {
            val state by list.state.collectAsState()
            val generation by list.sensitiveContentGeneration.collectAsState()
            OpenLifeTheme(darkTheme = darkTheme.value) {
                SourceListScreen(
                    state = state,
                    loadThumbnail = list::loadThumbnail,
                    thumbnailGeneration = generation,
                    onOpen = {},
                    onDelete = {},
                    onImportFromPhotoPicker = {},
                )
            }
        }
        composeRule.waitUntil(60_000) {
            composeRule.onAllNodes(hasContentDescription("Saved image thumbnail"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        return list
    }

    private suspend fun saveASource(): UUID {
        val intake = withContext(Dispatchers.Main) { IntakeViewModel(application, SavedStateHandle()) }
        withContext(Dispatchers.Main) {
            intake.startImport(ByteArrayInputStream(uniqueJpeg()), "image/jpeg", IntakeKind.SHARE)
        }
        val preview = awaitIntake(intake) { it as? IntakeUiState.Preview }
        withContext(Dispatchers.Main) { intake.confirmSave() }
        awaitIntake(intake) { it as? IntakeUiState.Saved }
        return preview.sourceId
    }

    private suspend fun <T : Any> awaitIntake(intake: IntakeViewModel, pick: (IntakeUiState) -> T?): T {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            pick(intake.state.value)?.let { return it }
            delay(50)
        }
        throw AssertionError("intake state not reached; last=${intake.state.value}")
    }

    private fun uniqueJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(UUID.randomUUID().hashCode() or 0xFF000000.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private companion object {
        const val FRAMES_AFTER = 5
    }
}
