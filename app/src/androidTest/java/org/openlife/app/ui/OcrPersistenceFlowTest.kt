package org.openlife.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.app.test.StrictModeRule
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.repository.PrepareResult
import org.openlife.vault.repository.SaveResult

/**
 * P2-01: OCR results live in the database, so a new ViewModel (as after a
 * process restart) shows the persisted revision instead of offering to
 * extract again.
 */
@RunWith(AndroidJUnit4::class)
class OcrPersistenceFlowTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenLifeApp

    @Test
    fun readyRevisionIsShownAfterViewModelRecreationWithoutRerunning(): Unit = runBlocking {
        val access = app.vault() as VaultAccess.Ready
        val sourceId = saveSyntheticSource(access)
        try {
            val first = withContext(Dispatchers.Main) { OcrViewModel(app) }
            withContext(Dispatchers.Main) { first.run(sourceId) }
            val persisted = withTimeout(OCR_TIMEOUT_MS) {
                access.ocrRepository.observeOcrView(sourceId).first { view ->
                    view != null && view.revision.state != org.openlife.vault.ocr.OcrRevisionState.RUNNING
                }
            }!!

            // A new ViewModel, as after process death: it must read, not re-run.
            val second = withContext(Dispatchers.Main) { OcrViewModel(app) }
            composeRule.setContent {
                val state by second.state(sourceId).collectAsState()
                ViewerScreen(
                    source = readySource(sourceId),
                    loadContent = { awaitCancellation() },
                    onBack = {},
                    onDeleteRequested = {},
                    ocrState = state,
                )
            }
            val settled = withTimeout(VIEW_TIMEOUT_MS) {
                var value = second.state(sourceId).value
                while (value is OcrUiState.Loading || value is OcrUiState.Idle) {
                    delay(POLL_MS)
                    value = second.state(sourceId).value
                }
                value
            }
            assertTrue("persisted outcome shown: $settled", settled is OcrUiState.Ready || settled is OcrUiState.Failed)
            composeRule.onNodeWithText("Extract text on this device").assertDoesNotExist()
            val after = access.ocrRepository.observeOcrView(sourceId).first()!!
            assertEquals("no second run", persisted.revision.id, after.revision.id)
            assertEquals(0, after.history.size)
        } finally {
            access.deletionRepository.deleteSource(sourceId)
        }
    }

    private suspend fun saveSyntheticSource(access: VaultAccess.Ready): UUID {
        val bitmap = android.graphics.Bitmap.createBitmap(48, 32, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(UUID.randomUUID().hashCode() or 0xFF000000.toInt())
        val bytes = ByteArrayOutputStream().also {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        bitmap.recycle()
        val prepared = access.importRepository.prepareImport(ByteArrayInputStream(bytes), "image/jpeg", IntakeKind.SHARE)
            as PrepareResult.Prepared
        assertTrue(access.importRepository.saveImport(prepared.sourceId) is SaveResult.Saved)
        return prepared.sourceId
    }

    private fun readySource(id: UUID) = Source(
        id = id,
        state = SourceState.READY,
        importedAt = 1L,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = 48,
        height = 32,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )

    private companion object {
        const val OCR_TIMEOUT_MS = 60_000L
        const val VIEW_TIMEOUT_MS = 15_000L
        const val POLL_MS = 50L
    }
}
