package org.openlife.app

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openlife.app.intake.InternalLinks
import org.openlife.app.lock.AppLockPolicy
import org.openlife.app.lock.LockAvailability
import org.openlife.app.settings.AboutPort
import org.openlife.app.settings.AppAboutPort
import org.openlife.app.ui.OcrBackend
import org.openlife.app.ui.OcrPresenter
import org.openlife.app.ui.SampledBitmapDecoder
import org.openlife.app.ui.SourceListUiState
import org.openlife.app.ui.SourceLookup
import org.openlife.app.ui.forgetSourcesLeavingTheList
import org.openlife.app.ui.search.SearchBackend
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.SourceState
import org.openlife.vault.model.UserContentKind
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRunResult
import org.openlife.vault.ocr.OcrView
import org.openlife.vault.repository.DeleteResult
import org.openlife.vault.repository.ReadyReadResult
import org.openlife.vault.repository.VaultResetResult
import org.openlife.vault.repository.VerifyReport
import org.openlife.vault.search.SearchHit
import java.util.UUID

/**
 * P1-20-R1 (AS-1): what every destination's ViewModel is built from. One per
 * process, created by [OpenLifeApp]. Work that must outlive a screen (OCR
 * runs, deletes, label writes) runs in the container's application scope;
 * screen ViewModels only observe and ask.
 */
interface AppContainer {
    val sources: SourceStore
    val ocr: OcrPresenter
    val searchBackend: SearchBackend
    val scrubber: ContentScrubber
    val internalLinks: InternalLinks
    val settings: SettingsPort

    /** P1-08: build identity, vault security details, diagnostics counts and bundled documents. */
    val about: AboutPort

    /** Runs [callback] before a vault reset; returns the call that unregisters it. */
    fun registerPreReset(callback: () -> Unit): () -> Unit
}

/** Sources by list and by id, behind the vault and the app lock. */
interface SourceStore {
    /** Opens the vault if needed and follows its visible sources. */
    fun observeList(): Flow<SourceListUiState>

    /** P1-20-R2: one source by id, whether or not any list has loaded it. */
    fun observeSource(sourceId: UUID): Flow<SourceLookup>

    suspend fun readReadyContent(sourceId: UUID): ReadyReadResult

    suspend fun loadThumbnail(sourceId: UUID, targetPixels: Long): Bitmap?

    /** Runs in the application scope; [onResult] is called on the main thread, null when the vault is not ready. */
    fun delete(sourceId: UUID, onResult: (DeleteResult?) -> Unit)

    fun setUserContentKind(sourceId: UUID, kind: UserContentKind?)

    suspend fun retryVault()

    suspend fun finishVaultReset(): Boolean

    suspend fun cleanAbandonedStages()
}

/** Settings > app lock, Verify all and Reset (P1-06, P1-07, P1-14). */
interface SettingsPort {
    val lockPolicy: StateFlow<AppLockPolicy>

    fun lockAvailability(): LockAvailability

    fun updateLockPolicy(policy: AppLockPolicy)

    suspend fun resetVault(): VaultResetResult

    suspend fun verifyAll(): VerifyReport?
}

/** The production container. Holds no content itself; the vault and the lock stay in [OpenLifeApp]. */
internal class OpenLifeAppContainer(private val app: OpenLifeApp) : AppContainer {
    /** Main-thread application scope, as the activity-scoped ViewModels had (they used viewModelScope). */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val scrubber = ContentScrubber()
    override val internalLinks = InternalLinks()
    override val sources: SourceStore = VaultSourceStore(app, uiScope)
    override val searchBackend: SearchBackend = AppSearchBackend(app)

    override val ocr: OcrPresenter = appOcrPresenter(app, uiScope).also { presenter ->
        app.registerPreResetCallback(presenter::forgetAll)
        // P2-01-R4: hiding and memory trim drop OCR text; a visible panel re-reads it.
        scrubber.register { presenter.clearTransient() }
    }

    override val settings: SettingsPort = object : SettingsPort {
        override val lockPolicy: StateFlow<AppLockPolicy> get() = app.appLock.policy

        override fun lockAvailability(): LockAvailability = app.appLock.authenticator.availability()

        override fun updateLockPolicy(policy: AppLockPolicy) = app.appLock.updatePolicy(policy)

        override suspend fun resetVault(): VaultResetResult = app.resetVault()

        override suspend fun verifyAll(): VerifyReport? = app.verifyAllItems()
    }

    override val about: AboutPort = AppAboutPort(app)

    override fun registerPreReset(callback: () -> Unit): () -> Unit = app.registerPreResetCallback(callback)

    init {
        // Memory trim and vault reset run the app's clearers; they scrub content but leave a visible screen visible.
        app.registerSensitiveContentClearer { scrubber.scrub(hidden = false) }
    }
}

/**
 * The app's OCR presenter, backed by the vault behind the lock. P2-02-R6 /
 * P1-19-R16: a source leaving the visible list stops its extraction and is
 * forgotten; the tracker opens the vault if nothing has yet and follows
 * whichever vault is Ready, including one that opens later or replaces a
 * reset vault.
 */
