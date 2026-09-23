package org.openlife.vault.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.storage.Fsync
import org.openlife.vault.storage.VaultPaths
import java.io.File
import java.util.UUID

enum class VaultResetResult { COMPLETED, BUSY, FAILED }

/** Explicit, durable deletion of app-owned vault files and its wrapping key. */
class VaultResetRepository(
    private val paths: VaultPaths,
    private val keystoreWrapper: KeystoreWrapper,
    private val mutationQueue: MutationQueue,
    private val closeDatabase: () -> Unit = {},
    private val fileOps: ArtefactFileOps = ArtefactFileOps.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun resetVault(): VaultResetResult = withContext(ioDispatcher) {
        mutationQueue.tryAcquire { resetWhileLocked() } ?: VaultResetResult.BUSY
    }

    private fun resetWhileLocked(): VaultResetResult = try {
        paths.vaultDir.mkdirs()
        if (!paths.vaultDir.isDirectory) return VaultResetResult.FAILED

        if (!paths.resetMarkerFile.exists()) {
            Fsync.writeAndSync(paths.resetMarkerFile, byteArrayOf(1))
            Fsync.syncDirectory(paths.vaultDir)
        }

        closeDatabase()
        if (!deleteKnownArtefacts()) return VaultResetResult.FAILED
        if (!deleteKnownFiles()) return VaultResetResult.FAILED

        // OEM Keystore providers can fail to remove an alias. This is
        // non-fatal by the reset policy; with database.key gone, bootstrap
        // still creates a new random database secret.
        runCatching { keystoreWrapper.deleteWrappingKey() }

        if (!paths.resetMarkerFile.delete() && paths.resetMarkerFile.exists()) {
            return VaultResetResult.FAILED
        }
        try {
            Fsync.syncDirectory(paths.vaultDir)
        } catch (_: Exception) {
            // If the final metadata sync fails, restore the durable recovery
            // marker so the next bootstrap cannot mistake this for a new vault.
            runCatching {
                Fsync.writeAndSync(paths.resetMarkerFile, byteArrayOf(1))
                Fsync.syncDirectory(paths.vaultDir)
            }
            return VaultResetResult.FAILED
        }
        VaultResetResult.COMPLETED
    } catch (_: Exception) {
        VaultResetResult.FAILED
    }

    private fun deleteKnownArtefacts(): Boolean {
        if (!paths.artefactsDir.exists()) return true
        val files = paths.artefactsDir.listFiles() ?: return false
        for (file in files) {
            if (!isOwnedArtefactName(file.name) || !fileOps.deleteIfExists(file)) return false
        }
        return paths.artefactsDir.delete() || !paths.artefactsDir.exists()
    }

    private fun deleteKnownFiles(): Boolean {
        val databaseFiles = listOf(
            paths.databaseFile,
            File(paths.databaseFile.path + "-wal"),
            File(paths.databaseFile.path + "-shm"),
            paths.databaseKeyFile,
        )
        for (file in databaseFiles) {
            if (!fileOps.deleteIfExists(file)) return false
        }
        return true
    }

    private fun isOwnedArtefactName(name: String): Boolean {
        val suffix = when {
            name.endsWith(".stage") -> ".stage"
            name.endsWith(".blob") -> ".blob"
            else -> return false
        }
        return runCatching { UUID.fromString(name.removeSuffix(suffix)) }.isSuccess
    }
}
