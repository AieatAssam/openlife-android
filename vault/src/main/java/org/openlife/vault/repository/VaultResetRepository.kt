package org.openlife.vault.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.storage.Fsync
import org.openlife.vault.storage.VaultPaths

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
    /**
     * Wipes the vault. [confirmation] is the capability that proves the user
     * confirmed it (P1-08 review); it carries no data and is not read.
     */
    @Suppress("UnusedParameter", "UNUSED_PARAMETER")
    suspend fun resetVault(confirmation: ResetConfirmation): VaultResetResult = withContext(ioDispatcher) {
        mutationQueue.tryMutation { resetWhileLocked() } ?: VaultResetResult.BUSY
    }

    private fun resetWhileLocked(): VaultResetResult = try {
        paths.vaultDir.mkdirs()
        if (!paths.vaultDir.isDirectory) return VaultResetResult.FAILED

        if (!paths.resetMarkerFile.exists()) {
            Fsync.writeAndSync(paths.resetMarkerFile, byteArrayOf(1))
            Fsync.syncDirectory(paths.vaultDir)
        }

        closeDatabase()
        // Every entry under the vault directory, known or not (database,
        // -wal/-shm/-journal, key, temporary key, artefacts, strays), so an
        // unrecognised file can never leave the marker set forever
        // (P1-18-R11). The marker goes last, below.
        if (!VaultDirectoryWipe.deleteAllExcept(paths.vaultDir, paths.resetMarkerFile, fileOps)) {
            return VaultResetResult.FAILED
        }

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
}
