package org.openlife.app.ui

import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import org.openlife.app.BuildConfig
import java.lang.ref.WeakReference

/**
 * Test hook for A-06 (P1-19-R13): records the decoded viewer and preview
 * bitmaps by weak reference (debug builds only), so an instrumented test can
 * ask, while the activity is stopped, whether any is still alive and not
 * recycled. Holds no strong reference and no content.
 */
internal object DecodedBitmapProbe {
    private val tracked = mutableListOf<WeakReference<Bitmap>>()

    fun track(bitmap: Bitmap) {
        if (!BuildConfig.DEBUG) return
        synchronized(tracked) {
            tracked.removeAll { it.get() == null }
            tracked += WeakReference(bitmap)
        }
    }

    /** Decoded bitmaps that are still reachable and not recycled. */
    @VisibleForTesting
    internal fun liveCountForTest(): Int = synchronized(tracked) {
        tracked.count { reference -> reference.get()?.isRecycled == false }
    }
}
