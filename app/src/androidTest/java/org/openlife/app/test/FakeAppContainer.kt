package org.openlife.app.test

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import org.openlife.app.AppContainer
import org.openlife.app.ContentScrubber
import org.openlife.app.SettingsPort
import org.openlife.app.SourceStore
import org.openlife.app.intake.InternalLinks
import org.openlife.app.lock.AppLockPolicy
import org.openlife.app.lock.LockAvailability
import org.openlife.app.settings.AboutPort
import org.openlife.app.ui.OcrBackend
import org.openlife.app.ui.OcrPresenter
import org.openlife.app.ui.SourceListUiState
import org.openlife.app.ui.SourceLookup
import org.openlife.app.ui.search.SearchBackend
import org.openlife.app.ui.facts.FactsGateway
import org.openlife.vault.model.Source
import org.openlife.vault.model.UserContentKind
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRunResult
import org.openlife.vault.ocr.OcrView
import org.openlife.vault.repository.DeleteResult
import org.openlife.vault.repository.ReadyReadResult
import org.openlife.vault.repository.ResetConfirmation
import org.openlife.vault.repository.VaultResetResult
import org.openlife.vault.repository.VerifyReport
import org.openlife.vault.search.SearchHit
import java.util.UUID

/**
 * P1-20-R1: an in-memory [AppContainer] for hosting the real navigation
 * destinations in Compose tests. Sources are whatever [sourceList] holds; bytes
 * never arrive unless [readContent] provides them; OCR text is [ocrView];
 * search answers every query with [searchHits]. The list shows what [listed]
 * makes of [sourceList] (null: the list never loads); the viewer reads
 * [sourceList] by id, independently. About reads [about] (synthetic by
 * default; pass the production port to test the real identity). Call [close]
 * after the test.
 */
class FakeAppContainer(
    initialSources: List<Source>,
    private val readContent: suspend (UUID) -> ReadyReadResult = { awaitCancellation() },
    private val searchHits: List<SearchHit> = emptyList(),
    private val ocrView: (UUID) -> OcrView? = { null },
    private val readingView: (UUID) -> OcrView? = { null },
    private val listed: (List<Source>) -> SourceListUiState? = { SourceListUiState.Loaded(it) },
    override val about: AboutPort = FakeAboutPort(),
    factsGateway: FactsGateway = FakeFactsGateway(),
) : AppContainer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sourceList = MutableStateFlow(initialSources)

    override val scrubber = ContentScrubber()
    override val internalLinks = InternalLinks()
    override val searchBackend = SearchBackend { flowOf(searchHits) }

    override val facts: FactsGateway = factsGateway

    override val ocr = OcrPresenter(
        object : OcrBackend {
            override fun observe(sourceId: UUID): Flow<OcrView?> = flowOf(ocrView(sourceId))

            override fun observeReading(revisionId: UUID): Flow<OcrView?> = flowOf(readingView(revisionId))

            override suspend fun run(sourceId: UUID): OcrRunResult =
                OcrRunResult.Failed(null, OcrFailureReason.SOURCE_NOT_READY)

            override suspend fun correct(revisionId: UUID, spanId: UUID?, correctedText: String) = Unit

            override suspend fun review(revisionId: UUID, reviewState: OcrReviewState) = Unit

            override suspend fun rejectSpan(spanId: UUID) = Unit

            override suspend fun undoRejectSpan(spanId: UUID) = Unit
        },
        scope,
    )

    override val sources: SourceStore = object : SourceStore {
        override fun observeList(): Flow<SourceListUiState> = sourceList.mapNotNull(listed)

        override fun observeSource(sourceId: UUID): Flow<SourceLookup> = sourceList.map { list ->
            list.find { it.id == sourceId }?.let { SourceLookup.Found(it) } ?: SourceLookup.Missing
        }

        override suspend fun readReadyContent(sourceId: UUID): ReadyReadResult = readContent(sourceId)

        override suspend fun loadThumbnail(sourceId: UUID, targetPixels: Long): Bitmap? = null

        override fun delete(sourceId: UUID, onResult: (DeleteResult?) -> Unit) {
            sourceList.value = sourceList.value.filterNot { it.id == sourceId }
            onResult(DeleteResult.Deleted)
        }

        override fun setUserContentKind(sourceId: UUID, kind: UserContentKind?) = Unit

        override suspend fun retryVault() = Unit

        override suspend fun finishVaultReset(): Boolean = false

        override suspend fun cleanAbandonedStages() = Unit
    }

    override val settings: SettingsPort = object : SettingsPort {
        override val lockPolicy: StateFlow<AppLockPolicy> = MutableStateFlow(AppLockPolicy())

        override fun lockAvailability(): LockAvailability = LockAvailability.AVAILABLE

        override fun updateLockPolicy(policy: AppLockPolicy) = Unit

        override suspend fun resetVault(confirmation: ResetConfirmation): VaultResetResult = VaultResetResult.FAILED

        override suspend fun verifyAll(): VerifyReport? = null
    }

    override fun registerPreReset(callback: () -> Unit): () -> Unit = {}

    fun close() = scope.cancel()
}
