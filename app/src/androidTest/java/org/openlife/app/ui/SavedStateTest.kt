package org.openlife.app.ui

import androidx.compose.ui.test.onNodeWithTag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.navigation.Routes
import org.openlife.app.test.FakeAppContainer
import org.openlife.app.test.StrictModeRule
import org.openlife.app.test.syntheticJpeg
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRevision
import org.openlife.vault.ocr.OcrRevisionState
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.ocr.OcrSpanView
import org.openlife.vault.ocr.OcrView
import org.openlife.vault.repository.ReadyReadResult
import java.util.UUID

/**
 * P1-20 / review N-05: with the viewer and its OCR panel open and a span
 * selected, the activity's saved-instance bundle, walked recursively, holds
 * no span text and no byte array larger than an id. This hosts the real
 * graph over [FakeAppContainer] in a ComponentActivity. It uses
 * createAndroidComposeRule, because createEmptyComposeRule does not register
 * the activity's content, so the waits below could never see its nodes
 * (device run 4). The search-query test on the real MainActivity is in
 * [SearchQuerySavedStateTest].
 *
 * Status: implemented; unverified (gap - pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class SavedStateTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var largestByteArray = 0

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun acknowledgeFirstRun() {
        FirstRunPreferences.setAcknowledged(instrumentation.targetContext)
    }

    @Test
    fun viewerWithOcrPanelSavesNoSpanTextOrBytes() {
        val sourceId = UUID(0L, 51L)
        val revisionId = UUID(0L, 52L)
        val span = OcrSpan(
            UUID(0L, 53L),
            revisionId,
            0,
            SPAN_TEXT,
            null,
            OcrCoordinateSystem.SOURCE_PIXELS,
            // A span without an evidence region is not selectable, so the viewer would never announce it.
            OcrEvidenceRegion(0, 0, 50, 20),
        )
        val container = FakeAppContainer(
            initialSources = listOf(source(sourceId)),
            readContent = { ReadyReadResult.Loaded(syntheticJpeg()) },
            ocrView = { OcrView(revision(revisionId, sourceId), listOf(OcrSpanView(span, null)), null, emptyList()) },
        )
        try {
            lateinit var navController: NavHostController
            composeRule.setContent {
                navController = rememberNavController()
                OpenLifeTheme { OpenLifeNavHost(container, navController) }
            }
            composeRule.runOnIdle {
                navController.navigate(Routes.ListDetail(sourceId.toString(), span.id.toString()))
            }
            composeRule.waitUntil(WAIT_MS) {
                composeRule.onNodeWithTag("viewer_span_announcement").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
            }
            composeRule.waitUntil(WAIT_MS) {
                composeRule.onAllNodesWithText(SPAN_TEXT, substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            val saved = Bundle()
            composeRule.runOnUiThread { instrumentation.callActivityOnSaveInstanceState(composeRule.activity, saved) }

            val dump = StringBuilder().also { describe(saved, it) }.toString()
            assertTrue("the selected span id is saved", dump.contains(span.id.toString()))
            assertFalse("span text is in saved state", dump.contains(SPAN_TEXT))
            assertTrue("a byte array larger than an id was saved: $largestByteArray", largestByteArray <= ID_BYTES)
        } finally {
            container.close()
        }
    }

    private fun source(id: UUID) = Source(
        id = id,
        state = SourceState.READY,
        importedAt = 1L,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = 64,
        height = 48,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )

    private fun revision(id: UUID, sourceId: UUID) = OcrRevision(
        id = id,
        sourceId = sourceId,
        state = OcrRevisionState.READY,
        engineId = "test-engine",
        modelVersion = "1",
        orientation = Orientation.NORMAL,
        sourceDigest = ByteArray(32) { 1 },
        startedAt = 1L,
        extractedAt = 2L,
        reviewState = OcrReviewState.UNREVIEWED,
        failureReason = null,
        charCount = SPAN_TEXT.length,
        spanCount = 1,
    )

    private fun describe(value: Any?, out: StringBuilder) {
        when (value) {
            is Bundle -> value.keySet().forEach { key ->
                out.append(key).append('=')
                @Suppress("DEPRECATION")
                describe(value.get(key), out)
                out.append(';')
            }
            is Array<*> -> value.forEach { describe(it, out) }
            is Iterable<*> -> value.forEach { describe(it, out) }
            is CharArray -> out.append(String(value))
            is ByteArray -> {
                largestByteArray = maxOf(largestByteArray, value.size)
                out.append(String(value, Charsets.UTF_8))
            }
            else -> out.append(value.toString())
        }
    }

    private companion object {
        const val WAIT_MS = 30_000L
        const val SPAN_TEXT = "Synthetic span qzxv"
        const val ID_BYTES = 16
    }
}
