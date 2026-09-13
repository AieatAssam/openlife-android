package org.openlife.vault.crypto

/**
 * Distinct AAD domain labels for each purpose an envelope is used for.
 * Binding a domain label into the AAD (see [EnvelopeAad]) stops a valid
 * envelope written for one purpose from being accepted in place of another
 * — e.g. a Source artefact ciphertext being fed to the database-secret
 * unwrap path — even when both happen to be protected by the same Keystore
 * wrapping key. See openlife-design-v0.2.md §10.
 *
 * Labels are versioned in the string itself (`.v1`) rather than relying
 * solely on [EnvelopeFormat.VERSION], so a future domain-specific change
 * that does not touch the envelope's binary layout still changes the AAD.
 */
enum class EnvelopeDomain(val label: String) {
    ARTEFACT("openlife.artefact.v1"),
    SOURCE_KEY("openlife.source-key.v1"),
    DATABASE_SECRET("openlife.database-secret.v1"),
}
