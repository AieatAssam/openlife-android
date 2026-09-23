package org.openlife.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.test.StrictModeRule
import org.openlife.app.OpenLifeApp
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.storage.VaultPaths

/**
 * Intake flows that need the real IntakeScreen on top of the real
 * IntakeViewModel and vault (P1-13-R8, R10). Kept apart from
 * IntakeAndListFlowTest because a Compose host activity interferes with
 * that class's ActivityScenario and UiDevice checks.
 */
@RunWith(AndroidJUnit4::class)
class IntakeScreenFlowTest {
    @get:Rule
    val strictMode = StrictModeRule()


    /** Serves a valid JPEG slowly so a test can act while the import is still Preparing. */
    private class SlowInputStream(bytes: ByteArray, private val delayPerReadMs: Long = 400) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        @Volatile var reads = 0
        @Volatile var closed = false

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (closed) throw IOException("closed")
            Thread.sleep(delayPerReadMs)
            return delegate.read(buffer, offset, minOf(length, 16))
        }

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<OpenLifeApp>()

    /** P1-13-R8: Preparing has a Cancel control that closes the stream and leaves no stage behind. */
    @Test
    fun cancelDuringPreparingClosesStreamAndLeavesNoStage(): Unit = runBlocking {
        val stream = SlowInputStream(syntheticJpegBytes(variant = 8))
        val intake = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            IntakeViewModel(application, SavedStateHandle())
        }
        composeRule.setContent {
            val state by intake.state.collectAsState()
            IntakeScreen(state = state, onSave = intake::confirmSave, onCancel = intake::cancel, onDone = {})
        }
        val stagesBefore = stageFiles()
        intake.startImport(stream, "image/jpeg", IntakeKind.SHARE)
        awaitCondition { stream.reads > 0 }

        composeRule.onNodeWithText("Cancel").performClick()
        awaitState(intake) { it is IntakeUiState.Cancelled }

        assertTrue("provider stream must close", stream.closed)
        awaitCondition(describe = { "new stage files remain: ${stageFiles() - stagesBefore}" }) {
            (stageFiles() - stagesBefore).isEmpty()
        }
    }

    /** C0-R29 from the UI: a second share while an import is active is refused with an explanation. */
    @Test
    fun secondShareDuringActiveImportShowsBusyCopy(): Unit = runBlocking {
        val first = SlowInputStream(syntheticJpegBytes(variant = 9))
        val firstIntake = IntakeViewModel(application, SavedStateHandle())
        val secondIntake = IntakeViewModel(application, SavedStateHandle())
        composeRule.setContent {
            val state by secondIntake.state.collectAsState()
            IntakeScreen(state = state, onSave = {}, onCancel = {}, onDone = {})
        }
        firstIntake.startImport(first, "image/jpeg", IntakeKind.SHARE)
        awaitCondition { first.reads > 0 }

        secondIntake.startImport(ByteArrayInputStream(syntheticJpegBytes(variant = 10)), "image/jpeg", IntakeKind.SHARE)
        awaitState(secondIntake) { it is IntakeUiState.Busy }
        composeRule.onNodeWithText("Another import is already in progress. Finish or cancel it first.")
            .assertExists()

        firstIntake.cancel()
        awaitState(firstIntake) { it is IntakeUiState.Cancelled }
    }

    private fun syntheticJpegBytes(variant: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF000000.toInt() or (variant * 0x010203))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun stageFiles(): Set<String> =
        VaultPaths(application).vaultDir.walkTopDown().filter { it.isFile && it.name.endsWith(".stage") }
            .map { it.name }.toSet()

    private suspend fun awaitState(viewModel: IntakeViewModel, predicate: (IntakeUiState) -> Boolean) {
        awaitCondition(describe = { "last intake state=${viewModel.state.value}" }) { predicate(viewModel.state.value) }
    }

    private suspend fun awaitCondition(describe: () -> String = { "condition" }, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            delay(POLL_MS)
        }
        throw AssertionError("condition not met within ${WAIT_MS}ms; ${describe()}")
    }

    private companion object {
        const val WAIT_MS = 60_000L
        const val POLL_MS = 50L
    }
}
