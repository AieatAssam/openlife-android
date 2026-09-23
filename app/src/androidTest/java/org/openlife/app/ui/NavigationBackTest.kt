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
