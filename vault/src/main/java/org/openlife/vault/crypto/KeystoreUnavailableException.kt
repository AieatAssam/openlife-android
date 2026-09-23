package org.openlife.vault.crypto

/**
 * Android Keystore could not perform an operation for a reason other than a
 * failed authentication tag: the key is temporarily inaccessible, the
 * keystore daemon failed, or the key was invalidated. This is never evidence
 * that stored content was tampered with, so callers must not mark a Source
 * CORRUPT because of it (design §10: distinguish a temporarily unavailable
 * vault from confirmed loss).
 */
class KeystoreUnavailableException(cause: Throwable) : Exception("keystore unavailable", cause)
