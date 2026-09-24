package org.openlife.vault.ocr

import java.io.File
import java.io.IOException
import java.io.InputStream

/** The bundled traineddata did not match its pinned digest; it is never used. */
class TessdataIntegrityException(message: String) : IOException(message)

/** P2-03 stub. */
class TessdataInstaller(
    private val dataDir: File,
    private val openAsset: () -> InputStream,
    private val expectedSha256: String = ENG_FAST_SHA256,
) {
    fun install(): File = dataDir

    companion object {
        const val ASSET_PATH = "tessdata/eng.traineddata"
        const val LANGUAGE = "eng"

        /** tessdata_fast tag 4.1.0 (tag object a8ba5063), 4,113,088 bytes, Apache-2.0 (ADR-0003). */
        const val ENG_FAST_SHA256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
    }
}
