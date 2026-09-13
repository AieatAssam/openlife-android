package org.openlife.vault.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.StandardOpenOption

/**
 * Small durability helpers shared by the key file (Stage 2) and the
 * artefact stage/save/recovery paths (Stage 3+). Design §9/§11 require the
 * wrapped-secret file and staged/renamed artefacts to be synchronised, not
 * just written, before the next step treats them as durable.
 */
object Fsync {

    /** Writes [bytes] to [file] and fsyncs the file descriptor before returning. */
    fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    /**
     * Fsyncs a directory's own metadata (e.g. after a create or rename
     * within it), using the platform-supported implementation rather than
     * assuming the file write above was sufficient on its own.
     */
    fun syncDirectory(directory: File) {
        java.nio.channels.FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }
}
