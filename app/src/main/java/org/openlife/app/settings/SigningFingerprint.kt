package org.openlife.app.settings

/** P1-08-R2: a certificate's SHA-256 written as README writes it (colon-separated upper-case hex). */
object SigningFingerprint {
    fun sha256(certificate: ByteArray): String = TODO("P1-08 RED")

    fun format(digest: ByteArray): String = TODO("P1-08 RED")
}