internal fun appOcrPresenter(app: OpenLifeApp, scope: CoroutineScope): OcrPresenter =
    OcrPresenter(AppOcrBackend(app), scope).also { presenter ->
        scope.launch {
            app.vault()
            forgetSourcesLeavingTheList(
                app.readyVault.map { access ->
                    access?.viewRepository?.observeVisibleSources()?.map { sources ->
                        sources.mapTo(HashSet()) { it.id }
                    }
                },
                presenter::forget,
            )
        }
    }

private class VaultSourceStore(private val app: OpenLifeApp, private val scope: CoroutineScope) : SourceStore {
    override fun observeList(): Flow<SourceListUiState> = flow {
        when (val access = app.vault()) {
            is VaultAccess.Unavailable -> emit(SourceListUiState.VaultUnavailable(access.cause))

            is VaultAccess.Ready -> emitAll(
                access.viewRepository.observeVisibleSources().map { SourceListUiState.Loaded(it) },
            )
        }
    }

    override fun observeSource(sourceId: UUID): Flow<SourceLookup> = flow {
        when (val access = app.vault()) {
            is VaultAccess.Unavailable -> emit(SourceLookup.Missing)

            is VaultAccess.Ready -> emitAll(
                access.viewRepository.observeVisibleSource(sourceId).map { source ->
                    source?.let { SourceLookup.Found(it) } ?: SourceLookup.Missing
                },
            )
        }
    }

    override suspend fun readReadyContent(sourceId: UUID): ReadyReadResult {
        app.appLock.awaitContentAccess()
        return (app.vault() as? VaultAccess.Ready)?.viewRepository?.readReadyBytes(sourceId)
            ?: ReadyReadResult.Unavailable
    }

    override suspend fun loadThumbnail(sourceId: UUID, targetPixels: Long): Bitmap? {
        app.appLock.awaitContentAccess()
        val access = app.vault() as? VaultAccess.Ready ?: return null
        return withContext(Dispatchers.Default) {
            val source = access.viewRepository.findSource(sourceId)
            val bytes = source?.let { access.viewRepository.loadReadyBytes(sourceId) }
            if (source == null || source.state != SourceState.READY || bytes == null) {
                null
            } else {
                SampledBitmapDecoder.decode(
                    bytes,
                    source.orientation ?: Orientation.NORMAL,
                    targetPixels,
                    Bitmap.Config.RGB_565,
                )
            }
        }
    }

    override fun delete(sourceId: UUID, onResult: (DeleteResult?) -> Unit) {
        scope.launch {
            val access = app.vault() as? VaultAccess.Ready
            onResult(access?.deletionRepository?.deleteSource(sourceId))
        }
    }

    override fun setUserContentKind(sourceId: UUID, kind: UserContentKind?) {
        scope.launch { (app.vault() as? VaultAccess.Ready)?.viewRepository?.setUserContentKind(sourceId, kind) }
    }

    override suspend fun retryVault() = app.retryVault()

    override suspend fun finishVaultReset(): Boolean = app.resetVault() == VaultResetResult.COMPLETED

    override suspend fun cleanAbandonedStages() {
        app.importHousekeeping.cleanAbandonedStages()
    }
}

/** The vault behind the app lock: persisted OCR text is content, read only once unlocked (ADR-0004). */
internal class AppOcrBackend(private val app: OpenLifeApp) : OcrBackend {
    override fun observe(sourceId: UUID): Flow<OcrView?> = flow {
        app.appLock.awaitContentAccess()
        when (val access = app.vault()) {
            is VaultAccess.Ready -> emitAll(access.ocrRepository.observeOcrView(sourceId))
            is VaultAccess.Unavailable -> emit(null)
        }
    }

    override suspend fun run(sourceId: UUID): OcrRunResult {
        app.appLock.awaitContentAccess()
        val access = app.vault() as? VaultAccess.Ready
            ?: return OcrRunResult.Failed(null, OcrFailureReason.SOURCE_NOT_READY)
        return access.ocrRepository.runOcr(sourceId)
    }

    override suspend fun correct(revisionId: UUID, spanId: UUID?, correctedText: String) {
        (app.vault() as? VaultAccess.Ready)?.ocrRepository?.addCorrection(revisionId, spanId, correctedText)
    }

    override suspend fun review(revisionId: UUID, reviewState: OcrReviewState) {
        (app.vault() as? VaultAccess.Ready)?.ocrRepository?.setReviewState(revisionId, reviewState)
    }

    override suspend fun rejectSpan(spanId: UUID) {
        (app.vault() as? VaultAccess.Ready)?.ocrRepository?.rejectSpan(spanId)
    }

    override suspend fun undoRejectSpan(spanId: UUID) {
        (app.vault() as? VaultAccess.Ready)?.ocrRepository?.undoRejectSpan(spanId)
    }
}

/** The vault behind the app lock: search reads content, so it waits for unlock (ADR-0004). */
private class AppSearchBackend(private val app: OpenLifeApp) : SearchBackend {
    override fun search(query: String): Flow<List<SearchHit>> = flow {
        app.appLock.awaitContentAccess()
        when (val access = app.vault()) {
            is VaultAccess.Ready -> emitAll(access.searchRepository.search(query))
            is VaultAccess.Unavailable -> emit(emptyList())
        }
    }
}
