package org.openlife.app.ui

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.os.Debug
import android.util.Log
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.OpenLifeApp
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState

@RunWith(AndroidJUnit4::class)
class ListMemoryBudgetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<OpenLifeApp>()

    @Test
    fun runningLowTrimClearsRegisteredSensitiveContent() {
        var clearCount = 0
        val unregister = application.registerSensitiveContentClearer { clearCount++ }
        try {
            application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE)
            assertTrue(clearCount == 0)

            application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            assertTrue(clearCount == 1)

            application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            assertTrue(clearCount == 2)
        } finally {
            unregister()
        }
    }

    @Test
    fun twoHundredSourcesScrollWithinBudget() {
        val sources = (0 until 200).map { index ->
            Source(
                id = UUID.randomUUID(),
                state = SourceState.READY,
                importedAt = index.toLong(),
                intakeKind = IntakeKind.SHARE,
                mimeType = ImageFormat.JPEG,
                byteCount = 1L,
                sha256 = null,
                width = 200,
                height = 200,
                orientation = null,
                wrappedDek = null,
                artefactVersion = null,
            )
        }
        val cache = SensitiveContentCache<UUID, Bitmap?>(
            onEvict = { bitmap ->
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            },
            sizeOf = { bitmap -> bitmap?.allocationByteCount?.toLong() ?: 0L },
        )
        val generation = cache.generation()
        val pssBefore = Debug.getPss()

        try {
            composeRule.setContent {
                SourceListScreen(
                    state = SourceListUiState.Loaded(sources),
                    loadThumbnail = { sourceId ->
                        cache.get(sourceId) ?: Bitmap.createBitmap(
                            200,
                            200,
                            Bitmap.Config.RGB_565,
                        ).also { bitmap ->
                            cache.put(sourceId, bitmap, generation)
                        }
                    },
                    onOpen = {},
                    onDelete = {},
                    onImportFromPhotoPicker = {},
                )
            }
            composeRule.waitForIdle()
            val list = composeRule.onNode(hasScrollAction())
            repeat(60) {
                list.performTouchInput { swipeUp() }
                composeRule.waitForIdle()
            }
            val pssAfter = Debug.getPss()
            Log.i(
                "OpenLifeMemoryTest",
                "PSS_KiB before=$pssBefore after=$pssAfter delta=${pssAfter - pssBefore}",
            )

            assertTrue(
                "PSS delta exceeded 60 MiB: before=$pssBefore KiB after=$pssAfter KiB",
                pssAfter - pssBefore < 60L * 1024L,
            )
            assertFalse("the cache must evict thumbnails at the configured bound", cache.contains(sources.first().id))
        } finally {
            cache.clear()
        }
    }
}
