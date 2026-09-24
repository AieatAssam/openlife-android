package org.openlife.vault.storage

import org.junit.Assert.assertFalse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.security.KeyStoreException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.crypto.Envelope
import org.openlife.vault.crypto.EnvelopeAuthenticationException
import org.openlife.vault.crypto.EnvelopeDomain
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.storage.VaultUnavailableCause

/**
 * Instrumented because Android Keystore and SQLCipher's native library both
 * require a real device/emulator runtime — a mocked encryption layer cannot
 * prove this behaviour (design §13). Covers docs/capabilities/C0.md C0-R17,
 * C0-R18, and the C0-12 test row: fresh bootstrap, restart reusing the same
 * key, an interrupted-bootstrap retry, and a database-without-a-usable-key
 * state that must never trigger a silent replacement.
 *
 * Each test uses its own Keystore alias (a random UUID) so tests cannot
 * interfere with each other's wrapping key, and cleans up both the alias
 * and the vault directory afterwards.
 */
@RunWith(AndroidJUnit4::class)
class VaultBootstrapperTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var paths: VaultPaths
    private lateinit var wrapper: KeystoreWrapper
    private lateinit var alias: String

    @Before
    fun setUp() {
        alias = "test.${UUID.randomUUID()}"
        wrapper = KeystoreWrapper(alias)
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
    fun freshBootstrapCreatesKeyFileAndReturnsAUsableSecret() {
        assertTrue(!paths.databaseKeyFile.exists())

        val result = VaultBootstrapper.bootstrap(paths, wrapper)

        assertTrue(result is VaultBootstrapResult.Ready)
        assertTrue(paths.databaseKeyFile.exists())
    }

    @Test
    fun restartReusesTheSameSecretAndPersistedDataSurvives(): Unit = runBlocking {
        val first = VaultBootstrapper.bootstrap(paths, wrapper) as VaultBootstrapResult.Ready
        val db = OpenLifeDatabaseFactory.create(context, paths, first.databaseSecret)
        val row = SourceEntity(
            id = UUID.randomUUID().toString(),
            state = "STAGED",
            importedAt = 1_700_000_000_000L,
            intakeKind = "SHARE",
            mimeType = null,
            byteCount = null,
            sha256 = null,
            width = null,
            height = null,
            orientation = null,
            wrappedDek = null,
            artefactVersion = null,
        )
        db.sourceDao().insert(row)
        db.close()

        // Simulate a process restart: fresh KeystoreWrapper/database objects
        // over the same on-disk files and the same Keystore alias.
        val second = VaultBootstrapper.bootstrap(paths, KeystoreWrapper(alias)) as VaultBootstrapResult.Ready
        assertArrayEquals(first.databaseSecret, second.databaseSecret)

        val reopened = OpenLifeDatabaseFactory.create(context, paths, second.databaseSecret)
        val reread = reopened.sourceDao().findById(row.id)
        assertEquals(row, reread)
        reopened.close()
    }

    @Test
    fun interruptedFreshBootstrapRetriesUsingTheExistingWrapper() {
        // Simulates a process kill after the key file was written+synced but
        // before the database was ever opened (design §10).
        val firstAttempt = VaultBootstrapper.bootstrap(paths, wrapper) as VaultBootstrapResult.Ready
        assertTrue(paths.databaseKeyFile.exists())
        assertTrue(!paths.databaseFile.exists())

        val retry = VaultBootstrapper.bootstrap(paths, wrapper)

        assertTrue(retry is VaultBootstrapResult.Ready)
        assertArrayEquals(firstAttempt.databaseSecret, (retry as VaultBootstrapResult.Ready).databaseSecret)
    }

    @Test
    fun databaseExistsWithoutAUsableKeyFileIsUnavailableNotReplaced() {
        val ready = VaultBootstrapper.bootstrap(paths, wrapper) as VaultBootstrapResult.Ready
        val db = OpenLifeDatabaseFactory.create(context, paths, ready.databaseSecret)
        db.openHelper.writableDatabase // force actual file creation
        db.close()
        assertTrue(paths.databaseFile.exists())

        // The key file is gone (e.g. lost, deleted, or from a different
        // installation's Keystore) but the database file remains.
        paths.databaseKeyFile.delete()

        val sizeBefore = paths.databaseFile.length()
        val result = VaultBootstrapper.bootstrap(paths, KeystoreWrapper(alias))

        assertTrue(result is VaultBootstrapResult.Unavailable)
        // Nothing was deleted or replaced: the database file is untouched
        // and no new key file was silently created.
        assertTrue(paths.databaseFile.exists())
        assertEquals(sizeBefore, paths.databaseFile.length())
        assertTrue(!paths.databaseKeyFile.exists())
    }

    @Test
    fun corruptKeyFileIsUnavailableNotReplaced() {
        paths.ensureDirectoriesExist()
        paths.databaseKeyFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        val result = VaultBootstrapper.bootstrap(paths, wrapper)

        assertTrue(result is VaultBootstrapResult.Unavailable)
        // The corrupt file is left in place for diagnosis, not deleted or
        // silently overwritten with a fresh key (design §11: "Never infer
        // that an unreadable database is empty and delete its files").
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), paths.databaseKeyFile.readBytes())
    }

    @Test
    fun eachFailureModeReportsItsTypedCause() {
        paths.ensureDirectoriesExist()
        paths.databaseKeyFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        val corrupt = VaultBootstrapper.bootstrap(paths, wrapper) as VaultBootstrapResult.Unavailable
        assertEquals(VaultUnavailableCause.KEY_FILE_CORRUPT, corrupt.cause)

        paths.databaseKeyFile.delete()
        paths.databaseFile.writeBytes(byteArrayOf(7))
        val missingKey = VaultBootstrapper.bootstrap(paths, wrapper) as VaultBootstrapResult.Unavailable
        assertEquals(VaultUnavailableCause.DATABASE_WITHOUT_KEY, missingKey.cause)

        paths.databaseFile.delete()
        val envelope = wrapper.wrap(ByteArray(32), EnvelopeDomain.DATABASE_SECRET)
        paths.databaseKeyFile.writeBytes(EnvelopeCodec.encode(envelope))
        val badAlias = "test.${UUID.randomUUID()}"
        val badWrapper = KeystoreWrapper(badAlias)
        try {
            badWrapper.wrap(ByteArray(32), EnvelopeDomain.DATABASE_SECRET)
            val unwrapFailed = VaultBootstrapper.bootstrap(paths, badWrapper) as VaultBootstrapResult.Unavailable
            assertEquals(VaultUnavailableCause.KEY_UNWRAP_FAILED, unwrapFailed.cause)
        } finally {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(badAlias)) deleteEntry(badAlias)
            }
        }

        val temporary = object : KeystoreWrapper(alias) {
            override fun unwrap(envelope: Envelope, domain: EnvelopeDomain, sourceId: UUID?): ByteArray {
                throw EnvelopeAuthenticationException(KeyStoreException("temporarily unavailable"))
            }
        }
        val temporaryFailure = VaultBootstrapper.bootstrap(paths, temporary) as VaultBootstrapResult.Unavailable
        assertEquals(VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE, temporaryFailure.cause)

        paths.databaseKeyFile.delete()
        paths.databaseKeyFile.mkdirs()
        val storageFailure = VaultBootstrapper.bootstrap(paths, wrapper) as VaultBootstrapResult.Unavailable
        assertEquals(VaultUnavailableCause.STORAGE_IO_ERROR, storageFailure.cause)
        paths.databaseKeyFile.deleteRecursively()

        paths.resetMarkerFile.writeBytes(byteArrayOf(1))
        val incompleteReset = VaultBootstrapper.bootstrap(paths, wrapper) as VaultBootstrapResult.Unavailable
        assertEquals(VaultUnavailableCause.RESET_INCOMPLETE, incompleteReset.cause)
    }

    /** P1-14-R2: a key file whose wrapping key has vanished is unrecoverable; no key is created. */
    @Test
    fun missingAliasWithExistingKeyFileIsUnrecoverableAndCreatesNoKey() {
        assertTrue(VaultBootstrapper.bootstrap(paths, wrapper) is VaultBootstrapResult.Ready)
        java.security.KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }

        val result = VaultBootstrapper.bootstrap(paths, KeystoreWrapper(alias))

        assertEquals(VaultBootstrapResult.Unavailable(VaultUnavailableCause.KEY_UNWRAP_FAILED), result)
        assertFalse("bootstrap must not generate a replacement key", KeystoreWrapper(alias).hasWrappingKey())
        assertTrue("the key file is kept", paths.databaseKeyFile.exists())
    }
}
