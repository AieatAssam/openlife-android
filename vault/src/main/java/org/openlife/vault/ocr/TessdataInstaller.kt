package org.openlife.vault.ocr

import org.openlife.vault.storage.Fsync
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** The bundled traineddata did not match its pinned digest; it is never used. */
class TessdataIntegrityException(message: String) : IOException(message)

/**
 * P2-03-R1/R2: makes the vendored `eng.traineddata` available to Tesseract,
 * which reads it from a file path. The copy is verified against the pinned
 * SHA-256 before first use in each process; a copy that does not match is
 * replaced from the asset, and an asset that does not match is refused.
 * The copy goes to a temporary file, is synced, then renamed into place, so
 * Tesseract never sees a partial model. All of this is disk I/O: call it off
 * the main thread.
 */
class TessdataInstaller(
    private val dataDir: File,
    private val openAsset: () -> InputStream,
    private val expectedSha256: String = ENG_FAST_SHA256,
) {
    @Volatile
    private var verified: File? = null

    /** Returns the data path to pass to Tesseract (the parent of `tessdata/`). */
    fun install(): File {
        verified?.let { return it }
        synchronized(this) {
            verified?.let { return it }
            val tessdata = File(dataDir, TESSDATA_DIR)
            val target = File(tessdata, "$LANGUAGE.traineddata")
            if (!target.isFile || sha256Of(target) != expectedSha256) copyVerified(tessdata, target)
            verified = dataDir
            return dataDir
        }
    }

    private fun copyVerified(tessdata: File, target: File) {
        if (!tessdata.isDirectory && !tessdata.mkdirs()) throw IOException("could not create the OCR model directory")
        val temp = File(tessdata, "$LANGUAGE.traineddata.tmp")
        val digest = MessageDigest.getInstance(SHA_256)
        try {
            openAsset().use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (hex(digest.digest()) != expectedSha256) {
                throw TessdataIntegrityException("the bundled OCR model does not match its pinned digest")
            }
            if (!temp.renameTo(target)) throw IOException("could not install the OCR model")
            Fsync.syncDirectory(tessdata)
        } finally {
            temp.delete()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance(SHA_256)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val ASSET_PATH = "tessdata/eng.traineddata"
        const val LANGUAGE = "eng"
        private const val TESSDATA_DIR = "tessdata"
        private const val SHA_256 = "SHA-256"
        private const val BUFFER_BYTES = 64 * 1024

        /** tessdata_fast tag 4.1.0 (tag object a8ba5063), 4,113,088 bytes, Apache-2.0 (ADR-0003). */
        const val ENG_FAST_SHA256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
    }
}
