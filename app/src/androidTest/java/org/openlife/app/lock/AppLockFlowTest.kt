package org.openlife.app.lock

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.UUID
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
import org.openlife.app.intake.IntakeActivity
import org.openlife.app.intake.TestHostileContentProvider
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.FirstRunPreferences
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.repository.PrepareResult
import org.openlife.vault.repository.SaveResult

/**
 * P1-07: with the app lock on, nothing content-bearing is composed and no
 * item bytes are decrypted or shared streams opened until the user has
 * authenticated; a device that cannot verify stays locked.
 */
@RunWith(AndroidJUnit4::class)
class AppLockFlowTest {
    @get:Rule
    val strictMode = StrictModeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as OpenLifeApp
    private val device = UiDevice.getInstance(instrumentation)
    private val savedIds = mutableListOf<UUID>()

    @Before
    fun setUp() {
        FirstRunPreferences.setAcknowledged(context)
        TestHostileContentProvider.reset()
    }

    @After
    fun tearDown() {
        // The lock is process-wide: never leave it on for the next test class.
        app.resetAppLockForTest()
        TestHostileContentProvider.reset()
        runBlocking {
            val access = app.vault() as? VaultAccess.Ready ?: return@runBlocking
            savedIds.forEach { access.deletionRepository.deleteSource(it) }
        }
    }

    @Test
    fun lockedMainActivityComposesNoContentAndNoBytesAreLoaded() {
        val id = saveSyntheticSource()
        val authenticator = ScriptedAuthenticator()
        app.setAppLockForTest(authenticator, AppLockPolicy(enabled = true))
        val readsBefore = app.contentReads.get()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SOURCE_ID, id.toString())

        ActivityScenario.launch<MainActivity>(intent).use {
            assertTrue("lock screen not shown", device.wait(Until.hasObject(By.text(LOCKED_TITLE)), WAIT_MS))
            authenticator.awaitRequest()
            device.waitForIdle()
            assertFalse("list composed while locked", device.hasObject(By.desc("More options")))
            assertEquals("bytes read while locked", readsBefore, app.contentReads.get())

            authenticator.complete(AuthOutcome.Succeeded)

            // The requested item opens after unlock, and only now are its bytes read.
            awaitTrue("no content read after unlock") { app.contentReads.get() > readsBefore }
            assertTrue(device.wait(Until.gone(By.text(LOCKED_TITLE)), WAIT_MS))
        }
    }

    @Test
    fun shareIntentWhileLockedOpensStreamOnlyAfterUnlock() {
        val authenticator = ScriptedAuthenticator()
        app.setAppLockForTest(authenticator, AppLockPolicy(enabled = true))
        TestHostileContentProvider.bytesToServe = syntheticJpegBytes(seed = 7_001)
        val intent = Intent(Intent.ACTION_SEND).apply {
            component = ComponentName(context.packageName, IntakeActivity::class.java.name)
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, TestHostileContentProvider.uriFor("locked-share.jpg"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        ActivityScenario.launch<IntakeActivity>(intent).use { scenario ->
            assertTrue("lock screen not shown", device.wait(Until.hasObject(By.text(LOCKED_TITLE)), WAIT_MS))
            authenticator.awaitRequest()
            device.waitForIdle()
            assertEquals("stream opened while locked", 0, TestHostileContentProvider.openCountForTest)

            authenticator.complete(AuthOutcome.Succeeded)

            awaitTrue("share did not continue to preview") { statusOf(scenario).startsWith("Prepared") }
            assertEquals(1, TestHostileContentProvider.openCountForTest)
            scenario.onActivity { it.cancelForTest() }
            awaitTrue("import not cancelled") {
                scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED || statusOf(scenario) == "Cancelled"
            }
        }
    }

    @Test
    fun enablingWithoutEnrolledCredentialIsRefusedWithExplanation() {
        app.setAppLockForTest(
            ScriptedAuthenticator(available = LockAvailability.NOT_ENROLLED),
            AppLockPolicy(enabled = false),
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            device.wait(Until.findObject(By.desc("More options")), WAIT_MS)!!.click()
            device.wait(Until.findObject(By.text("Settings")), WAIT_MS)!!.click()
            device.wait(Until.findObject(By.text("App lock")), WAIT_MS)!!.click()

            assertTrue(
                "no explanation of why the lock cannot be turned on",
                device.wait(Until.hasObject(By.textContains("needs a screen lock")), WAIT_MS),
            )
            assertFalse("the lock was enabled anyway", AppLockPolicyStore.read(context).enabled)
        }
    }

    @Test
    fun credentialRemovedWhileLockEnabledStaysLockedWithInstructions() {
        val authenticator = ScriptedAuthenticator(available = LockAvailability.NOT_ENROLLED)
        app.setAppLockForTest(authenticator, AppLockPolicy(enabled = true))

        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "no cannot-verify explanation",
                device.wait(Until.hasObject(By.text(CANNOT_VERIFY_TITLE)), WAIT_MS),
            )
            device.waitForIdle()
            assertFalse("content shown without verification", device.hasObject(By.desc("More options")))
            assertEquals("prompted although nothing can verify", 0, authenticator.requests.get())
        }
    }

    private fun saveSyntheticSource(): UUID = runBlocking {
        val access = app.vault() as VaultAccess.Ready
        val prepared = access.importRepository.prepareImport(
            ByteArrayInputStream(syntheticJpegBytes(seed = System.nanoTime())),
            "image/jpeg",
            IntakeKind.SHARE,
        ) as PrepareResult.Prepared
        assertTrue(access.importRepository.saveImport(prepared.sourceId) is SaveResult.Saved)
        prepared.sourceId.also(savedIds::add)
    }

    private fun syntheticJpegBytes(seed: Long): ByteArray {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(WIDTH * HEIGHT) { random.nextInt() or OPAQUE }, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun statusOf(scenario: ActivityScenario<IntakeActivity>): String {
        var status = ""
        scenario.onActivity { status = it.currentStatusForTest() }
        return status
    }

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError(message)
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val LOCKED_TITLE = "OpenLife is locked"
        const val CANNOT_VERIFY_TITLE = "OpenLife can't verify you"
        const val WAIT_MS = 30_000L
        const val POLL_MS = 100L
        const val WIDTH = 64
        const val HEIGHT = 48
        const val JPEG_QUALITY = 90
        const val OPAQUE = 0xFF000000.toInt()
    }
}
