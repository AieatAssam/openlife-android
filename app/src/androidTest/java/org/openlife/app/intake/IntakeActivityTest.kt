package org.openlife.app.intake

import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented because it drives the real exported activity with real
 * intents, per the C0-05 test row ("driving the exported activity directly
 * with hostile intents"). Every intent below sets an explicit component
 * naming [IntakeActivity] rather than relying on intent-filter resolution
 * — that is the point of this test: design §8 requires validating every
 * incoming intent regardless of the declared filter, since an exported
 * activity can be started directly with an arbitrary intent, which is
 * exactly what a normal implicit-intent launch would not exercise (it
 * would resolve through the system chooser, or fail to resolve at all for
 * an action/type IntakeActivity doesn't declare). Uses
 * [TestHostileContentProvider] (debug-only) as a real, readable
 * `content://` source for the accept-path cases.
 */
@RunWith(AndroidJUnit4::class)
class IntakeActivityTest {

    private val targetPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName

    private fun Intent.targetIntakeActivityDirectly(): Intent = apply {
        component = ComponentName(targetPackage, IntakeActivity::class.java.name)
    }

    private fun syntheticJpegBytes(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Before
    fun setUp() {
        TestHostileContentProvider.reset()
    }

    @After
    fun tearDown() {
        TestHostileContentProvider.reset()
    }

    private fun sendIntentFor(uri: Uri, mimeType: String = "image/jpeg"): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.targetIntakeActivityDirectly()

    @Test
    fun validShareImportsSuccessfully() {
        TestHostileContentProvider.bytesToServe = syntheticJpegBytes()
        val uri = TestHostileContentProvider.uriFor("a.jpg")

        ActivityScenario.launch<IntakeActivity>(sendIntentFor(uri)).use { scenario ->
            waitForStatusContaining(scenario, "Prepared")
        }
    }

    @Test
    fun multipleClipDataItemsAreRejectedWithoutTouchingTheProvider() {
        TestHostileContentProvider.bytesToServe = syntheticJpegBytes()
        val first = TestHostileContentProvider.uriFor("a.jpg")
        val second = TestHostileContentProvider.uriFor("b.jpg")

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            clipData = ClipData(ClipDescription("test", arrayOf("image/jpeg")), ClipData.Item(first)).apply {
                addItem(ClipData.Item(second))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.targetIntakeActivityDirectly()

        ActivityScenario.launch<IntakeActivity>(intent).use { scenario ->
            waitForStatusContaining(scenario, "Not imported")
        }
    }

    @Test
    fun fileSchemeUriIsRejected() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///sdcard/DCIM/photo.jpg"))
        }.targetIntakeActivityDirectly()

        ActivityScenario.launch<IntakeActivity>(intent).use { scenario ->
            waitForStatusContaining(scenario, "Not imported")
        }
    }

    @Test
    fun wrongActionIsRejected() {
        TestHostileContentProvider.bytesToServe = syntheticJpegBytes()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, TestHostileContentProvider.uriFor("a.jpg"))
        }.targetIntakeActivityDirectly()

        ActivityScenario.launch<IntakeActivity>(intent).use { scenario ->
            waitForStatusContaining(scenario, "Not imported")
        }
    }

    @Test
    fun changedBytesAfterOpenAreNotSilentlySubstituted() {
        // The provider serves the real bytes on the first read (used for
        // the saved snapshot) and would serve different (zeroed) bytes on
        // any subsequent read. Since prepareImport reads the stream
        // exactly once and never re-reads the provider afterward, this
        // proves there is no second read of the source to race (design
        // §11 C0-03: "Saved bytes equal preview snapshot, not later
        // provider content").
        TestHostileContentProvider.bytesToServe = syntheticJpegBytes()
        TestHostileContentProvider.mutateAfterFirstOpen = true
        val uri = TestHostileContentProvider.uriFor("a.jpg")

        ActivityScenario.launch<IntakeActivity>(sendIntentFor(uri)).use { scenario ->
            waitForStatusContaining(scenario, "Prepared")
        }
    }

    @Test
    fun unavailableProviderIsRejectedGracefully() {
        TestHostileContentProvider.failOpen = true
        val uri = TestHostileContentProvider.uriFor("missing.jpg")

        ActivityScenario.launch<IntakeActivity>(sendIntentFor(uri)).use { scenario ->
            waitForStatusContaining(scenario, "select it again")
        }
    }

    private fun waitForStatusContaining(scenario: ActivityScenario<IntakeActivity>, expected: String) {
        // Generous: first-access vault bootstrap (Keystore + SQLCipher) can
        // be slow on a loaded, single-core emulator.
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            var text: String? = null
            scenario.onActivity { activity ->
                text = activity.currentStatusForTest()
            }
            if (text?.contains(expected) == true) return
            Thread.sleep(100)
        }
        throw AssertionError("status never contained '$expected'; last check did not match in time")
    }
}
