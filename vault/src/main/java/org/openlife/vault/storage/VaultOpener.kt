package org.openlife.vault.storage

import android.content.Context
import androidx.room.migration.Migration
import org.openlife.vault.crypto.KeystoreWrapper

/** Outcome of [VaultOpener.open]. */
sealed interface VaultOpenResult {
    class Ready(val database: OpenLifeDatabase) : VaultOpenResult

    data class Unavailable(val cause: VaultUnavailableCause) : VaultOpenResult
}

/** P1-14 stub: bootstrap then open; failures still propagate. */
object VaultOpener {
    fun open(
        context: Context,
        paths: VaultPaths,
        wrapper: KeystoreWrapper,
        migrations: List<Migration> = OpenLifeDatabase.MIGRATIONS,
        databasePath: String = paths.databaseFile.absolutePath,
    ): VaultOpenResult = when (val bootstrap = VaultBootstrapper.bootstrap(paths, wrapper)) {
        is VaultBootstrapResult.Unavailable -> VaultOpenResult.Unavailable(bootstrap.cause)
        is VaultBootstrapResult.Ready -> VaultOpenResult.Ready(
            OpenLifeDatabaseFactory.create(context, paths, bootstrap.databaseSecret, migrations, databasePath),
        )
    }
}
