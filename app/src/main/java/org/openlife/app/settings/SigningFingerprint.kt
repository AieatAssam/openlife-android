package org.openlife.app.settings

import java.security.MessageDigest

/** P1-08-R2: a certificate's SHA-256 written as README writes it (colon-separated upper-case hex). */
object SigningFingerprint {
    fun sha256(certificate: ByteArray): String = format(MessageDigest.getInstance("SHA-256").digest(certificate))

    fun format(digest: ByteArray): String = digest.joinToString(separator = ":") { byte ->
        val value = byte.toInt() and BYTE_MASK
        HEX[value ushr NIBBLE].toString() + HEX[value and NIBBLE_MASK]
    }

    private const val HEX = "0123456789ABCDEF"
    private const val BYTE_MASK = 0xFF
    private const val NIBBLE = 4
    private const val NIBBLE_MASK = 0x0F
}
