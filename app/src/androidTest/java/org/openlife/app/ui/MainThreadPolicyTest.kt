package org.openlife.app.ui

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.app.test.StrictModeRule
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.repository.DeleteResult
import org.openlife.vault.repository.ReadyReadResult

/**
 * P1-13 / F-23: design §12 "There must be no main-thread file I/O". Drives the
 * real ViewModels, whose work starts on the main dispatcher, through save,
 * view, OCR and delete under [StrictModeRule]. Keystore and AES work cannot
 * be seen by StrictMode; `RepositoryDispatcherTest` in :vault covers that.
 */
@RunWith(AndroidJUnit4::class)
class MainThreadPolicyTest {
    @get:Rule
    val strictMode = StrictModeRule()

    private val application = ApplicationProvider.getApplicationContext<OpenLifeApp>()

    @Test
    fun saveViewDeleteAndOcrNeverTouchDiskOrCryptoOnMain(): Unit = runBlocking {
        val (intake, ocr, list) = withContext(Dispatchers.Main) {
            Triple(
                IntakeViewModel(application, SavedStateHandle()),
                OcrViewModel(application),
                SourceListViewModel(application),
            )
        }
        // Retry Busy from a read left over by an earlier test (F-29, fixed by P1-15).
        val bytes = uniqueJpeg()
        var preview: IntakeUiState.Preview? = null
        repeat(BUSY_RETRIES) {
            if (preview != null) return@repeat
            withContext(Dispatchers.Main) {
                intake.startImport(ByteArrayInputStream(bytes), "image/jpeg", IntakeKind.SHARE)
            }
            val settled = await {
                intake.state.value.takeIf { it is IntakeUiState.Preview || it is IntakeUiState.Busy }
            }
            preview = settled as? IntakeUiState.Preview ?: run { delay(BUSY_RETRY_DELAY_MS); null }
        }
        checkNotNull(preview) { "import stayed Busy" }

        withContext(Dispatchers.Main) { intake.confirmSave() }
        await { intake.state.value as? IntakeUiState.Saved }
        val sourceId = preview!!.sourceId

        val access = application.vault() as VaultAccess.Ready
        val read = withContext(Dispatchers.Main) { access.viewRepository.readReadyBytes(sourceId) }
        assertTrue("saved source must load", read is ReadyReadResult.Loaded)
        withContext(Dispatchers.Main) { list.loadThumbnail(sourceId) }

        // OCR state is only read while observed (P2-01), so observe it like the viewer does.
        val ocrState = ocr.state(sourceId)
        val observer = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { ocrState.collect {} }
        withContext(Dispatchers.Main) { ocr.run(sourceId) }
        await {
            ocrState.value.takeIf {
                it !is OcrUiState.Running && it !is OcrUiState.Idle && it !is OcrUiState.Loading
            }
        }
        observer.cancel()

        val deleted = AtomicReference<DeleteResult?>(null)
        withContext(Dispatchers.Main) { list.delete(sourceId) { deleted.set(it) } }
        assertTrue(await { deleted.get() } is DeleteResult.Deleted)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private suspend fun <T : Any> await(timeoutMs: Long = 60_000, read: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            delay(50)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    private companion object {
        const val BUSY_RETRIES = 5
        const val BUSY_RETRY_DELAY_MS = 500L
    }

    private fun uniqueJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(UUID.randomUUID().hashCode() or 0xFF000000.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
