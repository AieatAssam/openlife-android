package org.openlife.vault.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

/**
 * P1-18-R1/R2 (V-01) on the JVM. AOSP AndroidKeyStoreCipherSpiBase.engineDoFinal
 * (android-10.0.0_r1 and android-14.0.0_r1) reports KM_ERROR_VERIFICATION_FAILED
 * as AEADBadTagException, KM_ERROR_INVALID_ARGUMENT as BadPaddingException and
 * every other keymaster error as IllegalBlockSizeException with an
 * android.security.KeyStoreException cause. Only the first is evidence about
 * the stored envelope.
 */
class KeystoreFailuresTest {

    @Test
    fun onlyAFailedGcmTagIsAuthentication() {
        assertTrue(KeystoreFailures.forCipherFailure(AEADBadTagException("tag")) is EnvelopeAuthenticationException)

        val notTag = listOf(
            IllegalBlockSizeException("keymaster").apply { initCause(platformKeyStoreException()) },
            BadPaddingException("KM_ERROR_INVALID_ARGUMENT"),
            java.security.InvalidAlgorithmParameterException("init"),
            InvalidKeyException("key not usable right now"),
        )
        notTag.forEach { failure ->
            val mapped = KeystoreFailures.forCipherFailure(failure)
            assertTrue("$failure must be transient, was $mapped", mapped is KeystoreUnavailableException)
        }
    }

    @Test
    fun platformKeyStoreExceptionAnywhereInTheChainIsTemporary() {
        val platform = platformKeyStoreException()
        assertTrue(KeystoreFailures.isTemporary(platform))
        assertTrue(
            KeystoreFailures.isTemporary(
                EnvelopeAuthenticationException(IllegalBlockSizeException("x").apply { initCause(platform) }),
            ),
        )
        assertTrue(KeystoreFailures.isTemporary(KeystoreUnavailableException(RuntimeException("daemon"))))
        assertTrue(KeystoreFailures.isTemporary(java.security.KeyStoreException("jca")))
        assertTrue(KeystoreFailures.isTemporary(ProviderException("provider")))

        assertFalse(KeystoreFailures.isTemporary(EnvelopeAuthenticationException(AEADBadTagException("tag"))))
        assertFalse(KeystoreFailures.isTemporary(java.io.IOException("disk")))
    }

    /**
     * P1-18 review R-01: AOSP throws
     * `new AEADBadTagException().initCause(android.security.KeyStoreException)`
     * for KM_ERROR_VERIFICATION_FAILED (AndroidKeyStoreCipherSpiBase.engineDoFinal,
     * android-10.0.0_r1 keystore/, android-14.0.0_r1 keystore2/). The
     * Keystore cause must not make a genuine tag failure look transient.
     */
    @Test
    fun aFailedTagWithAKeystoreCauseIsNeverTemporary() {
        val tag = AEADBadTagException().apply { initCause(platformKeyStoreException()) }
        val mapped = KeystoreFailures.forCipherFailure(tag)
        assertTrue("$mapped", mapped is EnvelopeAuthenticationException)
        assertFalse(KeystoreFailures.isTemporary(tag))
        assertFalse(KeystoreFailures.isTemporary(mapped))
    }

    /** A permanently invalidated key never comes back; retrying forever is wrong (R-01). */
    @Test
    fun aPermanentlyInvalidatedKeyIsNotTransient() {
        val invalidated = PlatformExceptions.keyPermanentlyInvalidated() as GeneralSecurityException
        val mapped = KeystoreFailures.forCipherFailure(invalidated)
        assertTrue("$mapped", mapped is MissingWrappingKeyException)
        assertFalse(KeystoreFailures.isTemporary(invalidated))
        assertFalse(KeystoreFailures.isTemporary(mapped))
        assertFalse(KeystoreFailures.isTemporary(ProviderException(invalidated)))
        assertFalse(KeystoreFailures.isTemporary(InvalidKeyException(invalidated)))
    }

    private fun platformKeyStoreException(): Throwable = PlatformExceptions.keyStoreException()
}
