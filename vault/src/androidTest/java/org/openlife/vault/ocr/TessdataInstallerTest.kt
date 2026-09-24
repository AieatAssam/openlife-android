package org.openlife.vault.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** P2-03-R1/R2: the bundled traineddata is used only when it matches its pinned SHA-256. */
@RunWith(AndroidJUnit4::class)
class TessdataInstallerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dataDir = File(context.noBackupFilesDir, "test-tessdata-${UUID.randomUUID()}")
    private val installed = File(dataDir, "tessdata/eng.traineddata")

    @After
    fun tearDown() {
        dataDir.deleteRecursively()
    }

    @Test
    fun refusesToUseTraineddataWhoseDigestDoesNotMatch() {
        val forged = TessdataInstaller(dataDir, openAsset = { ByteArrayInputStream("not a model".toByteArray()) })

        assertThrows(TessdataIntegrityException::class.java) { forged.install() }
        assertFalse("nothing unverified is left for the engine to load", installed.exists())

        // A copy damaged after install is not used either: it is replaced from the verified asset.
        TessdataInstaller(dataDir, openAsset = ::openBundled).install()
        installed.writeBytes(installed.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        TessdataInstaller(dataDir, openAsset = ::openBundled).install()
        assertEquals(TessdataInstaller.ENG_FAST_SHA256, sha256(installed))
    }

    @Test
    fun installsOnceAndIsIdempotent() {
        val opens = AtomicInteger()
        val opener = {
            opens.incrementAndGet()
            openBundled()
        }

        val first = TessdataInstaller(dataDir, openAsset = opener).install()
        val modified = installed.lastModified()
        val second = TessdataInstaller(dataDir, openAsset = opener).install()

        assertEquals(first, second)
        assertEquals("the asset is copied once", 1, opens.get())
        assertEquals(modified, installed.lastModified())
        assertEquals(TessdataInstaller.ENG_FAST_SHA256, sha256(installed))
    }

    private fun openBundled() = context.assets.open(TessdataInstaller.ASSET_PATH)

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
