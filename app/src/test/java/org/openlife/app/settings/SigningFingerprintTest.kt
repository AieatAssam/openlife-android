package org.openlife.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** P1-08-R2: the About fingerprint is written exactly as README writes the release fingerprint. */
class SigningFingerprintTest {
    @Test
    fun formatsAsColonSeparatedUpperCaseHex() {
        assertEquals("00:0A:FF:7B", SigningFingerprint.format(byteArrayOf(0, 10, -1, 123)))
        // SHA-256 of the empty input, a fixed vector.
        assertEquals(
            "E3:B0:C4:42:98:FC:1C:14:9A:FB:F4:C8:99:6F:B9:24:27:AE:41:E4:64:9B:93:4C:A4:95:99:1B:78:52:B8:55",
            SigningFingerprint.sha256(ByteArray(0)),
        )
    }

    @Test
    fun readmeFingerprintHasTheSameShape() {
        val readme = java.nio.file.Files.readString(
            java.nio.file.Path.of(System.getProperty("openlife.projectDir", ".")).resolve("README.md"),
        )
        val shape = Regex("""([0-9A-F]{2}:){31}[0-9A-F]{2}""")
        val documented = shape.find(readme)?.value ?: error("README has no SHA-256 fingerprint")
        val bytes = documented.split(':').map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(documented, SigningFingerprint.format(bytes))
    }
}
