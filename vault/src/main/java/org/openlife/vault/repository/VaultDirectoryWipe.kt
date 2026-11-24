package org.openlife.vault.repository

import java.io.File

/**
 * Deletes everything under the vault directory except [keep] (the reset
 * marker), so an unknown file cannot block a reset (P1-18-R11, V-10).
 */
internal object VaultDirectoryWipe {
    fun deleteAllExcept(directory: File, keep: File, fileOps: ArtefactFileOps): Boolean = TODO("P1-18-R11")
}
