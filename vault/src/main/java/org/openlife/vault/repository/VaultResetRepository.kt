package org.openlife.vault.repository

import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.storage.VaultPaths

enum class VaultResetResult { COMPLETED, BUSY, FAILED }

/** Testable reset entry point. The implementation is completed in GREEN. */
class VaultResetRepository(
    @Suppress("unused") private val paths: VaultPaths,
    @Suppress("unused") private val keystoreWrapper: KeystoreWrapper,
    @Suppress("unused") private val mutationQueue: MutationQueue,
    @Suppress("unused") private val closeDatabase: () -> Unit = {},
    @Suppress("unused") private val fileOps: ArtefactFileOps = ArtefactFileOps.Default,
) {
    suspend fun resetVault(): VaultResetResult = VaultResetResult.BUSY
}
