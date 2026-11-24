package org.openlife.vault.crypto

import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException

/**
 * The one classifier for Keystore failures (P1-18-R1, R2), shared by
 * [KeystoreWrapper] and vault bootstrap so the two cannot drift apart.
 */
object KeystoreFailures {
    /** Hidden before API 33; matched by name so the vault can run on API 29. */
    const val PLATFORM_KEYSTORE_EXCEPTION = "android.security.KeyStoreException"

    /**
     * Maps a Keystore cipher failure. Only [AEADBadTagException] (keymaster
     * KM_ERROR_VERIFICATION_FAILED) is evidence about the envelope. AOSP
     * `AndroidKeyStoreCipherSpiBase.engineDoFinal` (android-10.0.0_r1,
     * android-14.0.0_r1) reports every other keymaster error as
     * IllegalBlockSizeException or BadPaddingException with an
     * android.security.KeyStoreException cause, so those are transient: a
     * false transient costs a retry, a false CORRUPT costs the original.
     */
    fun forCipherFailure(e: GeneralSecurityException): Exception =
        if (e is AEADBadTagException) EnvelopeAuthenticationException(e) else KeystoreUnavailableException(e)

    /** True when [failure] or any cause says Keystore, not the stored bytes, failed. */
    fun isTemporary(failure: Throwable): Boolean =
        generateSequence(failure) { current -> current.cause?.takeIf { it !== current } }
            .take(MAX_CAUSE_DEPTH)
            .any { it.isKeystoreFailure() }

    private fun Throwable.isKeystoreFailure(): Boolean =
        this is KeystoreUnavailableException ||
            this is KeyStoreException ||
            this is ProviderException ||
            javaClass.name == PLATFORM_KEYSTORE_EXCEPTION ||
            javaClass.name.endsWith("UserNotAuthenticatedException")

    private const val MAX_CAUSE_DEPTH = 16
}
