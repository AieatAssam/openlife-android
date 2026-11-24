package org.openlife.vault.repository

import java.io.File

/**
 * Deletes everything under the vault directory except [keep] (the reset
 * marker), so an unknown file cannot block a reset (P1-18-R11, V-10).
 */
internal object VaultDirectoryWipe {
    /** True only when nothing but [keep] remains; every entry is attempted. */
    fun deleteAllExcept(directory: File, keep: File, fileOps: ArtefactFileOps): Boolean {
        val entries = directory.listFiles() ?: return !directory.exists()
        var allRemoved = true
        for (entry in entries) {
            if (entry == keep) continue
            // A symlink is removed as a link and never followed (review R-09).
            val isLink = java.nio.file.Files.isSymbolicLink(entry.toPath())
            if (!isLink && entry.isDirectory && !deleteAllExcept(entry, keep, fileOps)) allRemoved = false
            if (!fileOps.deleteIfExists(entry)) allRemoved = false
        }
        return allRemoved
    }
}
