package org.openlife.app

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.repository.AndroidBitmapSampler
import org.openlife.vault.repository.DeletionRepository
import org.openlife.vault.repository.ImportRepository
import org.openlife.vault.repository.MutationQueue
import org.openlife.vault.repository.RecoveryRepository
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.OpenLifeDatabaseFactory
import org.openlife.vault.storage.VaultBootstrapResult
import org.openlife.vault.storage.VaultBootstrapper
import org.openlife.vault.storage.VaultPaths

/**
 * Composition root. No dependency-injection framework is used for C0
 * (openlife-design-v0.2.md §7) — dependencies are constructed explicitly
 * here. Vault access is lazy and off the main thread: [vault] does the
 * Keystore/SQLCipher bootstrap and one startup-recovery pass on first call,
 * from whichever caller's coroutine invokes it (always `Dispatchers.IO`
 * here, never the caller's own dispatcher), and caches the result for the
 * rest of the process's life. A [Mutex] guards against two callers racing
 * that first call.
 */
class OpenLifeApp : Application() {

    private val paths by lazy { VaultPaths(this) }
    private val keystoreWrapper by lazy { KeystoreWrapper() }
    private val mutationQueue = MutationQueue()
    private val vaultInitLock = Mutex()
    private var cachedAccess: VaultAccess? = null
    private var cachedDatabase: OpenLifeDatabase? = null

    suspend fun vault(): VaultAccess = withContext(Dispatchers.IO) {
        vaultInitLock.withLock {
            cachedAccess?.let { return@withContext it }

            val access = when (val result = VaultBootstrapper.bootstrap(paths, keystoreWrapper)) {
                is VaultBootstrapResult.Ready -> {
                    val database = OpenLifeDatabaseFactory.create(this@OpenLifeApp, paths, result.databaseSecret)
                    cachedDatabase = database
                    val report = RecoveryRepository(paths, database, keystoreWrapper, mutationQueue).recover()
                    VaultAccess.Ready(
                        importRepository = ImportRepository(
                            paths = paths,
                            database = database,
                            keystoreWrapper = keystoreWrapper,
                            bitmapSampler = AndroidBitmapSampler(),
                            mutationQueue = mutationQueue,
                        ),
                        deletionRepository = DeletionRepository(paths, database, mutationQueue),
                        lastRecovery = report,
                    )
                }

                is VaultBootstrapResult.Unavailable -> VaultAccess.Unavailable(result.reason)
            }

            cachedAccess = access
            access
        }
    }
}
