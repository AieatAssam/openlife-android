package org.openlife.vault.repository

/** Outcome of reading a saved Source for display (P1-13-R5). */
sealed interface ReadyReadResult {
    /** Authenticated, digest-verified plaintext. The caller owns and should clear it. */
    class Loaded(val bytes: ByteArray) : ReadyReadResult

    /** The stored artefact failed authentication or its digest; the row is now CORRUPT. */
    data object Corrupt : ReadyReadResult

    /** Keystore or I/O failed temporarily; nothing was marked and a retry may succeed. */
    data object Transient : ReadyReadResult

    /** No READY row, or its artefact is missing; nothing to display. */
    data object Unavailable : ReadyReadResult
}
