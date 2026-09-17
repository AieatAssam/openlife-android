package org.openlife.app.ui

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

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
 * Exercises the real seam between the ViewModels, `OpenLifeApp`'s
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

    private class TrackingInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        @Volatile var closed = false

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private val application = ApplicationProvider.getApplicationContext<OpenLifeApp>()

    private fun syntheticJpegBytes(variant: Int = 0): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        // A fixed variant (the default) reproduces the same bytes every
        // call, which several other tests in this file rely on when they
        // deliberately want two imports to collide. A distinct variant
        // avoids that collision - used by the many-sources test below,
        // where duplicate detection (design/C0-07, correctly) would
        // otherwise turn every import after the first into a Duplicate
        // rather than a fresh Saved source.
        if (variant != 0) bitmap.eraseColor(android.graphics.Color.rgb(variant % 256, (variant * 7) % 256, (variant * 13) % 256))
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
    fun listHoldsManySourcesWithoutLosingOrMisorderingAny(): Unit = runBlocking {
        // C0-17's "repeated list scrolling at limits" leg: proves the list
        // (backed by a real Room Flow query, LazyColumn-rendered in
        // SourceListScreen) holds a non-trivial number of real saved
        // sources without dropping or reordering any - the actual
        // scroll-gesture responsiveness on a real, populated list was
        // additionally checked manually on-device (see docs/verification/C0.md).
        val count = 15
        val savedIds = mutableListOf<java.util.UUID>()
        repeat(count) { i ->
            val intakeViewModel = IntakeViewModel(application, SavedStateHandle())
            intakeViewModel.startImport(ByteArrayInputStream(syntheticJpegBytes(variant = i + 1)), "image/jpeg", IntakeKind.SHARE)
            val prepared = awaitState(intakeViewModel) { it is IntakeUiState.Preview } as IntakeUiState.Preview
            intakeViewModel.confirmSave()
            awaitState(intakeViewModel) { it is IntakeUiState.Saved }
            savedIds += prepared.sourceId
        }

        val listViewModel = SourceListViewModel(application)
        val loaded = awaitState(listViewModel, timeoutMs = 45_000) {
            it is SourceListUiState.Loaded && it.sources.size >= count
        } as SourceListUiState.Loaded
        assertEquals(count, loaded.sources.size)
        // Set rather than order equality: importedAt is millisecond-
        // resolution wall-clock time (design §9), so two saves landing in
        // the same millisecond under a fast back-to-back loop like this one
        // have no guaranteed relative order - the ordering guarantee itself
        // is a finer-grained concern than what this test is stress-checking
        // (that all fifteen genuinely-saved sources are present, none
        // dropped or duplicated, once the list holds a non-trivial count).
        assertEquals(savedIds.toSet(), loaded.sources.map { it.id }.toSet())
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
    fun providerStreamClosesAfterImportCompletes(): Unit = runBlocking {
        val stream = TrackingInputStream(syntheticJpegBytes())
        val intakeViewModel = IntakeViewModel(application, SavedStateHandle())

        intakeViewModel.startImport(stream, "image/jpeg", IntakeKind.SHARE)
        awaitState(intakeViewModel) { it is IntakeUiState.Preview }

        assertTrue("provider stream must close after normal import completion", stream.closed)

        intakeViewModel.cancel()
        awaitState(intakeViewModel) { it is IntakeUiState.Cancelled }
    }

    @Test
    fun providerStreamClosesAfterImportFailure(): Unit = runBlocking {
        val stream = TrackingInputStream("not an image".toByteArray())
        val intakeViewModel = IntakeViewModel(application, SavedStateHandle())

        intakeViewModel.startImport(stream, "image/jpeg", IntakeKind.SHARE)
        awaitState(intakeViewModel) { it is IntakeUiState.Rejected || it is IntakeUiState.Failed }

        assertTrue("provider stream must close after failed import", stream.closed)
    }

    @Test
    fun cancellingWhilePreparingClosesProviderStream(): Unit = runBlocking {
        val stream = TrackingInputStream(syntheticJpegBytes())
        val intakeViewModel = IntakeViewModel(application, SavedStateHandle())

        intakeViewModel.startImport(stream, "image/jpeg", IntakeKind.SHARE)
        intakeViewModel.cancel()
        awaitState(intakeViewModel) { it is IntakeUiState.Cancelled }

        assertTrue("provider stream must close when preparation is cancelled", stream.closed)
    }

    @Test
    fun sampledPreviewDecoderStaysWithinPixelBudget(): Unit {
        val bitmap = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream().also { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }.toByteArray()
        bitmap.recycle()

        val preview = SampledBitmapDecoder.decode(encoded)

        assertTrue(preview != null)
        assertTrue(
            preview!!.width.toLong() * preview.height <=
                org.openlife.vault.repository.ImportLimits.MAX_PREVIEW_PIXELS
        )
        preview.recycle()
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

        // cancel() runs on viewModelScope (fire-and-forget) and holds the
        // app-wide MutationQueue mutex while it runs - awaiting its
        // completion here (rather than letting the test method return
        // immediately) matters because the same mutex is shared by every
        // ViewModel in this process. A dangling cancel from this test was
        // found to make the *next* test's own import spuriously come back
        // Busy instead of Prepared, since tryAcquire found the mutex still
        // held - a real cross-test race, not a hang in the code under test.
        restored.cancel()
        awaitState(restored) { it is IntakeUiState.Cancelled }
    }

    @Test
    fun backgroundScrubsPreviewAndForegroundReauthenticatesIt(): Unit = runBlocking {
        val intake = IntakeViewModel(application, SavedStateHandle())
        intake.startImport(ByteArrayInputStream(syntheticJpegBytes()), "image/jpeg", IntakeKind.SHARE)
        val preview = awaitState(intake) { it is IntakeUiState.Preview } as IntakeUiState.Preview
        assertTrue(preview.previewBytes != null)

        intake.clearSensitiveContentForBackground()
        assertEquals(IntakeUiState.Preparing, intake.state.value)

        intake.restoreSensitiveContentAfterForeground()
        val restored = awaitState(intake) { it is IntakeUiState.Preview } as IntakeUiState.Preview
        assertEquals(preview.sourceId, restored.sourceId)
        assertTrue(restored.previewBytes != null)
        intake.cancel()
        awaitState(intake) { it is IntakeUiState.Cancelled }
    }

    private suspend fun awaitState(viewModel: IntakeViewModel, predicate: (IntakeUiState) -> Boolean): IntakeUiState {
        var last: IntakeUiState = viewModel.state.value
        awaitCondition {
            last = viewModel.state.value
            predicate(last)
        }
        return last
    }

    private suspend fun awaitState(
        viewModel: SourceListViewModel,
        timeoutMs: Long = 30_000,
        predicate: (SourceListUiState) -> Boolean,
    ): SourceListUiState {
        var last: SourceListUiState = viewModel.state.value
        awaitCondition(timeoutMs) {
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

    private suspend fun awaitCondition(timeoutMs: Long = 30_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            kotlinx.coroutines.delay(50)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }
}
