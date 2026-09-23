package org.openlife.vault.storage

import android.content.Context
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Opens [OpenLifeDatabase] through Room's classic (2.x) SQLiteOpenHelper
 * factory API, backed by `net.zetetic:sqlcipher-android`'s
 * `SupportOpenHelperFactory` rather than the platform's plaintext SQLite —
 * see docs/decisions/0001-c0-defaults.md item 3 for why this integration
 * path (not the Room 3 driver API, not the deprecated
 * `android-database-sqlcipher` package) was chosen.
 *
 * `net.zetetic.database.sqlcipher.SQLiteConnection.nativeOpen` requires
 * `libsqlcipher.so` (bundled in the AAR's `jni/<abi>/` directories) to be
 * loaded first — confirmed the hard way, by an instrumented-test
 * `UnsatisfiedLinkError`, after an earlier static-analysis pass wrongly
 * concluded no explicit load was needed because there is no public
 * `loadLibs` entry point in this artifact (unlike the older
 * `android-database-sqlcipher`). There genuinely is no automatic loader
 * anywhere in the bundled classes; the caller has to load it, which
 * [System.loadLibrary] does here before every open. Repeated calls with an
 * already-loaded library name are a documented no-op on Android, so this is
 * safe to run on every [create] rather than only once per process.
 *
 * No fallback to plaintext exists on any path, and
 * [androidx.room.RoomDatabase.Builder.fallbackToDestructiveMigration] is
 * never called — an incompatible future schema change must ship a real
 * `Migration`, per design §10 ("destructive Room migration is prohibited").
 */
object OpenLifeDatabaseFactory {
    fun create(context: Context, paths: VaultPaths, databaseSecret: ByteArray): OpenLifeDatabase {
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(context, OpenLifeDatabase::class.java, paths.databaseFile.absolutePath)
            .openHelperFactory(SupportOpenHelperFactory(databaseSecret))
            .addMigrations(OpenLifeDatabase.MIGRATION_1_2)
            .addCallback(OpenLifeDatabase.readyInvariantCallback)
            .build()
    }

    /** Closes Room without exposing the RoomDatabase dependency to the app module. */
    fun close(database: OpenLifeDatabase) {
        database.close()
    }
}
