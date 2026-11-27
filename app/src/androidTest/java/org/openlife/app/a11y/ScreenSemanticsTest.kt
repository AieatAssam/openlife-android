package org.openlife.app.a11y

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.openlife.app.lock.AppLockOfferScreen
import org.openlife.app.lock.LockAvailability
import org.openlife.app.lock.AppLockPolicy
import org.openlife.app.lock.LockScreen
import org.openlife.app.settings.AboutScreen
import org.openlife.app.settings.AboutState
import org.openlife.app.settings.AppLockSettings
import org.openlife.app.settings.BuildIdentity
import org.openlife.app.settings.SettingsScreen
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.DeleteConfirmationDialog
import org.openlife.app.ui.FirstRunExplanationScreen
import org.openlife.app.ui.IntakeRejectionMessage
import org.openlife.app.ui.IntakeScreen
import org.openlife.app.ui.IntakeUiState
import org.openlife.app.ui.OcrUiState
import org.openlife.app.ui.SourceListScreen
import org.openlife.app.ui.SourceListUiState
import org.openlife.app.ui.VaultUnavailableScreen
import org.openlife.app.ui.ViewerScreen
import org.openlife.app.ui.search.SearchScreen
import org.openlife.app.ui.search.SearchUiState
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrFailureReason
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.repository.ReadyReadResult
import org.openlife.vault.repository.VaultResetResult
import org.openlife.vault.search.SearchHit
import org.openlife.vault.storage.VaultUnavailableCause

/**
 * P1-10-R1/R2/R5 (tdd.red): every screen, with synthetic state, passes the
 * shared [AccessibilityRule] at the default size, at 2.0x font scale and at
 * the largest display size; at the large sizes every action is reachable and
 * no text is clipped. Per screen it also asserts a pane title, a heading,
 * and a polite live region where the screen shows a status change.
 *
 * One composition is swapped between screens (as the golden test does), so
 * one test reports every screen's issues at once.
 *
 * Status: implemented; unverified (gap — pending physical-device run). No
 * emulator or device was used to write it (owner instruction 2026-11-24).
 */
