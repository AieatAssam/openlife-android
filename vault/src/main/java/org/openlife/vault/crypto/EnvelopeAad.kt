package org.openlife.vault.crypto

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Builds the authenticated additional data (AAD) bound into an envelope's
 * GCM tag on encrypt and required again on decrypt. AAD is never stored in
 * the envelope bytes (see [EnvelopeFormat]); both sides reconstruct it
 * identically from context they already hold.
 *
 * Layout, big-endian:
 * ```
 * version    1 byte
 * labelLen   4 bytes
 * label      labelLen bytes, ASCII
 * sourceId   16 bytes (present only when a Source UUID is bound)
 * ```
 */
object EnvelopeAad {

    fun forDomain(domain: EnvelopeDomain, version: Byte = EnvelopeFormat.VERSION): ByteArray =
        build(version, domain, sourceId = null)

    /** For per-source envelopes (the artefact and the wrapped per-source DEK). */
    fun forSource(domain: EnvelopeDomain, sourceId: UUID, version: Byte = EnvelopeFormat.VERSION): ByteArray =
        build(version, domain, sourceId)

    private fun build(version: Byte, domain: EnvelopeDomain, sourceId: UUID?): ByteArray {
        val labelBytes = domain.label.toByteArray(StandardCharsets.US_ASCII)
        val capacity = 1 + 4 + labelBytes.size + (if (sourceId != null) 16 else 0)
        val buffer = ByteBuffer.allocate(capacity)
        buffer.put(version)
        buffer.putInt(labelBytes.size)
        buffer.put(labelBytes)
        if (sourceId != null) {
            buffer.putLong(sourceId.mostSignificantBits)
            buffer.putLong(sourceId.leastSignificantBits)
        }
        return buffer.array()
    }
}
