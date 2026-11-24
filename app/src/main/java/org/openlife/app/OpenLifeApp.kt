package org.openlife.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.database.sqlite.SQLiteException
import android.icu.text.ListFormatter
import android.os.StrictMode
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openlife.app.R
import org.openlife.app.lock.AppLockController
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.ocr.MlKitOcrEngine
import org.openlife.vault.ocr.OcrEngineRegistry
import org.openlife.vault.ocr.TessdataInstaller
import org.openlife.vault.ocr.TesseractOcrEngine
import org.openlife.vault.repository.AndroidBitmapSampler
import org.openlife.vault.repository.DeletionRepository
import org.openlife.vault.repository.ImportRepository
import org.openlife.vault.repository.MutationQueue
import org.openlife.vault.repository.OcrRepository
import org.openlife.vault.repository.RecoveryReport
import org.openlife.vault.repository.RecoveryRepository
import org.openlife.vault.repository.SourceViewRepository
import org.openlife.vault.repository.VaultResetRepository
import org.openlife.vault.repository.VaultResetResult
import org.openlife.vault.repository.VerifyReport
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.OpenLifeDatabaseFactory
import org.openlife.vault.storage.VaultBootstrapResult
import org.openlife.vault.storage.VaultBootstrapper
import org.openlife.vault.storage.VaultOpenResult
import org.openlife.vault.storage.VaultOpener
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.VaultUnavailableCause
import org.openlife.vault.storage.retryable
import java.io.File
import java.io.IOException

/**
 * Composition root. No dependency-injection framework is used for C0
 * (openlife-design-v0.2.md §7) — dependencies are constructed explicitly
 * here. Vault access is lazy and off the main thread: [vault] opens the
 * vault through [VaultOpener] (bootstrap, SQLCipher open and migration as
 * one typed result) and runs one shallow startup-recovery pass on first call,
 * from whichever caller's coroutine invokes it (always `Dispatchers.IO`
 * here, never the caller's own dispatcher). A [Mutex] guards against two
 * callers racing bootstrap; only non-retryable failures are cached.
 */
class OpenLifeApp : Application() {

    private val paths by lazy { VaultPaths(this) }
    private val keystoreWrapper by lazy { KeystoreWrapper() }
    private val mutationQueue = MutationQueue()
    private val vaultInitLock = Mutex()

    private val mutableReadyVault = MutableStateFlow<VaultAccess.Ready?>(null)

    /**
     * The vault while it is Ready, else null (P1-19-R16). Follows late opens,
     * retries and resets, so observers are not tied to the vault that existed
     * when they started. Collecting it does not open the vault; [vault] does.
     */
    val readyVault: StateFlow<VaultAccess.Ready?> = mutableReadyVault

    // Read outside vaultInitLock by importHousekeeping's slot lookup, so publish it safely.
    @Volatile
    private var cachedAccess: VaultAccess? = null
        set(value) {
            field = value
            mutableReadyVault.value = value as? VaultAccess.Ready
        }

    private var cachedDatabase: OpenLifeDatabase? = null
    private val sensitiveContentClearers = mutableSetOf<() -> Unit>()
    private val preResetCallbacks = mutableSetOf<() -> Unit>()
    private var bootstrapOverrideForTest: ((VaultPaths, KeystoreWrapper) -> VaultBootstrapResult)? = null

