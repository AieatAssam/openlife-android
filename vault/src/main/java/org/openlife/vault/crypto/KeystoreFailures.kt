package org.openlife.vault.crypto

import android.security.keystore.KeyPermanentlyInvalidatedException
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
     * `AndroidKeyStoreCipherSpiBase.engineDoFinal` (android-10.0.0_r1
     * `keystore/java/android/security/keystore/`, android-14.0.0_r1
     * `keystore/java/android/security/keystore2/`) throws
     * `new AEADBadTagException().initCause(android.security.KeyStoreException)`
     * for that error and reports every other keymaster error as
     * IllegalBlockSizeException or BadPaddingException with the same kind of
     * cause, so those are transient: a false transient costs a retry, a false
     * CORRUPT costs the original. A permanently invalidated key is neither:
     * it is a lost key ([MissingWrappingKeyException]).
     */
    fun forCipherFailure(e: GeneralSecurityException): Exception = when {
        e is AEADBadTagException -> EnvelopeAuthenticationException(e)
        chainOf(e).any { it is KeyPermanentlyInvalidatedException } -> MissingWrappingKeyException(e)
        else -> KeystoreUnavailableException(e)
    }

    /**
     * True when [failure] or any cause says Keystore, not the stored bytes,
     * failed. Never true when the chain also holds a failed tag or a
     * permanently invalidated or missing key (review R-01): AOSP attaches a
     * KeyStoreException cause to a genuine tag failure, and a key that is
     * gone for good does not come back on retry.
     */
    fun isTemporary(failure: Throwable): Boolean {
        val chain = chainOf(failure).toList()
        return chain.none { it.isPermanent() } && chain.any { it.isKeystoreFailure() }
    }

    private fun chainOf(failure: Throwable): Sequence<Throwable> =
        generateSequence(failure) { current -> current.cause?.takeIf { it !== current } }.take(MAX_CAUSE_DEPTH)

    private fun Throwable.isPermanent(): Boolean = this is AEADBadTagException ||
        this is KeyPermanentlyInvalidatedException ||
        this is MissingWrappingKeyException

    private fun Throwable.isKeystoreFailure(): Boolean = this is KeystoreUnavailableException ||
        this is KeyStoreException ||
        this is ProviderException ||
        javaClass.name == PLATFORM_KEYSTORE_EXCEPTION ||
        javaClass.name.endsWith("UserNotAuthenticatedException")

    private const val MAX_CAUSE_DEPTH = 16
}
