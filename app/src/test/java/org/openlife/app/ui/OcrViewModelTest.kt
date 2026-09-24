package org.openlife.app.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.model.Orientation
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRevision
import org.openlife.vault.ocr.OcrRevisionState
import org.openlife.vault.ocr.OcrRunResult
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.ocr.OcrSpanView
import org.openlife.vault.ocr.OcrUserRevision
import org.openlife.vault.ocr.OcrView
import java.util.UUID

/**
 * P2-01-R2/R4 (plan name kept; the logic lives in [OcrPresenter], which the
 * ViewModel wraps): OCR state derives from the persisted view, the presenter
 * holds no text once cleared or unobserved, and a fresh observer repopulates
 * from the database.
 */
class OcrViewModelTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val backend = FakeBackend()
    private val presenter = OcrPresenter(backend, scope)
    private val sourceId = UUID.randomUUID()

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun clearTransientDropsTextAndFlowRepopulates() = runBlocking {
        backend.view.value = readyView("Appointment 14 October", correction = "Appointment 15 October")
        val first = presenter.state(sourceId)
        val collector = scope.launch { first.collect {} }
        assertTrue("persisted text is shown: ${first.value}", first.value is OcrUiState.Ready)
        val ready = first.value as OcrUiState.Ready
        assertEquals("Appointment 14 October", ready.spans.single().text)
        assertEquals("Appointment 15 October", ready.corrections[ready.spans.single().id])
        assertEquals(OcrReviewState.ACCEPTED, ready.reviewState)

        presenter.clearTransient()

        assertFalse("no Ready value retained after clearTransient", first.value is OcrUiState.Ready)
        val second = presenter.state(sourceId)
        val collector2 = scope.launch { second.collect {} }
        assertTrue("a fresh observer repopulates from the database", second.value is OcrUiState.Ready)
        assertEquals(2, backend.observations)

        collector.cancel()
        collector2.cancel()
        assertFalse("nothing is retained once nobody observes", second.value is OcrUiState.Ready)
    }

    @Test
    fun startsLoadingNotIdleUntilThePersistedStateIsKnown() {
        val state = presenter.state(sourceId)
        val collector = scope.launch { state.collect {} }

        assertEquals("never offer Extract before the database answered", OcrUiState.Loading, state.value)
        backend.view.value = null
        assertEquals(OcrUiState.Idle, state.value)
        collector.cancel()
    }

    @Test
    fun runningMarkerShowsWhileARunIsActiveThenThePersistedResult() {
        val state = presenter.state(sourceId)
        val collector = scope.launch { state.collect {} }

        presenter.run(sourceId)
        assertTrue(state.value is OcrUiState.Running)

        backend.view.value = readyView("done")
        backend.finishRun.complete(OcrRunResult.Completed(backend.revisionId, emptyList()))
        assertTrue(state.value is OcrUiState.Ready)
        collector.cancel()
    }

    private fun readyView(text: String, correction: String? = null): OcrView {
        val revision = revision(backend.revisionId, OcrRevisionState.READY, OcrReviewState.ACCEPTED)
        val span = OcrSpan(UUID.randomUUID(), revision.id, 0, text, null, OcrCoordinateSystem.SOURCE_PIXELS, null)
        val userRevision = correction?.let { OcrUserRevision(UUID.randomUUID(), revision.id, span.id, it, 2L) }
        return OcrView(revision, listOf(OcrSpanView(span, userRevision)), null, emptyList())
    }

    private fun revision(id: UUID, state: OcrRevisionState, review: OcrReviewState) = OcrRevision(
        id = id,
        sourceId = sourceId,
        state = state,
        engineId = "test-engine",
        modelVersion = "1",
        orientation = Orientation.NORMAL,
        sourceDigest = ByteArray(32) { 1 },
        startedAt = 1L,
        extractedAt = 2L,
        reviewState = review,
        failureReason = null,
        charCount = 4,
        spanCount = 1,
    )

    /** A database stand-in holding back its first emission until a value is set. */
    private class ViewSource {
        val flow = MutableStateFlow<Any?>(PENDING)
        var value: OcrView?
            get() = flow.value as? OcrView
            set(value) {
                flow.value = value
            }

        companion object {
            val PENDING = Any()
        }
    }

    private class FakeBackend : OcrBackend {
        val view = ViewSource()
        val revisionId: UUID = UUID.randomUUID()
        var observations = 0
        val finishRun = CompletableDeferred<OcrRunResult>()

        override fun observe(sourceId: UUID): Flow<OcrView?> = kotlinx.coroutines.flow.flow {
            view.flow.collect { if (it !== ViewSource.PENDING) emit(it as OcrView?) }
        }.onStart { observations++ }

        override suspend fun run(sourceId: UUID): OcrRunResult = finishRun.await()

        override suspend fun correct(revisionId: UUID, spanId: UUID?, correctedText: String) = Unit

        override suspend fun review(revisionId: UUID, reviewState: OcrReviewState) = Unit
    }
}
