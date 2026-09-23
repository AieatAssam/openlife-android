package org.openlife.vault.repository

import org.openlife.vault.storage.Fsync
import java.io.File

/**
 * Filesystem operations at the stage/blob durability boundary.
 *
 * The default implementation is the real filesystem path. Keeping these
 * four operations behind this small boundary makes C0's write/sync/deletion failure
 * recovery testable without filling a device or changing permissions; it is
 * not a second storage abstraction exposed outside the repository.
 */
interface ArtefactFileOps {
    fun writeAndSync(file: File, bytes: ByteArray)

    fun rename(stage: File, blob: File): Boolean

    fun syncDirectory(directory: File)

    /**
     * Removes a known app-owned artefact and verifies that it is gone.
     * Returning false keeps the owning database row durable for retry.
     */
    fun deleteIfExists(file: File): Boolean

    object Default : ArtefactFileOps {
        override fun writeAndSync(file: File, bytes: ByteArray) = Fsync.writeAndSync(file, bytes)

        override fun rename(stage: File, blob: File): Boolean = stage.renameTo(blob)

        override fun syncDirectory(directory: File) = Fsync.syncDirectory(directory)

        override fun deleteIfExists(file: File): Boolean {
            if (!file.exists()) return true
            return file.delete() && !file.exists()
        }
    }
}
