package org.openlife.vault.storage

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.crypto.KeystoreWrapper

/**
 * P1-14-R1/R5/R6: a database that cannot be opened makes the vault
 * unavailable, never crashes the app, and is never deleted, replaced or
 * destructively migrated (design §10).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseOpenFailureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var alias: String
    private lateinit var paths: VaultPaths

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        alias = "test.${UUID.randomUUID()}"
        paths = VaultPaths(context)
        paths.vaultDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        paths.vaultDir.deleteRecursively()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    @Test
    fun garbageDatabaseFileYieldsUnavailableWithoutDeletingAnything() {
        assertTrue(VaultBootstrapper.bootstrap(paths, KeystoreWrapper(alias)) is VaultBootstrapResult.Ready)
        val garbage = ByteArray(4096).also(SecureRandom()::nextBytes)
        paths.databaseFile.writeBytes(garbage)
        val keyFile = paths.databaseKeyFile.readBytes()

        val result = VaultOpener.open(context, paths, KeystoreWrapper(alias))

        assertEquals(VaultOpenResult.Unavailable(VaultUnavailableCause.DATABASE_OPEN_FAILED), result)
        assertArrayEquals("the database file must be untouched", garbage, paths.databaseFile.readBytes())
        assertArrayEquals("the key file must be untouched", keyFile, paths.databaseKeyFile.readBytes())
    }

    @Test
    fun wrongPassphraseYieldsUnavailable() {
        assertTrue(VaultBootstrapper.bootstrap(paths, KeystoreWrapper(alias)) is VaultBootstrapResult.Ready)
        // A database encrypted with some other secret sits where ours should be.
        val other = OpenLifeDatabaseFactory.create(context, paths, ByteArray(32) { 9 })
        runBlocking { other.sourceDao().count() }
        other.close()
        val before = paths.databaseFile.readBytes()

        val result = VaultOpener.open(context, paths, KeystoreWrapper(alias))

        assertEquals(VaultOpenResult.Unavailable(VaultUnavailableCause.DATABASE_OPEN_FAILED), result)
        assertArrayEquals(before, paths.databaseFile.readBytes())
    }

    @Test
    fun newerSchemaVersionYieldsUnavailableNotDestructiveMigration() {
        val opened = VaultOpener.open(context, paths, KeystoreWrapper(alias)) as VaultOpenResult.Ready
        runBlocking { opened.database.sourceDao().count() }
        // As if a newer OpenLife had upgraded the schema and the user then downgraded.
        opened.database.openHelper.writableDatabase.execSQL("PRAGMA user_version = 99")
        opened.database.close()
        val before = paths.databaseFile.readBytes()

        val result = VaultOpener.open(context, paths, KeystoreWrapper(alias))

        assertEquals(VaultOpenResult.Unavailable(VaultUnavailableCause.DATABASE_OPEN_FAILED), result)
        assertArrayEquals("no destructive migration", before, paths.databaseFile.readBytes())
    }

    @Test
    fun migrationThrowingMidwayLeavesPreviousVersionAndRerunsNextOpen() {
        val secret = (VaultBootstrapper.bootstrap(paths, KeystoreWrapper(alias)) as VaultBootstrapResult.Ready)
            .databaseSecret
        val name = "p114-migration-${UUID.randomUUID()}"
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OpenLifeDatabase::class.java,
            emptyList(),
            SupportOpenHelperFactory(secret.copyOf()),
        )
        helper.createDatabase(name, 1).apply {
            execSQL(
                """
                INSERT INTO sources(id, state, importedAt, intakeKind, mimeType, byteCount, sha256, width, height,
                    orientation, wrappedDek, artefactVersion)
                VALUES ('kept', 'READY', 1, 'SHARE', 'image/jpeg', 10, X'0102', 10, 10, 'NORMAL', X'0304', 1)
                """.trimIndent(),
            )
            close()
        }
        val path = context.getDatabasePath(name).absolutePath
        val failing = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                OpenLifeDatabase.MIGRATION_1_2.migrate(db)
                throw IllegalStateException("synthetic failure midway through the migration")
            }
        }
        try {
            val failed = VaultOpener.open(context, paths, KeystoreWrapper(alias), listOf(failing), path)
            assertEquals(VaultOpenResult.Unavailable(VaultUnavailableCause.DATABASE_OPEN_FAILED), failed)

            val retried = VaultOpener.open(context, paths, KeystoreWrapper(alias), databasePath = path)
            assertTrue("the next open re-runs the migration: $retried", retried is VaultOpenResult.Ready)
            val database = (retried as VaultOpenResult.Ready).database
            val row = runBlocking { database.sourceDao().findById("kept") }
            assertEquals("no data lost across the failed migration", "READY", row?.state)
            database.close()
        } finally {
            context.deleteDatabase(name)
        }
    }
}
