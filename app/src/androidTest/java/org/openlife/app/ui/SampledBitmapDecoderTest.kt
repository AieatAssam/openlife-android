package org.openlife.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.test.StrictModeRule
import org.openlife.vault.model.Orientation

@RunWith(AndroidJUnit4::class)
class SampledBitmapDecoderTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @Test
    fun rotate90ProducesSwappedDimensions() {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()

        val decoded = SampledBitmapDecoder.decode(output.toByteArray(), Orientation.ROTATE_90)
            ?: error("synthetic bitmap did not decode")
        try {
            assertEquals(40, decoded.width)
            assertEquals(80, decoded.height)
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun thumbnailDecodeUsesRgb565WhenRequested() {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
        bitmap.recycle()

        val decoded = SampledBitmapDecoder.decode(
            output.toByteArray(),
            Orientation.NORMAL,
            maxPixels = 80L * 40L,
            preferredConfig = Bitmap.Config.RGB_565,
        ) ?: error("synthetic bitmap did not decode")
        try {
            assertEquals(Bitmap.Config.RGB_565, decoded.config)
        } finally {
            decoded.recycle()
        }
    }
}
