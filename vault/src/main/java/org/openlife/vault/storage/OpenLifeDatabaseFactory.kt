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
 * `net.zetetic.database.sqlcipher.SQLiteDatabase` loads its native library
 * from a static initializer (confirmed by inspecting the bundled classes —
 * there is no public `loadLibs` entry point in this artifact, unlike the
 * older `android-database-sqlcipher`), so no explicit library-loading step
 * is needed here.
 *
 * No fallback to plaintext exists on any path, and
 * [androidx.room.RoomDatabase.Builder.fallbackToDestructiveMigration] is
 * never called — an incompatible future schema change must ship a real
 * `Migration`, per design §10 ("destructive Room migration is prohibited").
 */
object OpenLifeDatabaseFactory {
    fun create(context: Context, paths: VaultPaths, databaseSecret: ByteArray): OpenLifeDatabase =
        Room.databaseBuilder(context, OpenLifeDatabase::class.java, paths.databaseFile.absolutePath)
            .openHelperFactory(SupportOpenHelperFactory(databaseSecret))
            .addCallback(OpenLifeDatabase.readyInvariantCallback)
            .build()
}
