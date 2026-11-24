package org.openlife.vault.storage

import org.openlife.vault.crypto.Envelope
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.EnvelopeFormat
import java.io.File
import java.io.IOException

/**
 * Reads and writes the wrapped-database-secret envelope at
 * [VaultPaths.databaseKeyFile]. Framing only — authentication happens when
 * the caller unwraps the returned envelope via `KeystoreWrapper`.
 */
object VaultKeyFile {

    /** Returns null if no key file exists yet (first run). */
    fun read(file: File, reader: BoundedFileReader = BoundedFileReader.Default): Envelope? {
        if (!file.exists()) return null
        return try {
            EnvelopeCodec.decode(reader.readAtMost(file, EnvelopeFormat.MAX_ENCODED_LENGTH_BYTES))
        } catch (_: FileTooLargeException) {
            throw EnvelopeCodec.MalformedEnvelopeException("envelope file exceeds the maximum length")
        }
    }

    /**
     * Writes the key to `database.key.tmp`, fsyncs it, renames it over
     * `database.key`, then fsyncs the parent directory (P1-18-R10). A crash
     * at any point leaves either the old key, the new key, or a temporary
     * file that bootstrap removes; never a torn `database.key`. The secret is
     * durable before the database is initialised with it (design §10).
     */
    fun writeAndSync(file: File, envelope: Envelope, ops: KeyFileOps = KeyFileOps.Default) {
        val directory = requireNotNull(file.parentFile) { "key file must have a parent directory" }
        val temp = tempFor(file)
        ops.writeAndSync(temp, EnvelopeCodec.encode(envelope))
        if (!ops.rename(temp, file)) throw IOException("could not rename the temporary key file into place")
        ops.syncDirectory(directory)
    }

    /** Removes a `database.key.tmp` left by a write that never reached its rename (P1-18-R10). */
    fun removeLeftoverTemp(file: File): Boolean {
        val temp = tempFor(file)
        return !temp.exists() || temp.delete() || !temp.exists()
    }

    private fun tempFor(file: File): File = File(file.path + TEMP_SUFFIX)

    private const val TEMP_SUFFIX = ".tmp"
}

/** Durable-write seam for the key file, so the write protocol is testable on the JVM (P1-18-R10). */
interface KeyFileOps {
    fun writeAndSync(file: File, bytes: ByteArray)

    fun rename(from: File, to: File): Boolean

    fun syncDirectory(directory: File)

    object Default : KeyFileOps {
        override fun writeAndSync(file: File, bytes: ByteArray) = Fsync.writeAndSync(file, bytes)

        override fun rename(from: File, to: File): Boolean = from.renameTo(to)

        override fun syncDirectory(directory: File) = Fsync.syncDirectory(directory)
    }
}
