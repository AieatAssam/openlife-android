package org.openlife.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.test.StrictModeRule
import org.openlife.app.OpenLifeApp
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.SourceState
import org.openlife.vault.repository.DeleteResult
import org.openlife.vault.storage.VaultPaths

/**
 * Regression coverage for the app-level deletion failure contract: a
 * filesystem failure must leave a visible, unavailable DELETING row that can
 * be retried, rather than silently disappearing from the source list.
 */
@RunWith(AndroidJUnit4::class)
class DeletionRetryFlowTest {
    @get:Rule
    val strictMode = StrictModeRule()


    private val application = ApplicationProvider.getApplicationContext<OpenLifeApp>()

    @Test
    fun failedDeletionRemainsVisibleUntilRetrySucceeds(): Unit = runBlocking {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        val color = UUID.randomUUID().mostSignificantBits.toInt() or 0xff000000.toInt()
        bitmap.eraseColor(color)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()

        val intake = IntakeViewModel(application, SavedStateHandle())
        intake.startImport(ByteArrayInputStream(out.toByteArray()), "image/jpeg", IntakeKind.SHARE)
        val preview = awaitState(intake) { it is IntakeUiState.Preview } as IntakeUiState.Preview
        intake.confirmSave()
        awaitState(intake) { it is IntakeUiState.Saved }

        val paths = VaultPaths(application)
        val blob = paths.blobFile(preview.sourceId)
        assertTrue(blob.exists())
        assertTrue(blob.delete())
        assertTrue(blob.mkdir())
        val blocker = File(blob, "occupied")
        blocker.writeText("x")

        val list = SourceListViewModel(application)
        try {
            awaitListContaining(list, preview.sourceId)

            var result: DeleteResult? = null
            list.delete(preview.sourceId) { result = it }
            awaitCondition { result != null }
            assertEquals(DeleteResult.Failed, result)

            val pending = awaitState(list) {
                it is SourceListUiState.Loaded && it.sources.any { source ->
                    source.id == preview.sourceId && source.state == SourceState.DELETING
                }
            } as SourceListUiState.Loaded
            assertTrue(pending.sources.any { it.id == preview.sourceId && it.state == SourceState.DELETING })

            assertTrue(blocker.delete())
            assertTrue(blob.delete())
            result = null
            list.delete(preview.sourceId) { result = it }
            awaitCondition { result != null }
            assertEquals(DeleteResult.Deleted, result)
            awaitState(list) {
                it is SourceListUiState.Loaded && it.sources.none { source -> source.id == preview.sourceId }
            }
        } finally {
            blocker.delete()
            blob.deleteRecursively()
        }
    }

    private suspend fun awaitState(viewModel: IntakeViewModel, predicate: (IntakeUiState) -> Boolean): IntakeUiState {
        var state = viewModel.state.value
        awaitCondition {
            state = viewModel.state.value
            predicate(state)
        }
        return state
    }

    private suspend fun awaitState(viewModel: SourceListViewModel, predicate: (SourceListUiState) -> Boolean): SourceListUiState {
        var state = viewModel.state.value
        awaitCondition {
            state = viewModel.state.value
            predicate(state)
        }
        return state
    }

    private suspend fun awaitListContaining(viewModel: SourceListViewModel, id: UUID) {
        awaitState(viewModel) { state ->
            state is SourceListUiState.Loaded && state.sources.any { it.id == id }
        }
    }

    private suspend fun awaitCondition(timeoutMs: Long = 30_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            kotlinx.coroutines.delay(50)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }
}
