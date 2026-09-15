package org.openlife.vault.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.Orientation

@RunWith(AndroidJUnit4::class)
class MlKitOcrEngineTest {
    @Test
    fun bundledLatinEngineExtractsSyntheticTextWithoutAProvider(): Unit = runBlocking {
        val bitmap = Bitmap.createBitmap(800, 240, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(
                "OpenLife local text",
                32f,
                150f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 64f
                },
            )
        }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()

        val result = MlKitOcrEngine().extract(
            OcrEngineInput(
                bytes = output.toByteArray(),
                width = 800,
                height = 240,
                orientation = Orientation.NORMAL,
                mimeType = ImageFormat.PNG,
            ),
        )

        assertTrue(result.spans.any { it.text.contains("OpenLife", ignoreCase = true) })
        assertTrue(result.spans.any { it.region != null })
    }
}
