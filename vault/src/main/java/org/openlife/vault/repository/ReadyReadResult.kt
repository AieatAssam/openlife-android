package org.openlife.vault.repository

/** Outcome of reading a saved Source for display (P1-13-R5). */
sealed interface ReadyReadResult {
    /** Authenticated, digest-verified plaintext. The caller owns and should clear it. */
    class Loaded(val bytes: ByteArray) : ReadyReadResult

    /** The stored artefact failed authentication or its digest; the row is now CORRUPT. */
    data object Corrupt : ReadyReadResult

    /**
     * The READY row's artefact file is missing. Startup recovery marks this
     * CORRUPT (design §11); a read reports it without marking.
     */
    data object Missing : ReadyReadResult

    /** Keystore or I/O failed temporarily; nothing was marked and a retry may succeed. */
    data object Transient : ReadyReadResult

    /** No READY row (not found, already CORRUPT, or being deleted); nothing to display. */
    data object Unavailable : ReadyReadResult
}
