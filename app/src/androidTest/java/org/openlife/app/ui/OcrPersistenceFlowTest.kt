package org.openlife.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
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
    private val viewModelStores = mutableListOf<ViewModelStore>()

    /** Review Q-08: every OcrViewModel made here is cleared after the test, as a screen's would be. */
    @After
    fun clearViewModels() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModelStores.forEach { it.clear() } }
        viewModelStores.clear()
    }

    private suspend fun newOcrViewModel(): OcrViewModel = withContext(Dispatchers.Main) {
        val store = ViewModelStore().also(viewModelStores::add)
        ViewModelProvider(store, OcrViewModel.factory(app))[OcrViewModel::class.java]
    }

    @Test
    fun readyRevisionIsShownAfterViewModelRecreationWithoutRerunning(): Unit = runBlocking {
        val access = app.vault() as VaultAccess.Ready
        val sourceId = saveSyntheticSource(access)
        try {
            val first = newOcrViewModel()
            withContext(Dispatchers.Main) { first.run(sourceId) }
            val persisted = withTimeout(OCR_TIMEOUT_MS) {
                access.ocrRepository.observeOcrView(sourceId).first { view ->
                    view != null && view.revision.state != org.openlife.vault.ocr.OcrRevisionState.RUNNING
                }
            }!!

            // A new ViewModel, as after process death: it must read, not re-run.
            val second = newOcrViewModel()
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

    /**
     * A-13 (P1-19-R16, suspected): OcrViewModel watched the visible-source
     * list only if the vault was Ready when the ViewModel was created. Here
     * the vault opens later (Unavailable, then Ready after a retry); a source
     * deleted afterwards must still be forgotten (its state is dropped back
     * to Loading by OcrPresenter.forget).
     */
    @Test
    fun ocrTextIsForgottenForSourcesDeletedAfterVaultReset(): Unit = runBlocking {
        var attempts = 0
        val failingOnce = object : org.openlife.vault.crypto.KeystoreWrapper() {
            private var failed = false

            override fun wrap(
                plaintext: ByteArray,
                domain: org.openlife.vault.crypto.EnvelopeDomain,
                sourceId: UUID?,
            ): org.openlife.vault.crypto.Envelope {
                if (!failed) {
                    failed = true
                    throw java.security.ProviderException("synthetic temporary keystore failure")
                }
                return super.wrap(plaintext, domain, sourceId)
            }

            override fun unwrap(
                envelope: org.openlife.vault.crypto.Envelope,
                domain: org.openlife.vault.crypto.EnvelopeDomain,
                sourceId: UUID?,
            ): ByteArray {
                if (!failed) {
                    failed = true
                    throw java.security.ProviderException("synthetic temporary keystore failure")
                }
                return super.unwrap(envelope, domain, sourceId)
            }
        }
        app.setBootstrapOverrideForTest { paths, wrapper ->
            attempts++
            org.openlife.vault.storage.VaultBootstrapper.bootstrap(paths, if (attempts == 1) failingOnce else wrapper)
        }
        var sourceId: UUID? = null
        try {
            val viewModel = newOcrViewModel()
            var access = app.vault()
            val deadline = System.currentTimeMillis() + VIEW_TIMEOUT_MS
            while (access !is VaultAccess.Ready && System.currentTimeMillis() < deadline) {
                app.retryVault()
                access = app.vault()
            }
            assertTrue("the vault never opened: $access", access is VaultAccess.Ready)
            val ready = access as VaultAccess.Ready
            val id = saveSyntheticSource(ready).also { sourceId = it }

            val state = withContext(Dispatchers.Main) { viewModel.state(id) }
            val collector = launch(Dispatchers.Main) { state.collect {} }
            withTimeout(VIEW_TIMEOUT_MS) { while (state.value is OcrUiState.Loading) delay(POLL_MS) }
            val held = state.value

            ready.deletionRepository.deleteSource(id)
            sourceId = null
            val forgotten = withTimeoutOrNull(VIEW_TIMEOUT_MS) {
                while (state.value !is OcrUiState.Loading) delay(POLL_MS)
                true
            } ?: false
            collector.cancel()
            assertTrue("OCR state for a deleted source was kept ($held -> ${state.value})", forgotten)
        } finally {
            app.setBootstrapOverrideForTest(null)
            sourceId?.let { id -> (app.vault() as? VaultAccess.Ready)?.deletionRepository?.deleteSource(id) }
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
