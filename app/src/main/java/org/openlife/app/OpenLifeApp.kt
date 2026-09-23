package org.openlife.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.database.sqlite.SQLiteException
import android.os.StrictMode
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openlife.app.R
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.ocr.MlKitOcrEngine
import org.openlife.vault.ocr.OcrEngineRegistry
import org.openlife.vault.repository.AndroidBitmapSampler
import org.openlife.vault.repository.DeletionRepository
import org.openlife.vault.repository.ImportRepository
import org.openlife.vault.repository.MutationQueue
import org.openlife.vault.repository.OcrRepository
import org.openlife.vault.repository.RecoveryRepository
import org.openlife.vault.repository.SourceViewRepository
import org.openlife.vault.repository.VaultResetRepository
import org.openlife.vault.repository.VaultResetResult
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.OpenLifeDatabaseFactory
import org.openlife.vault.storage.VaultBootstrapResult
import org.openlife.vault.storage.VaultBootstrapper
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.VaultUnavailableCause
import org.openlife.vault.storage.retryable
import java.io.IOException

/**
 * Composition root. No dependency-injection framework is used for C0
 * (openlife-design-v0.2.md §7) — dependencies are constructed explicitly
 * here. Vault access is lazy and off the main thread: [vault] does the
 * Keystore/SQLCipher bootstrap and one startup-recovery pass on first call,
 * from whichever caller's coroutine invokes it (always `Dispatchers.IO`
 * here, never the caller's own dispatcher). A [Mutex] guards against two
 * callers racing bootstrap; only non-retryable failures are cached.
 */
class OpenLifeApp : Application() {

    private val paths by lazy { VaultPaths(this) }
    private val keystoreWrapper by lazy { KeystoreWrapper() }
    private val mutationQueue = MutationQueue()
    private val vaultInitLock = Mutex()
    private var cachedAccess: VaultAccess? = null
    private var cachedDatabase: OpenLifeDatabase? = null
    private val sensitiveContentClearers = mutableSetOf<() -> Unit>()
    private val preResetCallbacks = mutableSetOf<() -> Unit>()
    private var bootstrapOverrideForTest: ((VaultPaths, KeystoreWrapper) -> VaultBootstrapResult)? = null

    override fun onCreate() {
        super.onCreate()
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
            val access = when (val result = bootstrap(paths, keystoreWrapper)) {
                is VaultBootstrapResult.Ready -> {
                    try {
                        val database = OpenLifeDatabaseFactory.create(this@OpenLifeApp, paths, result.databaseSecret)
                        cachedDatabase = database
                        val report = RecoveryRepository(paths, database, keystoreWrapper, mutationQueue).recover()
                        if (BuildConfig.DEBUG) {
                            android.util.Log.i("OpenLifeRecovery", report.toString())
                        }
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
                                engineRegistry = OcrEngineRegistry(listOf(MlKitOcrEngine()), "mlkit-latin"),
                                mutationQueue = mutationQueue,
                            ),
                            lastRecovery = report,
                        )
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

                is VaultBootstrapResult.Unavailable -> VaultAccess.Unavailable(result.cause)
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

    suspend fun retryVault() = withContext(Dispatchers.IO) {
        vaultInitLock.withLock {
            if (cachedAccess is VaultAccess.Unavailable) cachedAccess = null
        }
    }

    suspend fun resetVault(): VaultResetResult = withContext(Dispatchers.IO) {
        vaultInitLock.withLock {
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
            } else if (paths.resetMarkerFile.exists()) {
                cachedAccess = VaultAccess.Unavailable(VaultUnavailableCause.RESET_INCOMPLETE)
                cachedDatabase = null
            }
            result
        }
    }

    internal fun setBootstrapOverrideForTest(override: ((VaultPaths, KeystoreWrapper) -> VaultBootstrapResult)?) {
        cachedDatabase?.let(OpenLifeDatabaseFactory::close)
        cachedDatabase = null
        cachedAccess = null
        bootstrapOverrideForTest = override
    }

    private fun classifyBootstrapFailure(exception: Exception): VaultUnavailableCause =
        if (exception is IOException || exception is android.database.sqlite.SQLiteFullException) {
            VaultUnavailableCause.STORAGE_IO_ERROR
        } else {
            VaultUnavailableCause.DATABASE_OPEN_FAILED
        }

    companion object {
        private const val APP_LOCK_PREFERENCES = "app_lock"
    }
}

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
        if (counts.isEmpty()) return context.getString(org.openlife.app.R.string.about_vault_diagnostics_empty)
        return counts.entries.joinToString(", ") { (cause, count) -> "$cause: $count" }
    }
}

internal object SensitiveContentTrimPolicy {
    @Suppress("FunctionExpressionBody")
    fun shouldClear(level: Int): Boolean {
        return level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
    }
}
