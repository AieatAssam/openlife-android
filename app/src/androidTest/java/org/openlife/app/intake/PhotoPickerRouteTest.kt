package org.openlife.app.intake

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.MainActivity
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.FirstRunPreferences
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.storage.VaultPaths

/**
 * P1-02: the Photo Picker route. MainActivity forwards the picked URI to the
 * intake flow without touching ContentResolver on the main thread; the
 * recorded route cannot be spoofed by an external sender; unsupported picked
 * formats are explained; the contract's fallback stays a single-item picker.
 */
@RunWith(AndroidJUnit4::class)
class PhotoPickerRouteTest {
    @get:Rule
    val strictMode = StrictModeRule()

    private val application = ApplicationProvider.getApplicationContext<OpenLifeApp>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUp() {
        TestHostileContentProvider.reset()
        FirstRunPreferences.setAcknowledged(context)
    }

    @After
    fun tearDown() {
        TestHostileContentProvider.reset()
    }

    @Test
    fun pickerForwardingDoesNotTouchContentResolverOnMainThread() {
        TestHostileContentProvider.bytesToServe = jpeg(1)
        TestHostileContentProvider.getTypeDelayMillis = SLOW_PROVIDER_MILLIS
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val started = System.currentTimeMillis()
            scenario.onActivity { it.forwardPickedUriForTest(TestHostileContentProvider.uriFor("slow-type.jpg")) }
            val forwardMillis = System.currentTimeMillis() - started
            assertTrue("forwarding blocked the main thread for ${forwardMillis}ms", forwardMillis < RESPONSIVE_MILLIS)

            assertTrue("picked image never reached preview", device.wait(Until.hasObject(By.text("Save")), WAIT_MS))
            device.findObject(By.text("Cancel"))?.click()
        }
    }

    @Test
    fun unsupportedPickedTypeIsExplainedAndNothingIsStaged() {
        TestHostileContentProvider.bytesToServe = jpeg(2)
        TestHostileContentProvider.mimeTypeToReport = "image/webp"
        val stagesBefore = stageFiles()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.forwardPickedUriForTest(TestHostileContentProvider.uriFor("picked.webp")) }
            assertTrue(
                "unsupported format was not explained",
                device.wait(Until.hasObject(By.textContains("JPEG and PNG")), WAIT_MS),
            )
        }
        assertEquals("nothing may be staged", stagesBefore, stageFiles())
    }

    @Test
    fun pickerRouteIsRecordedAsPhotoPicker() {
        TestHostileContentProvider.bytesToServe = jpeg(3)
        val saved = saveThrough { scenario ->
            scenario.onActivity { it.forwardPickedUriForTest(TestHostileContentProvider.uriFor("picked.jpg")) }
        }
        assertEquals(IntakeKind.PHOTO_PICKER, saved)
    }

    /** F-34: an external sender claiming the picker route is recorded as a share. */
    @Test
    fun directShareClaimingThePickerIsRecordedAsShare() {
        TestHostileContentProvider.bytesToServe = jpeg(4)
        val saved = saveThrough { _ ->
            context.startActivity(
                Intent(Intent.ACTION_SEND).apply {
                    setClassName(context.packageName, IntakeActivity::class.java.name)
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, TestHostileContentProvider.uriFor("spoof.jpg"))
                    putExtra(IntakeActivity.EXTRA_INTAKE_KIND, IntakeKind.PHOTO_PICKER.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
        assertEquals(IntakeKind.SHARE, saved)
    }

    /** P1-02-R3: whichever route the pinned contract picks on this device, it asks for one image only. */
    @Test
    fun pickerContractRequestsASingleImageOnEveryFallbackRoute() {
        val intent = ActivityResultContracts.PickVisualMedia().createIntent(
            context,
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
        val allowed = setOf(
            "android.provider.action.PICK_IMAGES",
            "androidx.activity.result.contract.action.PICK_IMAGES",
            Intent.ACTION_OPEN_DOCUMENT,
        )
        assertTrue("unexpected picker action ${intent.action}", intent.action in allowed)
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
            assertEquals("image/*", intent.type)
        }
        assertEquals(
            "no persistable grant is requested",
            0,
            intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }

    /** Starts from MainActivity, lets [start] launch intake, taps Save, returns the saved row's route. */
    private fun saveThrough(start: (ActivityScenario<MainActivity>) -> Unit): IntakeKind = runBlocking {
        val access = application.vault() as VaultAccess.Ready
        val before = access.viewRepository.observeVisibleSources().first().map { it.id }.toSet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            start(scenario)
            val save = device.wait(Until.findObject(By.text("Save")), WAIT_MS)
            assertTrue("preview never appeared", save != null)
            save.click()
            assertTrue("save never finished", device.wait(Until.hasObject(By.text("Saved on this device")), WAIT_MS))
        }
        val saved = access.viewRepository.observeVisibleSources().first().single { it.id !in before }
        access.deletionRepository.deleteSource(saved.id)
        saved.intakeKind
    }

    private fun stageFiles(): Set<String> =
        VaultPaths(application).artefactsDir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".stage") }.toSet()

    private fun jpeg(variant: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF000000.toInt() or (variant * 0x0A0B0C))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private companion object {
        const val SLOW_PROVIDER_MILLIS = 6_000L
        const val RESPONSIVE_MILLIS = 1_000L
        const val WAIT_MS = 30_000L
    }
}
