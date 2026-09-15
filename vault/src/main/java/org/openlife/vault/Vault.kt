package org.openlife.vault

/**
 * Marker object for Stage 0. Real content — the encryption envelope
 * (Stage 1), Keystore wrapping and encrypted database (Stage 2), and the
 * import/recovery/deletion repository (Stages 3-5) — lands in the `crypto`,
 * `storage`, `model`, and `repository` packages as those stages begin.
 * Nothing in this module may declare INTERNET or any broad-access permission;
 * see docs/THREAT_MODEL.md.
 */
internal object Vault {
    const val SCHEMA_VERSION = 2
}
