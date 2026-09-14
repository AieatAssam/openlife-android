package org.openlife.app.intake

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Debug-only content provider for adversarial intake testing (design §13:
 * "Use a test-only content provider for mutable, oversized and failing
 * streams; it must not ship in release"). Declared only in
 * `src/debug/AndroidManifest.xml`, so a release build never contains it.
 *
 * Instrumented tests running in the same process set the companion state
 * directly before launching an intent referencing [uriFor], then reset it
 * in their teardown.
 *
 * Deliberately does NOT simulate "provider never responds" via a real,
 * unwritten OS pipe closed from another thread: an earlier version did
 * exactly that, and closing a file descriptor that another thread is
 * blocked reading is a known-hazardous race (the closed fd number can be
 * reused before the kernel has fully unblocked the original read, so an
 * unrelated later file open in the same process can silently inherit a
 * corrupted or still-in-use descriptor). That test made an *unrelated*,
 * later instrumented test in the same process hang - found by running the
 * suite repeatedly, not by inspection. `BoundedStreamReaderTest`
 * (`:vault:test`, pure JVM) verifies the same cooperative-cancellation
 * behaviour with a plain in-memory `InputStream`, with no real file
 * descriptor involved.
 */
class TestHostileContentProvider : ContentProvider() {

    companion object {
        // Deliberately NOT nested under "org.openlife." - IntakeIntentValidator
        // rejects any authority starting with "$ownPackageName." as pointing
        // back into the app (see IntakeIntentValidatorTest
        // .anotherAppWithASimilarLookingAuthorityIsNotRejected for why that
        // check is conservative). An earlier authority here
        // ("org.openlife.debug.testprovider") tripped that exact check and
        // made every accept-path instrumented test time out waiting for a
        // "Prepared" status that a same-activity synchronous rejection had
        // already overwritten before the test ever looked - found by running
        // IntakeActivityTest, not by inspection.
        const val AUTHORITY = "net.openlifetest.hostileprovider"

        @Volatile var bytesToServe: ByteArray = ByteArray(0)

        @Volatile var mimeTypeToReport: String? = "image/jpeg"

        @Volatile var failOpen: Boolean = false

        /** If true, every open after the first returns zeroed-out bytes of the same length. */
        @Volatile var mutateAfterFirstOpen: Boolean = false

        private var openCount = 0

        fun reset() {
            bytesToServe = ByteArray(0)
            mimeTypeToReport = "image/jpeg"
            failOpen = false
            mutateAfterFirstOpen = false
            openCount = 0
        }

        fun uriFor(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (failOpen) throw FileNotFoundException("simulated provider failure")

        val bytes = if (mutateAfterFirstOpen && openCount > 0) {
            ByteArray(bytesToServe.size)
        } else {
            bytesToServe
        }
        openCount++

        val file = File.createTempFile("hostile", ".tmp", contextOrThrow().cacheDir)
        FileOutputStream(file).use { it.write(bytes) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = mimeTypeToReport

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun contextOrThrow() = checkNotNull(context) { "provider not attached" }
}
