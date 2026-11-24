package org.openlife.vault.crypto

import java.security.GeneralSecurityException

/**
 * The one classifier for Keystore failures (P1-18-R1, R2), shared by
 * [KeystoreWrapper] and vault bootstrap so the two cannot drift apart.
 */
object KeystoreFailures {
    /** Hidden before API 33; matched by name so the vault can run on API 29. */
    const val PLATFORM_KEYSTORE_EXCEPTION = "android.security.KeyStoreException"

    /** Maps a Keystore cipher failure to authentication (only a failed GCM tag) or transient. */
    fun forCipherFailure(e: GeneralSecurityException): Exception = TODO("P1-18-R1")

    /** True when [failure] or any cause says Keystore, not the stored bytes, failed. */
    fun isTemporary(failure: Throwable): Boolean = TODO("P1-18-R2")
}
