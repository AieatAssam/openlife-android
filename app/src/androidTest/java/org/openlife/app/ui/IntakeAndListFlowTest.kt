package org.openlife.app.ui

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.OpenLifeApp
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.repository.DeleteResult

/**
 * Exercises the real seam between the Stage 7 ViewModels, `OpenLifeApp`'s
 * cached vault access, and the actual repositories - the wiring this stage
 * added, as opposed to logic already covered by the vault module's own
 * tests. Deliberately drives the ViewModels directly rather than clicking
 * through Compose, since the risk here is in the wiring (does
 * `IntakeViewModel.confirmSave` actually make the source show up in
 * `SourceListViewModel`'s live query, does `SavedStateHandle` restoration
 * work), not in the vault's own save/prepare/delete logic.
 */
@RunWith(AndroidJUnit4::class)
class IntakeAndListFlowTest {

    private val application = ApplicationProvider.getApplicationContext<OpenLifeApp>()

    private fun syntheticJpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun savingThroughIntakeViewModelMakesTheSourceAppearInTheListAndDeletionRemovesIt(): Unit = runBlocking {
        val intakeViewModel = IntakeViewModel(application, SavedStateHandle())
        intakeViewModel.startImport(ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE)

        val prepared = awaitState(intakeViewModel) { it is IntakeUiState.Preview } as IntakeUiState.Preview

        intakeViewModel.confirmSave()
        awaitState(intakeViewModel) { it is IntakeUiState.Saved }

        val listViewModel = SourceListViewModel(application)
        val loaded = awaitListContaining(listViewModel, prepared.sourceId)
        assertTrue(loaded.sources.any { it.id == prepared.sourceId })

        var deleteResult: DeleteResult? = null
        listViewModel.delete(prepared.sourceId) { deleteResult = it }
        awaitCondition { deleteResult != null }
        assertEquals(DeleteResult.Deleted, deleteResult)

        val afterDelete = awaitListNotContaining(listViewModel, prepared.sourceId)
        assertTrue(afterDelete.sources.none { it.id == prepared.sourceId })
    }

    @Test
    fun cancellingAPreviewDiscardsItWithoutSaving(): Unit = runBlocking {
        val intakeViewModel = IntakeViewModel(application, SavedStateHandle())
        intakeViewModel.startImport(ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE)
        val prepared = awaitState(intakeViewModel) { it is IntakeUiState.Preview } as IntakeUiState.Preview

        intakeViewModel.cancel()
        awaitState(intakeViewModel) { it is IntakeUiState.Cancelled }

        val listViewModel = SourceListViewModel(application)
        val loaded = awaitState(listViewModel) { it is SourceListUiState.Loaded } as SourceListUiState.Loaded
        assertNull(loaded.sources.find { it.id == prepared.sourceId })
    }

    @Test
    fun restoringFromASavedStateHandleReAuthenticatesTheStagePreview(): Unit = runBlocking {
        val original = IntakeViewModel(application, SavedStateHandle())
        original.startImport(ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE)
        val prepared = awaitState(original) { it is IntakeUiState.Preview } as IntakeUiState.Preview

        // Simulate process recreation: a fresh ViewModel over a
        // SavedStateHandle that already has the Source UUID, the way
        // design §8 requires rotation to survive - never via plaintext
        // bytes carried in saved instance state.
        val restoredHandle = SavedStateHandle(mapOf("org.openlife.app.ui.IntakeViewModel.sourceId" to prepared.sourceId.toString()))
        val restored = IntakeViewModel(application, restoredHandle)
        val restoredState = awaitState(restored) { it is IntakeUiState.Preview } as IntakeUiState.Preview

        assertEquals(prepared.sourceId, restoredState.sourceId)
        assertEquals(prepared.width, restoredState.width)
        assertTrue(restoredState.previewBytes != null)

        restored.cancel()
    }

    private suspend fun awaitState(viewModel: IntakeViewModel, predicate: (IntakeUiState) -> Boolean): IntakeUiState {
        var last: IntakeUiState = viewModel.state.value
        awaitCondition {
            last = viewModel.state.value
            predicate(last)
        }
        return last
    }

    private suspend fun awaitState(viewModel: SourceListViewModel, predicate: (SourceListUiState) -> Boolean): SourceListUiState {
        var last: SourceListUiState = viewModel.state.value
        awaitCondition {
            last = viewModel.state.value
            predicate(last)
        }
        return last
    }

    private suspend fun awaitListContaining(
        viewModel: SourceListViewModel,
        id: java.util.UUID,
    ): SourceListUiState.Loaded = awaitState(viewModel) {
        it is SourceListUiState.Loaded && it.sources.any { s -> s.id == id }
    } as SourceListUiState.Loaded

    private suspend fun awaitListNotContaining(
        viewModel: SourceListViewModel,
        id: java.util.UUID,
    ): SourceListUiState.Loaded = awaitState(viewModel) {
        it is SourceListUiState.Loaded && it.sources.none { s -> s.id == id }
    } as SourceListUiState.Loaded

    private suspend fun awaitCondition(timeoutMs: Long = 15_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            kotlinx.coroutines.delay(50)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }
}
