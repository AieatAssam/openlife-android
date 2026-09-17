package org.openlife.vault.crypto

/**
 * Thrown by any envelope decrypt path — [AesGcmCodec] or [KeystoreWrapper] —
 * for every failure mode alike: wrong key, wrong AAD, a tampered ciphertext,
 * tag, or nonce, or a structurally invalid input. Callers get one uniform
 * failure to react to; no path returns partial or unauthenticated plaintext
 * (design §10).
 */
class EnvelopeAuthenticationException(cause: Throwable) : Exception("envelope authentication failed", cause)
