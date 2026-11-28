package org.openlife.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openlife.app.SourceStore
import org.openlife.vault.model.UserContentKind
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRunResult
import org.openlife.vault.ocr.OcrView
import org.openlife.vault.repository.DeleteResult
import org.openlife.vault.repository.ReadyReadResult
import java.util.UUID

/** P3-05 review U-03: a viewer that shows an earlier reading makes no write to the OCR store or the source store. */
class ViewerViewModelTest {
    private val sourceId = UUID.randomUUID()
    private val revisionId = UUID.randomUUID()
    private val spanId = UUID.randomUUID()
    private val backend = RecordingOcrBackend()
    private val ocr = OcrPresenter(backend, CoroutineScope(Dispatchers.Unconfined))
    private val sources = RecordingSourceStore()

    private fun writes(readOnly: Boolean) = ViewerWrites(sourceId, readOnly, ocr, sources)

    private fun everyWrite(writes: ViewerWrites) {
        writes.extractText()
        writes.cancelOcr()
        writes.correct(revisionId, spanId, "corrected")
        writes.review(revisionId, OcrReviewState.ACCEPTED)
        writes.rejectSpan(spanId)
        writes.undoRejectSpan(spanId)
        writes.setUserContentKind(UserContentKind.COMMUNICATION)
    }

    @Test
    fun mutationsAreNoOpsWhenReadOnly() = runBlocking {
        everyWrite(writes(readOnly = true))
        assertEquals(emptyList<String>(), backend.calls)
        assertEquals(emptyList<UserContentKind?>(), sources.kinds)
    }

    /** The same calls reach the stores when the viewer is writable, so the no-op above is not vacuous. */
    @Test
    fun mutationsReachTheStoresWhenWritable() = runBlocking {
        everyWrite(writes(readOnly = false))
        assertEquals(
            listOf("run", "correct", "review", "rejectSpan", "undoRejectSpan"),
            backend.calls,
        )
        assertEquals(listOf<UserContentKind?>(UserContentKind.COMMUNICATION), sources.kinds)
    }

    private class RecordingOcrBackend : OcrBackend {
        val calls = mutableListOf<String>()

        override fun observe(sourceId: UUID): Flow<OcrView?> = flowOf(null)

        override suspend fun run(sourceId: UUID): OcrRunResult {
            calls += "run"
            return OcrRunResult.Failed(null, OcrFailureReason.SOURCE_NOT_READY)
        }

        override fun observeReading(sourceId: UUID, revisionId: UUID): Flow<OcrView?> = flowOf(null)

        override suspend fun correct(revisionId: UUID, spanId: UUID?, correctedText: String) {
            calls += "correct"
        }

        override suspend fun review(revisionId: UUID, reviewState: OcrReviewState) {
            calls += "review"
        }

        override suspend fun rejectSpan(spanId: UUID) {
            calls += "rejectSpan"
        }

        override suspend fun undoRejectSpan(spanId: UUID) {
            calls += "undoRejectSpan"
        }
    }

    private class RecordingSourceStore : SourceStore {
        val kinds = mutableListOf<UserContentKind?>()

        override fun observeList(): Flow<SourceListUiState> = flowOf(SourceListUiState.Loaded(emptyList()))

        override fun observeSource(sourceId: UUID): Flow<SourceLookup> = flowOf(SourceLookup.Missing)

        override suspend fun readReadyContent(sourceId: UUID): ReadyReadResult = ReadyReadResult.Unavailable

        override suspend fun loadThumbnail(sourceId: UUID, targetPixels: Long) = null

        override fun delete(sourceId: UUID, onResult: (DeleteResult?) -> Unit) = onResult(null)

        override fun setUserContentKind(sourceId: UUID, kind: UserContentKind?) {
            kinds += kind
        }

        override suspend fun retryVault() = Unit

        override suspend fun finishVaultReset(): Boolean = false

        override suspend fun cleanAbandonedStages() = Unit
    }
}
