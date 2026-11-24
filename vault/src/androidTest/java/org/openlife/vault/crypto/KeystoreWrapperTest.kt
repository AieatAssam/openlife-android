package org.openlife.vault.crypto

import org.junit.Assert.assertFalse
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.testing.DoFinalFaultWrapper
import org.openlife.vault.testing.KeystoreFaults

/**
 * Instrumented because Android Keystore is unavailable off-device (design
 * §13). Covers C0-R17/C0-R18: the wrapping key is non-exportable, its IV is
 * provider-generated (never caller-supplied), and unwrap fails closed the
 * same way as the pure-JVM AesGcmCodec path.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreWrapperTest {

    private lateinit var alias: String
    private lateinit var wrapper: KeystoreWrapper

    @Before
    fun setUp() {
        alias = "test.${UUID.randomUUID()}"
        wrapper = KeystoreWrapper(alias)
    }

    @After
    fun tearDown() {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    @Test
    fun firstUseCreatesAKeyAndSubsequentCallsReuseIt() {
        assertTrue(!wrapper.hasWrappingKey())
        val first = wrapper.ensureWrappingKey()
        assertTrue(wrapper.hasWrappingKey())
        val second = wrapper.ensureWrappingKey()
        // Same Keystore-backed key handle for the same alias, not a fresh
        // generation on every call (design §10: an existing wrapper is
        // never silently replaced).
        assertEquals(first, second)
    }

    @Test
    fun wrappingKeyIsNotExportable() {
        // A non-exportable Keystore SecretKey reports no encoded material.
        assertNull(wrapper.ensureWrappingKey().encoded)
    }

    @Test
    fun wrapThenUnwrapRoundTrips() {
        wrapper.ensureWrappingKey()
        val plaintext = "database secret material".toByteArray()
        val envelope = wrapper.wrap(plaintext, EnvelopeDomain.DATABASE_SECRET)
        val decrypted = wrapper.unwrap(envelope, EnvelopeDomain.DATABASE_SECRET)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun eachWrapUsesAFreshProviderGeneratedIv() {
        wrapper.ensureWrappingKey()
        val plaintext = "database secret material".toByteArray()
        val first = wrapper.wrap(plaintext, EnvelopeDomain.DATABASE_SECRET)
        val second = wrapper.wrap(plaintext, EnvelopeDomain.DATABASE_SECRET)
        assertNotEquals(String(first.nonce), String(second.nonce))
    }

    @Test
    fun sourceBoundWrapRejectsWrongUuidOnUnwrap() {
        wrapper.ensureWrappingKey()
        val sourceId = UUID.randomUUID()
        val plaintext = "per-source DEK".toByteArray()
        val envelope = wrapper.wrap(plaintext, EnvelopeDomain.SOURCE_KEY, sourceId)
        assertThrows(EnvelopeAuthenticationException::class.java) {
            wrapper.unwrap(envelope, EnvelopeDomain.SOURCE_KEY, UUID.randomUUID())
        }
    }

    @Test
    fun tamperedCiphertextFailsClosed() {
        wrapper.ensureWrappingKey()
        val plaintext = "database secret material".toByteArray()
        val envelope = wrapper.wrap(plaintext, EnvelopeDomain.DATABASE_SECRET)
        val tampered = envelope.ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertThrows(EnvelopeAuthenticationException::class.java) {
            wrapper.unwrap(Envelope(envelope.nonce, tampered), EnvelopeDomain.DATABASE_SECRET)
        }
    }

    /** P1-14-R2 / design §10: a lost wrapping key is never silently replaced. */
    @Test
    fun unwrapWithMissingAliasFailsAndDoesNotCreateAKey() {
        wrapper.ensureWrappingKey()
        val envelope = wrapper.wrap(ByteArray(32) { 7 }, EnvelopeDomain.DATABASE_SECRET)
        deleteAlias()

        val failure = runCatching { wrapper.unwrap(envelope, EnvelopeDomain.DATABASE_SECRET) }.exceptionOrNull()

        assertTrue("expected MissingWrappingKeyException, was $failure", failure is MissingWrappingKeyException)
        assertFalse("unwrap must not generate a replacement key", wrapper.hasWrappingKey())
    }

    @Test
    fun wrapWithMissingAliasFailsAndDoesNotCreateAKey() {
        wrapper.ensureWrappingKey()
        deleteAlias()

        val failure = runCatching { wrapper.wrap(ByteArray(32), EnvelopeDomain.DATABASE_SECRET) }.exceptionOrNull()

        assertTrue("expected MissingWrappingKeyException, was $failure", failure is MissingWrappingKeyException)
        assertFalse("wrap must not generate a replacement key", wrapper.hasWrappingKey())
    }

    /**
     * P1-18-R1 (V-01): only a failed GCM tag (AEADBadTagException) is
     * evidence about the envelope. AOSP's AndroidKeyStoreCipherSpiBase.engineDoFinal
     * reports every other keymaster error as IllegalBlockSizeException (and
     * KM_ERROR_INVALID_ARGUMENT as BadPaddingException) with a KeyStoreException
     * cause; those are transient and must never become a CORRUPT verdict.
     */
    @Test
    fun nonAeadCipherFailureIsTransientNotAuthentication() {
        val faulty = DoFinalFaultWrapper(alias)
        faulty.ensureWrappingKey()
        val envelope = faulty.wrap("database secret material".toByteArray(), EnvelopeDomain.DATABASE_SECRET)
        android.util.Log.i("P118KeystoreWrapperTest", KeystoreFaults.describe())

        faulty.failure = { KeystoreFaults.doFinalFailure() }
        val blockSize = runCatching { faulty.unwrap(envelope, EnvelopeDomain.DATABASE_SECRET) }.exceptionOrNull()
        assertTrue("IllegalBlockSizeException must be transient, was $blockSize", blockSize is KeystoreUnavailableException)

        faulty.failure = { javax.crypto.BadPaddingException("synthetic").apply { initCause(KeystoreFaults.keystoreCause()) } }
        val padding = runCatching { faulty.unwrap(envelope, EnvelopeDomain.DATABASE_SECRET) }.exceptionOrNull()
        assertTrue("BadPaddingException must be transient, was $padding", padding is KeystoreUnavailableException)

        faulty.failure = { javax.crypto.AEADBadTagException("synthetic tag failure") }
        val tag = runCatching { faulty.unwrap(envelope, EnvelopeDomain.DATABASE_SECRET) }.exceptionOrNull()
        assertTrue("AEADBadTagException is authentication, was $tag", tag is EnvelopeAuthenticationException)

        faulty.failure = null
        assertArrayEquals("database secret material".toByteArray(), faulty.unwrap(envelope, EnvelopeDomain.DATABASE_SECRET))
    }

    private fun deleteAlias() {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
    }
}
