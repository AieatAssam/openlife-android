package org.openlife.vault.storage

import java.io.File
import org.openlife.vault.crypto.Envelope
import org.openlife.vault.crypto.EnvelopeCodec

/**
 * Reads and writes the wrapped-database-secret envelope at
 * [VaultPaths.databaseKeyFile]. Framing only — authentication happens when
 * the caller unwraps the returned envelope via `KeystoreWrapper`.
 */
object VaultKeyFile {

    /** Returns null if no key file exists yet (first run). */
    fun read(file: File): Envelope? {
        if (!file.exists()) return null
        return EnvelopeCodec.decode(file.readBytes())
    }

    /**
     * Writes and fsyncs the key file, then fsyncs its parent directory, so
     * the wrapped secret is durable before the database is initialised
     * with it (design §10: "Write and synchronise the wrapped-secret file
     * before initialising the database").
     */
    fun writeAndSync(file: File, envelope: Envelope) {
        Fsync.writeAndSync(file, EnvelopeCodec.encode(envelope))
        Fsync.syncDirectory(requireNotNull(file.parentFile) { "key file must have a parent directory" })
    }
}