    override fun onCreate() {
        super.onCreate()
        appLock.start()
        if (BuildConfig.DEBUG) {
            // P1-13-R3: surface main-thread disk and network access during
            // development. Logging only; instrumented tests enforce it with
            // StrictModeRule. Release builds never install a policy.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build(),
            )
        }
    }

    @Synchronized
    fun registerSensitiveContentClearer(clearer: () -> Unit): () -> Unit {
        sensitiveContentClearers += clearer
        return { unregisterSensitiveContentClearer(clearer) }
    }

    @Synchronized
    private fun unregisterSensitiveContentClearer(clearer: () -> Unit) {
        sensitiveContentClearers -= clearer
    }

    @Synchronized
    fun registerPreResetCallback(callback: () -> Unit): () -> Unit {
        preResetCallbacks += callback
        return { synchronized(this) { preResetCallbacks -= callback } }
    }

    /**
     * Saved originals next to a database with no rows are never shown as an
     * empty vault (P1-18 review R-08): the database is closed and the vault
     * reports DATABASE_MISSING.
     */
    private fun accessAfterRecovery(
        database: OpenLifeDatabase,
        recoveryRepository: RecoveryRepository,
        report: RecoveryReport,
    ): VaultAccess = if (report.keptBlobsWithoutRows > 0) {
        OpenLifeDatabaseFactory.close(database)
        cachedDatabase = null
        VaultAccess.Unavailable(VaultUnavailableCause.DATABASE_MISSING)
    } else {
        VaultAccess.Ready(
            importRepository = ImportRepository(
                paths = paths,
                database = database,
                keystoreWrapper = keystoreWrapper,
                bitmapSampler = AndroidBitmapSampler(),
                mutationQueue = mutationQueue,
            ),
            deletionRepository = DeletionRepository(paths, database, mutationQueue),
            viewRepository = SourceViewRepository(paths, database, keystoreWrapper, mutationQueue),
            ocrRepository = OcrRepository(
                paths = paths,
                database = database,
                keystoreWrapper = keystoreWrapper,
                engineRegistry = OcrEngineRegistry(
                    ocrEngines(this@OpenLifeApp),
                    SELECTED_OCR_ENGINE,
                ),
                mutationQueue = mutationQueue,
            ),
            lastRecovery = report,
            recoveryRepository = recoveryRepository,
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!SensitiveContentTrimPolicy.shouldClear(level)) return
        val clearers = synchronized(this) { sensitiveContentClearers.toList() }
        clearers.forEach { it() }
    }

    suspend fun vault(): VaultAccess = withContext(Dispatchers.IO) {
        vaultInitLock.withLock {
            cachedAccess?.let { access ->
                if (access !is VaultAccess.Unavailable || !access.cause.retryable) {
                    return@withContext access
                }
            }

            val bootstrap = bootstrapOverrideForTest ?: VaultBootstrapper::bootstrap
            val opened = VaultOpener.open(this@OpenLifeApp, paths, keystoreWrapper, bootstrap = bootstrap)
            val access = when (opened) {
                is VaultOpenResult.Ready -> {
                    val database = opened.database
                    cachedDatabase = database
                    try {
                        val recoveryRepository = RecoveryRepository(paths, database, keystoreWrapper, mutationQueue)
                        val report = recoveryRepository.recover()
                        if (BuildConfig.DEBUG) {
                            android.util.Log.i("OpenLifeRecovery", report.toString())
                        }
                        accessAfterRecovery(database, recoveryRepository, report)
                    } catch (exception: IOException) {
                        cachedDatabase?.let(OpenLifeDatabaseFactory::close)
                        cachedDatabase = null
                        VaultAccess.Unavailable(classifyBootstrapFailure(exception))
                    } catch (exception: SQLiteException) {
                        cachedDatabase?.let(OpenLifeDatabaseFactory::close)
                        cachedDatabase = null
                        VaultAccess.Unavailable(classifyBootstrapFailure(exception))
                    } catch (exception: IllegalStateException) {
                        cachedDatabase?.let(OpenLifeDatabaseFactory::close)
                        cachedDatabase = null
                        VaultAccess.Unavailable(classifyBootstrapFailure(exception))
                    } catch (exception: SecurityException) {
                        cachedDatabase?.let(OpenLifeDatabaseFactory::close)
                        cachedDatabase = null
                        VaultAccess.Unavailable(classifyBootstrapFailure(exception))
                    }
                }

                is VaultOpenResult.Unavailable -> VaultAccess.Unavailable(opened.cause)
            }

            if (access !is VaultAccess.Unavailable || !access.cause.retryable) {
                cachedAccess = access
            } else {
                cachedAccess = null
            }
            if (access is VaultAccess.Unavailable) VaultFailureDiagnostics.record(this@OpenLifeApp, access.cause)
            access
        }
    }

    /** The optional app lock (P1-07); every content read awaits [AppLockController.awaitContentAccess]. */
    val appLock by lazy { AppLockController(this, importHousekeeping.appScope) }

    /** One-shot proof of OpenLife's own Photo Picker forwarding (P1-02-R6). */
    val pickerNonce = org.openlife.app.intake.PickerNonce()

    /** One-shot proof that an open-source request came from IntakeActivity's "Open existing" (P1-19-R17). */
    val openSourceNonce = org.openlife.app.intake.PickerNonce()

    /** Import cleanup that must outlive a screen (P1-15, P1-01); owns the application scope. */
    val importHousekeeping = ImportHousekeeping(
        vault = { vault() },
        currentSlot = { (cachedAccess as? VaultAccess.Ready)?.importRepository?.importSlot },
    )

    /** Settings > Verify all items (P1-14-R3); null when the vault is unavailable. */
    suspend fun verifyAllItems(): VerifyReport? = (vault() as? VaultAccess.Ready)?.recoveryRepository?.verifyAll()

    suspend fun retryVault() = withContext(Dispatchers.IO) {
        vaultInitLock.withLock {
            if (cachedAccess is VaultAccess.Unavailable) cachedAccess = null
        }
    }

    suspend fun resetVault(): VaultResetResult = withContext(Dispatchers.IO) {
        vaultInitLock.withLock {
            // Reset must not wait out an import and then silently wipe the
            // user's preview: an import in progress makes reset busy.
            if ((cachedAccess as? VaultAccess.Ready)?.importRepository?.importSlot?.isOccupied == true) {
                return@withLock VaultResetResult.BUSY
            }
            val clearers = synchronized(this@OpenLifeApp) { sensitiveContentClearers.toList() }
            val resetters = synchronized(this@OpenLifeApp) { preResetCallbacks.toList() }
            withContext(Dispatchers.Main.immediate) {
                clearers.forEach { it() }
                resetters.forEach { it() }
            }
            val result = VaultResetRepository(
                paths = paths,
                keystoreWrapper = keystoreWrapper,
                mutationQueue = mutationQueue,
                closeDatabase = {
                    cachedDatabase?.let(OpenLifeDatabaseFactory::close)
                    cachedDatabase = null
                },
            ).resetVault()
            if (result == VaultResetResult.COMPLETED) {
                cachedAccess = null
                getSharedPreferences(APP_LOCK_PREFERENCES, MODE_PRIVATE).edit(commit = true) { clear() }
                // Reset turns the app lock off (ADR-0005 scope); keep the running process in step.
                appLock.state.onPolicyChanged(org.openlife.app.lock.AppLockPolicy())
            } else if (paths.resetMarkerFile.exists()) {
                cachedAccess = VaultAccess.Unavailable(VaultUnavailableCause.RESET_INCOMPLETE)
                cachedDatabase = null
            }
            result
        }
    }

    @VisibleForTesting
    internal fun setBootstrapOverrideForTest(override: ((VaultPaths, KeystoreWrapper) -> VaultBootstrapResult)?) {
        cachedDatabase?.let(OpenLifeDatabaseFactory::close)
        cachedDatabase = null
        cachedAccess = null
        bootstrapOverrideForTest = override
    }

    companion object {
        private const val APP_LOCK_PREFERENCES = "app_lock"

        /** P2-03-R3 / ADR-0003: the open-source engine is the default; selection is compile-time. */
        private const val SELECTED_OCR_ENGINE = TesseractOcrEngine.ID
    }
}

