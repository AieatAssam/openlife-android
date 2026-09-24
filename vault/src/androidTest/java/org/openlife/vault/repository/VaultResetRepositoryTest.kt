package org.openlife.vault.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.crypto.EnvelopeDomain
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.storage.VaultBootstrapResult
import org.openlife.vault.storage.VaultBootstrapper
import org.openlife.vault.storage.VaultPaths

@RunWith(AndroidJUnit4::class)
class VaultResetRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var paths: VaultPaths
    private lateinit var wrapper: KeystoreWrapper
    private lateinit var alias: String

    @Before
    fun setUp() {
        alias = "test.reset.${UUID.randomUUID()}"
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
    fun resetRemovesArtefactsDatabaseKeyFileAndKeystoreAliasThenFreshBootstrapSucceeds(): Unit = runBlocking {
        val secret = ByteArray(32) { it.toByte() }
        wrapper.ensureWrappingKey()
        val wrapped = wrapper.wrap(secret, EnvelopeDomain.DATABASE_SECRET)
        paths.ensureDirectoriesExist()
        paths.databaseKeyFile.writeBytes(org.openlife.vault.crypto.EnvelopeCodec.encode(wrapped))
        paths.databaseFile.writeBytes(byteArrayOf(1, 2, 3))
        File(paths.databaseFile.path + "-wal").writeBytes(byteArrayOf(4))
        File(paths.databaseFile.path + "-shm").writeBytes(byteArrayOf(5))
        File(paths.artefactsDir, "${UUID.randomUUID()}.blob").writeBytes(byteArrayOf(6))

        val result = VaultResetRepository(paths, wrapper, MutationQueue()).resetVault()

        assertEquals(VaultResetResult.COMPLETED, result)
        assertFalse(paths.databaseFile.exists())
        assertFalse(File(paths.databaseFile.path + "-wal").exists())
        assertFalse(File(paths.databaseFile.path + "-shm").exists())
        assertFalse(paths.databaseKeyFile.exists())
        assertTrue(paths.artefactsDir.listFiles().orEmpty().isEmpty())
        assertFalse(wrapper.hasWrappingKey())
        assertFalse(File(paths.vaultDir, "RESET_IN_PROGRESS").exists())
        assertTrue(VaultBootstrapper.bootstrap(paths, wrapper) is VaultBootstrapResult.Ready)
    }

    @Test
    fun interruptedResetLeavesMarkerAndBootstrapReportsResetIncompleteUntilFinishResetSucceeds(): Unit = runBlocking {
        val secret = ByteArray(32) { it.toByte() }
        wrapper.ensureWrappingKey()
        val wrapped = wrapper.wrap(secret, EnvelopeDomain.DATABASE_SECRET)
        paths.ensureDirectoriesExist()
        paths.databaseKeyFile.writeBytes(org.openlife.vault.crypto.EnvelopeCodec.encode(wrapped))
        val blob = File(paths.artefactsDir, "${UUID.randomUUID()}.blob").apply { writeBytes(byteArrayOf(1)) }
        val fileOps = FailOnceOnFile(blob)
        val reset = VaultResetRepository(paths, wrapper, MutationQueue(), fileOps = fileOps)

        assertEquals(VaultResetResult.FAILED, reset.resetVault())
        assertTrue(File(paths.vaultDir, "RESET_IN_PROGRESS").exists())
        val blocked = VaultBootstrapper.bootstrap(paths, wrapper) as VaultBootstrapResult.Unavailable
        assertEquals(org.openlife.vault.storage.VaultUnavailableCause.RESET_INCOMPLETE, blocked.cause)

        assertEquals(VaultResetResult.COMPLETED, reset.resetVault())
        assertFalse(File(paths.vaultDir, "RESET_IN_PROGRESS").exists())
        assertTrue(VaultBootstrapper.bootstrap(paths, wrapper) is VaultBootstrapResult.Ready)
    }

    @Test
    fun resetIsRefusedWhileAnImportIsInProgress(): Unit = runBlocking {
        val queue = MutationQueue()
        val result = queue.withMutation {
            VaultResetRepository(paths, wrapper, queue).resetVault()
        }
        assertEquals(VaultResetResult.BUSY, result)
        assertFalse(File(paths.vaultDir, "RESET_IN_PROGRESS").exists())
    }

    private class FailOnceOnFile(private val target: File) : ArtefactFileOps by ArtefactFileOps.Default {
        private var failed = false
        override fun deleteIfExists(file: File): Boolean {
            if (file == target && !failed) {
                failed = true
                return false
            }
            return ArtefactFileOps.Default.deleteIfExists(file)
        }
    }
}
