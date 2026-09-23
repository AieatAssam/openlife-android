package org.openlife.vault.crypto

/**
 * Fixed binary layout for every authenticated-encryption envelope OpenLife
 * writes to disk: the wrapped database secret, each Source's wrapped
 * per-file DEK, and each Source's encrypted artefact. See
 * openlife-design-v0.2.md §10 and docs/capabilities/C0.md C0-R17..C0-R20.
 *
 * Layout, big-endian, every length field counts the field that follows it:
 *
 * ```
 * magic            4 bytes   ASCII "OLV1"
 * version          1 byte    EnvelopeFormat.VERSION
 * nonceLength      1 byte    must equal NONCE_LENGTH_BYTES
 * nonce            nonceLength bytes
 * ciphertextLength 4 bytes   unsigned (stored as signed Int; never negative)
 * ciphertext       ciphertextLength bytes — GCM ciphertext with the 16-byte
 *                  authentication tag appended, exactly what
 *                  javax.crypto.Cipher produces/consumes for GCM in one
 *                  shot. There is no separate tag field in this layout.
 * ```
 *
 * The envelope format version, a caller-supplied domain label, and (for
 * per-source envelopes) the Source UUID are authenticated as additional
 * data (see [EnvelopeAad]) but are never stored in the envelope bytes. The
 * caller must reconstruct identical AAD from context it already has, or
 * decryption fails closed. This is deliberate: it stops a ciphertext being
 * replayed into the wrong slot — e.g. one Source's wrapped DEK substituted
 * for another's — even though the raw bytes would otherwise decrypt
 * successfully under the same key.
 */
object EnvelopeFormat {
    val MAGIC: ByteArray = byteArrayOf('O'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    const val VERSION: Byte = 1

    /** 96-bit GCM nonce, per design §10. */
    const val NONCE_LENGTH_BYTES = 12
    const val TAG_LENGTH_BITS = 128
    const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8

    /**
     * Upper bound on the declared ciphertext length, checked BEFORE any
     * buffer is allocated for it (design §10: "Bound lengths before
     * allocation"). This is independent of, and deliberately looser than,
     * any business-size limit enforced elsewhere — e.g. the 16 MiB original
     * artefact limit in docs/capabilities/C0.md C0-R26 — it exists purely to
     * stop a corrupted or hostile length field from driving an unbounded
     * allocation before that business check ever runs.
     */
    const val MAX_CIPHERTEXT_LENGTH = 32 * 1024 * 1024

    // Magic, version, and nonce-length fields precede the nonce bytes.
    const val HEADER_LENGTH_BYTES = 4 + 1 + 1
    const val CIPHERTEXT_LENGTH_FIELD_BYTES = 4

    /** Maximum physical envelope size including framing and nonce bytes. */
    val MAX_ENCODED_LENGTH_BYTES: Long =
        MAX_CIPHERTEXT_LENGTH.toLong() + HEADER_LENGTH_BYTES + NONCE_LENGTH_BYTES +
            CIPHERTEXT_LENGTH_FIELD_BYTES
}
