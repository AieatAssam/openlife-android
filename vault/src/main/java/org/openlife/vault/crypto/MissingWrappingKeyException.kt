package org.openlife.vault.crypto

/**
 * This installation's Keystore wrapping key is gone (P1-14-R2). Permanent,
 * not transient, and never repaired by generating a replacement (design
 * §10): the vault's wrapped secrets can no longer be opened.
 */
class MissingWrappingKeyException : Exception("wrapping key is missing")
