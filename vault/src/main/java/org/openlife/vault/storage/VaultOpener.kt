package org.openlife.vault.storage

import android.content.Context
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.migration.Migration
import org.openlife.vault.crypto.KeystoreWrapper
import java.io.IOException

/** Outcome of [VaultOpener.open]. */
sealed interface VaultOpenResult {
    /** The database is open, migrated and readable; the caller owns and closes it. */
    class Ready(val database: OpenLifeDatabase) : VaultOpenResult

    /** Nothing on disk was changed: no file deleted or replaced, no key created. */
    data class Unavailable(val cause: VaultUnavailableCause) : VaultOpenResult
}

/**
 * Bootstrap and database open as one step with one result (P1-14-R1).
 *
 * Room opens lazily, so a database that is garbage, encrypted with another
 * secret, newer than this build, or whose migration throws would otherwise
 * fail later, on whichever query happens to run first. [open] forces the
 * open (and any migration) here, so every such failure becomes
 * [VaultOpenResult.Unavailable] instead of a crash. A failed migration runs
 * inside SQLite's upgrade transaction, so it rolls back and the next open
 * tries it again. The database file is never deleted, replaced, or
 * destructively migrated (design §10).
 */
object VaultOpener {
    @Suppress("LongParameterList")
    fun open(
        context: Context,
        paths: VaultPaths,
        wrapper: KeystoreWrapper,
        migrations: List<Migration> = OpenLifeDatabase.MIGRATIONS,
        databasePath: String = paths.databaseFile.absolutePath,
        bootstrap: (VaultPaths, KeystoreWrapper) -> VaultBootstrapResult = VaultBootstrapper::bootstrap,
    ): VaultOpenResult = when (val result = bootstrap(paths, wrapper)) {
        is VaultBootstrapResult.Unavailable -> VaultOpenResult.Unavailable(result.cause)

        is VaultBootstrapResult.Ready -> openDatabase {
            OpenLifeDatabaseFactory.create(context, paths, result.databaseSecret, migrations, databasePath)
        }
    }

    private fun openDatabase(create: () -> OpenLifeDatabase): VaultOpenResult {
        var database: OpenLifeDatabase? = null
        val failure = try {
            database = create()
            // Opening the writable database runs Room's version check and migrations.
            database.openHelper.writableDatabase.query("SELECT count(*) FROM sqlite_master").close()
            return VaultOpenResult.Ready(database)
        } catch (exception: SQLiteException) {
            causeOf(exception)
        } catch (exception: IllegalStateException) {
            causeOf(exception)
        } catch (exception: IOException) {
            causeOf(exception)
        } catch (exception: UnsatisfiedLinkError) {
            causeOf(exception)
        }
        closeQuietly(database)
        return VaultOpenResult.Unavailable(failure)
    }

    private fun causeOf(failure: Throwable): VaultUnavailableCause = when (failure) {
        is IOException, is SQLiteFullException, is SQLiteDiskIOException -> VaultUnavailableCause.STORAGE_IO_ERROR
        else -> VaultUnavailableCause.DATABASE_OPEN_FAILED
    }

    @Suppress("SwallowedException")
    private fun closeQuietly(database: OpenLifeDatabase?) {
        try {
            database?.close()
        } catch (_: SQLiteException) {
            // Already failed to open; there is nothing further to release.
        } catch (_: IllegalStateException) {
            // Same: closing a database that never opened is best effort.
        }
    }
}
