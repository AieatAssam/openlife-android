package org.openlife.app.test

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.app.ui.IntakeUiState
import org.openlife.app.ui.IntakeViewModel
import org.openlife.vault.model.IntakeKind

/** P1-20: saves a synthetic 64x48 JPEG through the real intake path and returns its id. */
fun OpenLifeApp.createReadySource(): UUID = runBlocking {
    val viewModel = IntakeViewModel(this@createReadySource, SavedStateHandle())
    viewModel.startImport(ByteArrayInputStream(syntheticJpeg()), "image/jpeg", IntakeKind.SHARE)
    val preview = withTimeout(SOURCE_WAIT_MS) { viewModel.state.first { it is IntakeUiState.Preview } }
    viewModel.confirmSave()
    withTimeout(SOURCE_WAIT_MS) { viewModel.state.first { it is IntakeUiState.Saved } }
    (preview as IntakeUiState.Preview).sourceId
}

/** Deletes a synthetic source; a no-op if the vault is not open. */
fun OpenLifeApp.deleteSyntheticSource(id: UUID) {
    runBlocking { (vault() as? VaultAccess.Ready)?.deletionRepository?.deleteSource(id) }
}

/** A small synthetic JPEG; no user content. */
fun syntheticJpeg(): ByteArray {
    val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
    return ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        output.toByteArray()
    }
}

private const val SOURCE_WAIT_MS = 30_000L
