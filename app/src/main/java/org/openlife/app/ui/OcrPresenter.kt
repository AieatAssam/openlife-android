package org.openlife.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRunResult
import org.openlife.vault.ocr.OcrView
import java.util.UUID

/** What the OCR presenter needs from the vault; the app wires it to OcrRepository behind the app lock. */
interface OcrBackend {
    fun observe(sourceId: UUID): Flow<OcrView?>

    suspend fun run(sourceId: UUID): OcrRunResult

    suspend fun correct(revisionId: UUID, spanId: UUID?, correctedText: String)

    suspend fun review(revisionId: UUID, reviewState: OcrReviewState)
}

/** P2-01 stub. */
class OcrPresenter(private val backend: OcrBackend, private val scope: CoroutineScope) {
    fun state(sourceId: UUID): StateFlow<OcrUiState> = MutableStateFlow(OcrUiState.Idle)

    fun run(sourceId: UUID) = Unit

    fun cancel(sourceId: UUID) = Unit

    fun clearTransient() = Unit
}