private fun classifyBootstrapFailure(exception: Exception): VaultUnavailableCause =
    if (exception is IOException || exception is android.database.sqlite.SQLiteFullException) {
        VaultUnavailableCause.STORAGE_IO_ERROR
    } else {
        VaultUnavailableCause.DATABASE_OPEN_FAILED
    }

/** Both engines are registered; P2-05 removes ML Kit once P2-04's evaluation gate passes. */
private fun ocrEngines(context: android.content.Context) = listOf(
    TesseractOcrEngine(
        TessdataInstaller(
            File(context.noBackupFilesDir, "ocr"),
            openAsset = { context.assets.open(TessdataInstaller.ASSET_PATH) },
        ),
    ),
    MlKitOcrEngine(),
)

/** Counts only typed causes; no exception messages, identifiers, or user data are stored. */
object VaultFailureDiagnostics {
    private const val PREFERENCES = "vault_failure_counts"

    fun record(context: android.content.Context, cause: VaultUnavailableCause) {
        val preferences = context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
        preferences.edit { putInt(cause.name, preferences.getInt(cause.name, 0) + 1) }
    }

    fun summary(context: android.content.Context): String {
        val counts = context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE).all
            .filterValues { it is Int && it > 0 }
            .toSortedMap()
        val entries = counts.mapNotNull { (name, count) ->
            val cause = VaultUnavailableCause.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            context.resources.getQuantityString(
                R.plurals.about_vault_diagnostics_entry,
                count as Int,
                context.getString(causeLabel(cause)),
                count,
            )
        }
        if (entries.isEmpty()) return context.getString(R.string.about_vault_diagnostics_empty)
        // P1-19-R20: the locale decides how a list is joined.
        return ListFormatter.getInstance(context.resources.configuration.locales[0]).format(entries)
    }

    private fun causeLabel(cause: VaultUnavailableCause): Int = when (cause) {
        VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE ->
            R.string.vault_cause_keystore_temporarily_unavailable

        VaultUnavailableCause.KEY_FILE_CORRUPT -> R.string.vault_cause_key_file_corrupt

        VaultUnavailableCause.KEY_UNWRAP_FAILED -> R.string.vault_cause_key_unwrap_failed

        VaultUnavailableCause.DATABASE_WITHOUT_KEY -> R.string.vault_cause_database_without_key

        VaultUnavailableCause.DATABASE_OPEN_FAILED -> R.string.vault_cause_database_open_failed

        VaultUnavailableCause.STORAGE_IO_ERROR -> R.string.vault_cause_storage_io_error

        VaultUnavailableCause.RESET_INCOMPLETE -> R.string.vault_cause_reset_incomplete

        VaultUnavailableCause.DATABASE_MISSING -> R.string.vault_cause_database_missing
    }
}

internal object SensitiveContentTrimPolicy {
    @Suppress("FunctionExpressionBody")
    fun shouldClear(level: Int): Boolean {
        return level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
    }
}
