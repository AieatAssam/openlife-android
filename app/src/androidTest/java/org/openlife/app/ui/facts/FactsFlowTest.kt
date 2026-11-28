package org.openlife.app.ui.facts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.R
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.navigation.Routes
import org.openlife.app.test.FakeAppContainer
import org.openlife.app.test.FakeFactsGateway
import org.openlife.app.test.StrictModeRule
import org.openlife.app.test.syntheticJpeg
import org.openlife.vault.repository.ReadyReadResult
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.facts.EvidenceRole
import org.openlife.vault.facts.FactEvidence
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactOrigin
import org.openlife.vault.facts.FactStatus
import org.openlife.vault.facts.FactValue
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

/**
 * P3-05-R1 through the real navigation graph: the facts route, the evidence action and the viewer entry. The facts
 * come from [FakeFactsGateway]; the repository's persistence is proven by the vault tests, not here.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class FactsFlowTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    @Test
    fun evidenceActionOpensTheViewerWithTheSpanSelected() {
        val source = source(UUID(0L, 31L))
        val evidence = FactEvidence(EvidenceRole.VALUE, READING.toString(), SPAN.toString(), 4, 14)
        val row = FakeFactsGateway.row(
            source.id,
            FactKind.DATE,
            FactOrigin.EXTRACTED,
            FactStatus.UNREVIEWED,
            FactValue("{\"date\":\"2026-04-03\"}", "2026-04-03"),
        )
        val facts = FakeFactsGateway(rows = listOf(row), evidence = listOf(evidence), readingId = READING)
        setHost(FakeAppContainer(listOf(source), factsGateway = facts))
        composeRule.runOnIdle { navController.navigate(Routes.Facts(source.id.toString())) }

        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithText(context.getString(R.string.facts_show_evidence))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(context.getString(R.string.facts_show_evidence)).onFirst().performClick()

        composeRule.waitUntil(WAIT_MS) { topRouteIsViewer() }
        val top = composeRule.runOnIdle { navController.currentBackStackEntry?.toRoute<Routes.ListDetail>() }
        assertEquals("the viewer must open on the evidence span", SPAN.toString(), top?.spanId)
        assertEquals("the viewer must open on the fact's source", source.id.toString(), top?.sourceId)
    }

    @Test
    fun viewerFactsEntryOpensTheFactsOfThatSource() {
        val source = source(UUID(0L, 32L))
        val revisionId = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val span = OcrSpan(
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            revisionId,
            0,
            "2026-04-03",
            null,
            OcrCoordinateSystem.SOURCE_PIXELS,
            OcrEvidenceRegion(0, 0, 50, 20),
        )
        val view = OcrView(revision(revisionId, source.id), listOf(OcrSpanView(span, null)), null, emptyList())
        setHost(
            FakeAppContainer(
                listOf(source),
                readContent = { ReadyReadResult.Loaded(syntheticJpeg()) },
                ocrView = { view },
            ),
        )
        composeRule.runOnIdle { navController.navigate(Routes.ListDetail(source.id.toString())) }

        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag("viewer_open_facts").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("viewer_open_facts").performClick()

        composeRule.waitUntil(WAIT_MS) {
            navController.currentBackStackEntry?.destination?.hasRoute<Routes.Facts>() == true
        }
        val facts = composeRule.runOnIdle { navController.currentBackStackEntry?.toRoute<Routes.Facts>() }
        assertEquals("the facts route must name the viewer's source", source.id.toString(), facts?.sourceId)
    }

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
        charCount = 10,
        spanCount = 1,
    )

    private fun topRouteIsViewer(): Boolean {
        val entry = navController.currentBackStackEntry ?: return false
        return entry.destination.route?.contains("ListDetail") == true
    }

    private fun setHost(fake: FakeAppContainer) {
        composeRule.setContent {
            navController = rememberNavController()
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 800.dp))) {
                OpenLifeTheme { OpenLifeNavHost(fake, navController) }
            }
        }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

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

    private companion object {
        const val WAIT_MS = 10_000L
        val READING: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val SPAN: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}
