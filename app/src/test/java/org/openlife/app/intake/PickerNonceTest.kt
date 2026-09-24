package org.openlife.app.intake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-02-R6 / F-34: only OpenLife's own picker forwarding may record PHOTO_PICKER. */
class PickerNonceTest {
    @Test
    fun anIssuedNonceIsConsumedExactlyOnce() {
        val nonce = PickerNonce()
        val value = nonce.issue()
        assertTrue(value.length >= MIN_LENGTH)
        assertTrue(nonce.consume(value))
        assertFalse("a nonce is single-use", nonce.consume(value))
    }

    @Test
    fun missingOrForgedValuesAreRefused() {
        val nonce = PickerNonce()
        assertFalse("nothing issued", nonce.consume("guess"))
        val value = nonce.issue()
        assertFalse(nonce.consume(null))
        assertFalse(nonce.consume(""))
        assertFalse(nonce.consume(value + "x"))
        assertTrue("a refused guess does not burn the real nonce", nonce.consume(value))
    }

    @Test
    fun issuingAgainReplacesThePreviousNonce() {
        val nonce = PickerNonce()
        val first = nonce.issue()
        val second = nonce.issue()
        assertNotEquals(first, second)
        assertFalse(nonce.consume(first))
        assertTrue(nonce.consume(second))
    }

    private companion object {
        const val MIN_LENGTH = 32
    }
}
