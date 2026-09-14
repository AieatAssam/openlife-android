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

        /**
         * A real, bounded `Thread.sleep` before `openFile` returns, for
         * C0-17's "slow provider" coverage (design §12's 15s provider-read
         * deadline). Deliberately a plain bounded sleep, not an unwritten
         * pipe held open indefinitely - the earlier version of "slow
         * provider" support used exactly that and caused the real,
         * documented fd-corruption hang investigated during Stage 7/8 (see
         * the class doc below). A bounded sleep always returns on its own
         * and carries none of that risk.
         */
        @Volatile var artificialDelayMillis: Long = 0

        private var openCount = 0

        fun reset() {
            bytesToServe = ByteArray(0)
            mimeTypeToReport = "image/jpeg"
            failOpen = false
            mutateAfterFirstOpen = false
            artificialDelayMillis = 0
            openCount = 0
        }

        fun uriFor(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")

        /**
         * A real, multi-megabyte decodable JPEG, generated on demand rather
         * than held in a static field - so a manual, host-driven real
         * process-kill fault-injection run (`adb shell am start ... -d
         * ${LARGE_FIXTURE_NAME}`, C0-09/C0-10's "true" leg) doesn't need any
         * prior same-process instrumented test to seed state, and works
         * across a real `kill -9` and app respawn. Same-app, same-uid
         * access to this unexported provider needs no URI permission grant
         * at all, unlike a cross-app `content://media/...` URI - the actual
         * problem this replaced during Stage 8 fault-injection testing.
         */
        const val LARGE_FIXTURE_NAME = "large-fault-injection.jpg"

        /**
         * "noise-<w>x<h>.jpg" generates a real decodable JPEG at the
         * requested dimensions on demand - used by [LARGE_FIXTURE_NAME]
         * (fixed at 4000x3000) and by Stage 8's §12 performance pass, which
         * needed a ~4 MiB fixture (the design's own baseline size) rather
         * than the 16 MiB-adjacent one C0-09/C0-10 wanted.
         */
        private fun generateNoiseJpeg(width: Int, height: Int): ByteArray {
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val random = java.util.Random(42)
            val row = IntArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) row[x] = random.nextInt() or -0x1000000
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            bitmap.recycle()
            return out.toByteArray()
        }

        private fun generateLargeJpeg(): ByteArray = generateNoiseJpeg(4000, 3000)

        private val noisePattern = Regex("""noise-(\d+)x(\d+)\.jpg""")
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (failOpen) throw FileNotFoundException("simulated provider failure")
        // "slow-<millis>.jpg" lets a host-driven `adb shell am start` encode
        // its own delay directly in the URI, the same reason
        // LARGE_FIXTURE_NAME needs no prior same-process state-setting call.
        uri.lastPathSegment?.removePrefix("slow-")?.removeSuffix(".jpg")?.toLongOrNull()?.let {
            Thread.sleep(it)
        }
        if (artificialDelayMillis > 0) Thread.sleep(artificialDelayMillis)

        val noiseMatch = uri.lastPathSegment?.let { noisePattern.matchEntire(it) }
        val bytes = if (uri.lastPathSegment == LARGE_FIXTURE_NAME || uri.lastPathSegment?.startsWith("slow-") == true) {
            generateLargeJpeg()
        } else if (noiseMatch != null) {
            generateNoiseJpeg(noiseMatch.groupValues[1].toInt(), noiseMatch.groupValues[2].toInt())
        } else if (mutateAfterFirstOpen && openCount > 0) {
            ByteArray(bytesToServe.size)
        } else {
            bytesToServe
        }
        openCount++

        val file = File.createTempFile("hostile", ".tmp", contextOrThrow().cacheDir)
        FileOutputStream(file).use { it.write(bytes) }
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        // Unlink immediately - the open fd keeps the (test) bytes readable
        // until closed, but nothing is left sitting in cacheDir afterward.
        // Found by C0-13's on-device cache inspection: a leftover
        // "hostileNNN.tmp" file from an earlier run was still present.
        file.delete()
        return fd
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
