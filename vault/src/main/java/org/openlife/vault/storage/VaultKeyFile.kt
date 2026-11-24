package org.openlife.vault.storage

import org.openlife.vault.crypto.Envelope
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.EnvelopeFormat
import java.io.File

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
     * Writes and fsyncs the key file, then fsyncs its parent directory, so
     * the wrapped secret is durable before the database is initialised
     * with it (design §10: "Write and synchronise the wrapped-secret file
     * before initialising the database").
     */
    /** Removes a `database.key.tmp` left by a write that never reached its rename (P1-18-R10). */
    fun removeLeftoverTemp(file: File): Boolean = TODO("P1-18-R10")

    fun writeAndSync(file: File, envelope: Envelope, @Suppress("UNUSED_PARAMETER") ops: KeyFileOps = KeyFileOps.Default) {
        Fsync.writeAndSync(file, EnvelopeCodec.encode(envelope))
        Fsync.syncDirectory(requireNotNull(file.parentFile) { "key file must have a parent directory" })
    }
}

/** Durable-write seam for the key file, so the write protocol is testable on the JVM (P1-18-R10). */
interface KeyFileOps {
    /** Removes a `database.key.tmp` left by a write that never reached its rename (P1-18-R10). */
    fun removeLeftoverTemp(file: File): Boolean = TODO("P1-18-R10")

    fun writeAndSync(file: File, bytes: ByteArray)

    fun rename(from: File, to: File): Boolean

    fun syncDirectory(directory: File)

    object Default : KeyFileOps {
        override fun writeAndSync(file: File, bytes: ByteArray) = Fsync.writeAndSync(file, bytes)

        override fun rename(from: File, to: File): Boolean = from.renameTo(to)

        override fun syncDirectory(directory: File) = Fsync.syncDirectory(directory)
    }
}
