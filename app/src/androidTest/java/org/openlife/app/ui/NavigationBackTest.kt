package org.openlife.app.ui

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.test.StrictModeRule
import org.openlife.app.MainActivity
import org.openlife.app.OpenLifeApp
import org.openlife.vault.model.IntakeKind

@RunWith(AndroidJUnit4::class)
class NavigationBackTest {
    @get:Rule
    val strictMode = StrictModeRule()


    private val application = ApplicationProvider.getApplicationContext<OpenLifeApp>()
    private var sourceId: UUID? = null

    @Before
    fun acknowledgeFirstRun() {
        FirstRunPreferences.setAcknowledged(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }

    @After
    fun deleteSyntheticSource() {
        sourceId?.let { id ->
            runBlocking {
                var finished = false
                SourceListViewModel(application).delete(id) { finished = true }
                val deadline = System.currentTimeMillis() + 30_000
                while (!finished && System.currentTimeMillis() < deadline) delay(50)
                assertTrue("synthetic navigation source cleanup timed out", finished)
            }
        }
    }

    @Test
    fun backFromViewerReturnsToListInsteadOfFinishing() {
        sourceId = createReadySource()
        val intent = Intent(application, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_SOURCE_ID, sourceId.toString())
            putExtra(MainActivity.EXTRA_OPEN_SOURCE_NONCE, application.openSourceNonce.issue())
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(
                "viewer did not load the authenticated image",
                device.wait(Until.hasObject(By.desc("Saved image")), 30_000),
            )

            pressBack()

            assertTrue(
                "list was not restored after viewer back",
                device.wait(Until.hasObject(By.text("Import from photos")), 5_000),
            )
            var finishing = true
            scenario.onActivity { finishing = it.isFinishing }
            assertFalse("back from viewer must not finish MainActivity", finishing)
        }
    }

    /**
     * A-10 (P1-19-R14, suspected): after a delete from the viewer, the Room
     * flow drops the source (ViewerDestination pops on a null source) and the
     * delete callback also pops. With the flow forced to win (the callback is
     * held back), the second pop must not remove the List destination.
     */
    @Test
    fun deleteFromViewerKeepsListOnTheBackStack() {
        sourceId = createReadySource()
        val intent = Intent(application, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_SOURCE_ID, sourceId.toString())
            putExtra(MainActivity.EXTRA_OPEN_SOURCE_NONCE, application.openSourceNonce.issue())
        }
        val releaseDeleteResult = CompletableDeferred<Unit>()
        SourceListViewModel.beforeDeleteResultForTest = { releaseDeleteResult.await() }
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val device = UiDevice.getInstance(instrumentation)
                assertTrue("viewer not shown", device.wait(Until.hasObject(By.desc("Saved image")), 30_000))
                device.findObject(By.desc("Delete")).click()
                device.wait(Until.findObject(By.res("delete_confirm")), 5_000)?.click()
                    ?: device.wait(Until.findObject(By.text("Delete")), 5_000)!!.click()

                assertTrue(
                    "the flow-driven pop should show the list",
                    device.wait(Until.hasObject(By.text("Import from photos")), 10_000),
                )
                // Release the held-back delete callback. Its continuation is
                // posted to the main looper before the runOnMainSync below, so
                // once that returns the callback's own pop has run.
                releaseDeleteResult.complete(Unit)
                instrumentation.runOnMainSync {}
                instrumentation.waitForIdleSync()
                device.waitForIdle()
                assertTrue(
                    "the list was popped off the back stack by the second pop",
                    device.wait(Until.hasObject(By.text("Import from photos")), 2_000),
                )
                var finishing = true
                scenario.onActivity { finishing = it.isFinishing }
                assertFalse("MainActivity must stay open", finishing)
            }
            sourceId = null
        } finally {
            releaseDeleteResult.complete(Unit)
            SourceListViewModel.beforeDeleteResultForTest = null
        }
    }

    @Test
    fun backFromListFinishesActivity() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            // pressBack() throws NoActivityResumedException when the activity
            // finishes, which is the P0-07-R3 list-root behaviour. Use the
            // unconditional variant and then assert DESTROYED.
            pressBackUnconditionally()
            val deadline = System.currentTimeMillis() + 5_000
            while (scenario.state != Lifecycle.State.DESTROYED &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50)
            }
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    private fun createReadySource(): UUID = runBlocking {
        val viewModel = IntakeViewModel(application, SavedStateHandle())
        viewModel.startImport(ByteArrayInputStream(syntheticJpeg()), "image/jpeg", IntakeKind.SHARE)
        val preview = awaitState(viewModel) { it is IntakeUiState.Preview } as IntakeUiState.Preview
        viewModel.confirmSave()
        awaitState(viewModel) { it is IntakeUiState.Saved }
        preview.sourceId
    }

    private suspend fun awaitState(
        viewModel: IntakeViewModel,
        predicate: (IntakeUiState) -> Boolean,
    ): IntakeUiState {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val state = viewModel.state.value
            if (predicate(state)) return state
            delay(50)
        }
        throw AssertionError("expected intake state was not reached")
    }

    private fun syntheticJpeg(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
