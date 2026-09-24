package org.openlife.vault.crypto

/**
 * The Keystore wrapping key for this installation no longer exists (for
 * example after a lock-screen removal or a restore onto a new device).
 * Everything wrapped under it is unrecoverable; OpenLife reports this and
 * never generates a replacement key outside a fresh bootstrap (P1-14-R2).
 */
class MissingWrappingKeyException : Exception("wrapping key is missing")
