package org.openlife.app.intake

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot proof that an intake intent came from OpenLife's own Photo
 * Picker forwarding (P1-02-R6, F-34). IntakeActivity is exported, so any app
 * can send it an intent claiming the picker route; only a caller that
 * received the current nonce from this process can. The value lives in
 * process memory only and is consumed on first use.
 */
class PickerNonce {
    private val current = AtomicReference<String?>(null)
    private val random = SecureRandom()

    /** Issues a fresh nonce, replacing any unused one. */
    fun issue(): String {
        val bytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val value = bytes.joinToString("") { "%02x".format(it) }
        current.set(value)
        return value
    }

    /** True exactly once for the current nonce; a wrong value does not burn it. */
    fun consume(candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        val expected = current.get() ?: return false
        val matches = MessageDigest.isEqual(expected.toByteArray(), candidate.toByteArray())
        return matches && current.compareAndSet(expected, null)
    }

    private companion object {
        const val NONCE_BYTES = 16
    }
}