@RunWith(AndroidJUnit4::class)
class ScreenSemanticsTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val accessibility = AccessibilityRule(composeRule)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(StrictModeRule()).around(composeRule).around(accessibility)

    private val current = mutableStateOf<Pair<ScreenCase, SizeConfig>?>(null)

    @Test
    fun everyScreenPassesAccessibilityChecks() {
        composeRule.setContent {
            val (case, size) = current.value ?: return@setContent
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density * size.densityScale, fontScale = size.fontScale),
            ) {
                OpenLifeTheme { case.content() }
            }
        }
        val failures = mutableListOf<String>()
        SIZES.forEach { size ->
            cases().forEach { case ->
                composeRule.runOnIdle { current.value = case to size }
                composeRule.waitForIdle()
                val where = "${case.name} @ ${size.name}"
                failures += accessibility.findIssues().map { "$where: $it" }
                failures += expectations(case).map { "$where: $it" }
                if (size != SizeConfig.DEFAULT) {
                    failures += unreachableActions().map { "$where: $it" }
                    failures += accessibility.findClippedText().map { "$where: $it" }
                }
            }
        }
        assertTrue("accessibility failures:\n" + failures.joinToString("\n"), failures.isEmpty())
        accessibility.check("final screen")
    }

    /** The viewer announces the selected evidence span as a polite live region (P2-06, kept by P1-10). */
    @Test
    fun viewerEvidenceAnnouncementIsPoliteLiveRegion() {
        val source = readySource()
        val span = span(source, 0, "Synthetic appointment 14 October", grounded = true)
        composeRule.setContent {
            OpenLifeTheme {
                ViewerScreen(
                    source = source,
                    loadContent = { ReadyReadResult.Loaded(tinyJpeg()) },
                    onBack = {},
                    onDeleteRequested = {},
                    ocrState = OcrUiState.Ready(source.id, span.revisionId, listOf(span)),
                    initialSelectedSpanId = span.id,
                )
            }
        }
        val node = composeRule.onNodeWithTag("viewer_span_announcement").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        accessibility.check("viewer with a selected span")
    }

    /** Pane title, heading and (for status states) a polite live region on the current screen. */
    private fun expectations(case: ScreenCase): List<String> {
        val all = composeRule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        return buildList {
            if (case.paneTitle && all.none { it.config.contains(SemanticsProperties.PaneTitle) }) add("no pane title")
            if (case.heading && composeRule.onAllNodes(isHeading(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
                add("no heading")
            }
            if (case.liveRegion && all.none { it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite }) {
                add("status shown without a polite live region")
            }
        }
    }

    /** At large sizes every action can be scrolled to and is displayed. */
    private fun unreachableActions(): List<String> {
        val actions = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
        return actions.mapNotNull { node ->
            val matcher = SemanticsMatcher("node ${node.id}") { it.id == node.id }
            runCatching {
                val interaction = composeRule.onNode(matcher)
                if (composeRule.onAllNodes(matcher and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
                    interaction.performScrollTo()
                }
                interaction.assertIsDisplayed()
            }.exceptionOrNull()?.let { "unreachable action ${AccessibilityRule.label(node)}: ${it.message?.lines()?.first()}" }
        }
    }

    private class ScreenCase(
        val name: String,
        val paneTitle: Boolean = true,
        val heading: Boolean = true,
        val liveRegion: Boolean = false,
        val content: @Composable () -> Unit,
    )

    private enum class SizeConfig(val fontScale: Float, val densityScale: Float) {
        DEFAULT(1f, 1f),
        FONT_2X(fontScale = 2f, densityScale = 1f),
        FONT_2X_LARGEST_DISPLAY(fontScale = 2f, densityScale = LARGEST_DISPLAY_SIZE),
    }

    @Suppress("LongMethod")
    private fun cases(): List<ScreenCase> {
        val ready = readySource()
        val corrupt = readySource().copy(id = UUID.randomUUID(), state = SourceState.CORRUPT)
        val deleting = readySource().copy(id = UUID.randomUUID(), state = SourceState.DELETING)
        val grounded = span(ready, 0, "Synthetic appointment 14 October", grounded = true)
        val rejected = span(ready, 1, "Synthetic clinic room 3", grounded = true)
        val loose = span(ready, 2, "Synthetic reference 0042", grounded = false)
        val ocrReady = OcrUiState.Ready(
            ready.id,
            grounded.revisionId,
            listOf(grounded, rejected, loose),
            corrections = mapOf(loose.id to "Synthetic reference 0043"),
            spanReviews = mapOf(rejected.id to OcrReviewState.REJECTED),
        )
        fun viewer(source: Source, ocr: OcrUiState, load: ReadyReadResult = ReadyReadResult.Loaded(tinyJpeg())) =
            @Composable {
                ViewerScreen(
                    source = source,
                    loadContent = { load },
                    onBack = {},
                    onDeleteRequested = {},
                    ocrState = ocr,
                    initialSelectedSpanId = grounded.id.takeIf { ocr is OcrUiState.Ready },
                )
            }
        fun intake(state: IntakeUiState) = @Composable {
            IntakeScreen(state = state, onSave = {}, onCancel = {}, onDone = {})
        }
        fun list(state: SourceListUiState) = @Composable {
            SourceListScreen(
                state = state,
                loadThumbnail = { null },
                onOpen = {},
                onDelete = {},
                onImportFromPhotoPicker = {},
                onOpenSearch = {},
            )
        }
        val preview = IntakeUiState.Preview(
            sourceId = UUID.randomUUID(),
            format = ImageFormat.JPEG,
            width = 32,
            height = 24,
            byteCount = 2048,
            previewBytes = tinyJpeg(),
            intakeKind = IntakeKind.PHOTO_PICKER,
        )
        return listOf(
            ScreenCase("list-empty", content = list(SourceListUiState.Loaded(emptyList()))),
            ScreenCase("list-items", content = list(SourceListUiState.Loaded(listOf(ready, corrupt, deleting)))),
            ScreenCase("list-loading", heading = true, liveRegion = true, content = list(SourceListUiState.Loading)),
            ScreenCase("vault-unavailable", paneTitle = false) {
                VaultUnavailableScreen(VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE, onRetry = {}, onOpenResetSettings = {})
            },
            ScreenCase("viewer-ocr-ready", content = viewer(ready, ocrReady)),
            ScreenCase("viewer-ocr-idle", content = viewer(ready, OcrUiState.Idle)),
            ScreenCase("viewer-ocr-running", liveRegion = true, content = viewer(ready, OcrUiState.Running(ready.id))),
            ScreenCase(
                "viewer-ocr-failed",
                liveRegion = true,
                content = viewer(ready, OcrUiState.Failed(ready.id, OcrFailureReason.TIMEOUT)),
            ),
            ScreenCase("viewer-ocr-stale", liveRegion = true, content = viewer(ready, OcrUiState.Stale(ready.id))),
            ScreenCase("viewer-transient", content = viewer(ready, OcrUiState.Idle, ReadyReadResult.Transient)),
            ScreenCase("viewer-unreadable", content = viewer(corrupt, OcrUiState.Idle)),
            ScreenCase("intake-preparing", heading = false, liveRegion = true, content = intake(IntakeUiState.Preparing)),
            ScreenCase("intake-preview", heading = false, content = intake(preview)),
            ScreenCase("intake-saving", heading = false, liveRegion = true, content = intake(IntakeUiState.Saving(preview.sourceId))),
            ScreenCase("intake-saved", heading = false, liveRegion = true, content = intake(IntakeUiState.Saved(preview.sourceId))),
            ScreenCase(
                "intake-duplicate",
                heading = false,
                liveRegion = true,
                content = intake(IntakeUiState.Duplicate(UUID.randomUUID())),
            ),
            ScreenCase(
                "intake-rejected",
                heading = false,
                liveRegion = true,
                content = intake(IntakeUiState.Rejected(IntakeRejectionMessage.entries.first())),
            ),
            ScreenCase("intake-failed", heading = false, liveRegion = true, content = intake(IntakeUiState.Failed)),
            ScreenCase("first-run") { FirstRunExplanationScreen(onContinue = {}) },
            ScreenCase("lock") {
                LockScreen(title = "Locked", body = "Unlock to continue.", note = org.openlife.app.R.string.lock_not_verified, action = "Unlock", onAction = {})
            },
            ScreenCase("lock-offer") { AppLockOfferScreen(onEnable = { LockAvailability.AVAILABLE }, onSkip = {}) },
            ScreenCase("settings") {
                SettingsScreen(
                    onResetVault = { VaultResetResult.COMPLETED },
                    onResetComplete = {},
                    onVerifyAll = { null },
                    appLock = AppLockSettings(AppLockPolicy(enabled = true), { LockAvailability.AVAILABLE }, {}),
                )
            },
            ScreenCase("about") {
                AboutScreen(
                    state = AboutState(identity = BuildIdentity("0.0.0-synthetic", 1, "synthetic", listOf("AA:BB"))),
                )
            },
            ScreenCase("search-idle", liveRegion = true) {
                SearchScreen(query = "", state = SearchUiState.Idle, onQueryChange = {}, onOpenResult = {}, onBack = {})
            },
            ScreenCase("search-results") {
                SearchScreen(
                    query = "synthetic",
                    state = SearchUiState.Results(
                        listOf(SearchHit(ready.id, grounded.revisionId, grounded.id, grounded.text, false, listOf(0..8))),
                    ),
                    onQueryChange = {},
                    onOpenResult = {},
                    onBack = {},
                )
            },
            ScreenCase("delete-dialog", paneTitle = false) {
                DeleteConfirmationDialog(itemLabel = "Imported 1 Jan 1970", onConfirm = {}, onDismiss = {})
            },
        )
    }

    private fun span(source: Source, ordinal: Int, text: String, grounded: Boolean) = OcrSpan(
        id = UUID.randomUUID(),
        revisionId = REVISION,
        ordinal = ordinal,
        text = text,
        confidence = null,
        coordinateSystem = OcrCoordinateSystem.SOURCE_PIXELS,
        evidenceRegion = if (grounded) OcrEvidenceRegion(2, 2 + ordinal * 8, 30, 8 + ordinal * 8) else null,
    ).also { check(source.width != null) }

    private fun readySource() = Source(
        id = UUID.randomUUID(),
        state = SourceState.READY,
        importedAt = 1L,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = 32,
        height = 24,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )

    private fun tinyJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    private companion object {
        /** Android's "Largest" display-size step is about 1.3x the default density. */
        const val LARGEST_DISPLAY_SIZE = 1.3f
        val REVISION: UUID = UUID.randomUUID()
        val SIZES = SizeConfig.entries
    }
}
