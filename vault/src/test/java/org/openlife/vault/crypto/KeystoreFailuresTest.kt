package org.openlife.vault.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
            InvalidKeyException("KeyPermanentlyInvalidated-shaped"),
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
     * The android.jar stub on the unit-test classpath has the real class name;
     * allocating it without running its stub constructor gives an instance
     * whose type is exactly what the platform throws.
     */
    private fun platformKeyStoreException(): Throwable {
        val type = Class.forName(KeystoreFailures.PLATFORM_KEYSTORE_EXCEPTION)
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val field = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val instance = unsafeType.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), type)
        assertNotNull(instance)
        return instance as Throwable
    }
}
