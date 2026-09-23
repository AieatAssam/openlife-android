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
        withContext(Dispatchers.Main) {
            intake.startImport(ByteArrayInputStream(uniqueJpeg()), "image/jpeg", IntakeKind.SHARE)
        }
        val preview = await { intake.state.value as? IntakeUiState.Preview }

        withContext(Dispatchers.Main) { intake.confirmSave() }
        await { intake.state.value as? IntakeUiState.Saved }
        val sourceId = preview.sourceId

        val access = application.vault() as VaultAccess.Ready
        val read = withContext(Dispatchers.Main) { access.viewRepository.readReadyBytes(sourceId) }
        assertTrue("saved source must load", read is ReadyReadResult.Loaded)
        withContext(Dispatchers.Main) { list.loadThumbnail(sourceId) }

        withContext(Dispatchers.Main) { ocr.run(sourceId) }
        await {
            ocr.states.value[sourceId]?.takeIf { it !is OcrUiState.Running && it !is OcrUiState.Idle }
        }

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

    private fun uniqueJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(UUID.randomUUID().hashCode() or 0xFF000000.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
