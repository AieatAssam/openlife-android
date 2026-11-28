package org.openlife.app.ui.facts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import org.openlife.vault.facts.ReviewDecision
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

    /**
     * P3-05-R3 (C2-R39): evidence on an earlier reading is offered, and opens that reading read-only, with the
     * span selected. The route names the reading by id only.
     */
    @Test
    fun anEarlierReadingEvidenceOpensThatReadingAndNoOther() {
        val source = source(UUID(0L, 34L))
        val evidence = FactEvidence(EvidenceRole.VALUE, EARLIER.toString(), SPAN.toString(), 4, 14)
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
        assertEquals("the route must name the earlier reading", EARLIER.toString(), top?.readingId)
        assertEquals("the viewer must open on the evidence span", SPAN.toString(), top?.spanId)
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

        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithTag("viewer_open_facts").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("viewer_open_facts").performClick()

        composeRule.waitUntil(WAIT_MS) {
            navController.currentBackStackEntry?.destination?.hasRoute<Routes.Facts>() == true
        }
        val facts = composeRule.runOnIdle { navController.currentBackStackEntry?.toRoute<Routes.Facts>() }
        assertEquals("the facts route must name the viewer's source", source.id.toString(), facts?.sourceId)
    }

    @Test
    fun clarifyChoosingACandidateSendsThatCandidateOnly() {
        val source = source(UUID(0L, 41L))
        val row = FakeFactsGateway.row(
            source.id,
            FactKind.DATE,
            FactOrigin.EXTRACTED,
            FactStatus.CLARIFICATION_NEEDED,
            FactValue(CANDIDATES_JSON, "2026-04-03 | 2026-03-04"),
        )
        val facts = FakeFactsGateway(rows = listOf(row), readingId = READING)
        openFacts(source, facts)

        // P3-05-R6: the candidate button shows the locale's date; the chosen entry stays canonical.
        val shown = FactDisplay.value(FactKind.DATE, "2026-03-04", Locale.getDefault())
        awaitText(shown)
        composeRule.onNodeWithText(shown).performClick()

        composeRule.waitUntil(WAIT_MS) { facts.clarifications.isNotEmpty() }
        val (revisionId, chosen) = facts.clarifications.single()
        assertEquals("the choice must name the revision shown", row.revision.id, revisionId)
        assertEquals("the chosen candidate keeps its stored entry", "{\"date\":\"2026-03-04\"}", chosen.valueJson)
        assertEquals("2026-03-04", chosen.canonicalText)
    }

    @Test
    fun aClarifiedChoiceOffersTheOtherCandidatesAndSendsTheChosenOne() {
        val source = source(UUID(0L, 43L))
        val chosen = FactValue("{\"date\":\"2026-03-04\"}", "2026-03-04")
        val row = FakeFactsGateway.row(source.id, FactKind.DATE, FactOrigin.USER, FactStatus.CONFIRMED, chosen)
        val candidates = mapOf(row.revision.id to FactValue(CANDIDATES_JSON, "2026-03-04 | 2026-04-03"))
        val facts = FakeFactsGateway(rows = listOf(row), readingId = READING, candidates = candidates)
        openFacts(source, facts)

        // P3-05-R7 (C2-R36): a clarified value can be re-chosen from the candidates it was chosen from.
        val other = FactDisplay.value(FactKind.DATE, "2026-04-03", Locale.getDefault())
        awaitText(other)
        val current = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.facts_choice_current)
        composeRule.onNodeWithText(current).assertIsDisplayed()
        composeRule.onNodeWithText(other).performClick()

        composeRule.waitUntil(WAIT_MS) { facts.clarifications.isNotEmpty() }
        val (revisionId, picked) = facts.clarifications.single()
        assertEquals("the re-choice must name the revision shown", row.revision.id, revisionId)
        assertEquals("the re-chosen candidate keeps its stored entry", "{\"date\":\"2026-04-03\"}", picked.valueJson)
    }

    @Test
    fun rejectSendsTheRejectDecisionForThatRevision() {
        val source = source(UUID(0L, 42L))
        val row = FakeFactsGateway.row(
            source.id,
            FactKind.URL,
            FactOrigin.EXTRACTED,
            FactStatus.UNREVIEWED,
            FactValue("{\"url\":\"https://example.org\"}", "https://example.org"),
        )
        val facts = FakeFactsGateway(rows = listOf(row), readingId = READING)
        openFacts(source, facts)

        val reject = context.getString(R.string.facts_reject)
        awaitText(reject)
        composeRule.onNodeWithText(reject).performClick()

        composeRule.waitUntil(WAIT_MS) { facts.reviews.isNotEmpty() }
        assertEquals(listOf(row.revision.id to ReviewDecision.REJECT), facts.reviews)
    }

    @Test
    fun addingAUserUrlSendsTheNormalisedValueToTheGateway() {
        val source = source(UUID(0L, 43L))
        val facts = FakeFactsGateway(readingId = READING)
        openFacts(source, facts)

        composeRule.onNodeWithText(context.getString(R.string.facts_add)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("https://example.org/notice")
        composeRule.onNodeWithText(context.getString(R.string.facts_add_save)).performClick()

        composeRule.waitUntil(WAIT_MS) { facts.additions.isNotEmpty() }
        val (sourceId, kind, value) = facts.additions.single()
        assertEquals(source.id, sourceId)
        assertEquals(FactKind.URL, kind)
        assertTrue("the accepted value keeps its address", value.canonicalText.contains("example.org"))
    }

    @Test
    fun addingAUserDateSavesTheChosenDayWithNoEvidence() {
        val source = source(UUID(0L, 46L))
        val facts = FakeFactsGateway(readingId = READING)
        openFacts(source, facts)

        composeRule.onNodeWithText(context.getString(R.string.facts_add)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.facts_kind_date)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.facts_pick_date)).performClick()
        awaitText(context.getString(R.string.facts_picker_confirm))
        // Day cells carry their date as text ("Sunday, 15 November 2026").
        composeRule.onAllNodes(dayCell(15)).onFirst().performClick()
        composeRule.onNodeWithText(context.getString(R.string.facts_picker_confirm)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.facts_add_save)).performClick()

        composeRule.waitUntil(WAIT_MS) { facts.additions.isNotEmpty() }
        val (sourceId, kind, value) = facts.additions.single()
        assertEquals(source.id, sourceId)
        assertEquals(FactKind.DATE, kind)
        assertTrue("the picked day must be stored canonically", Regex("\\d{4}-\\d{2}-15").matches(value.canonicalText))
    }

    @Test
    fun correctingAUserUrlSendsTheRevisionAndTheNormalisedValue() {
        val source = source(UUID(0L, 47L))
        val row = FakeFactsGateway.row(
            source.id,
            FactKind.URL,
            FactOrigin.EXTRACTED,
            FactStatus.UNREVIEWED,
            FactValue("{\"url\":\"https://example.org\"}", "https://example.org"),
        )
        val facts = FakeFactsGateway(rows = listOf(row), readingId = READING)
        openFacts(source, facts)

        val correct = context.getString(R.string.facts_correct)
        awaitText(correct)
        composeRule.onNodeWithText(correct).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("https://example.org/notice")
        composeRule.onNodeWithText(context.getString(R.string.facts_correct_save)).performClick()

        composeRule.waitUntil(WAIT_MS) { facts.corrections.isNotEmpty() }
        val (revisionId, value) = facts.corrections.single()
        assertEquals("the correction supersedes the revision shown", row.revision.id, revisionId)
        assertTrue("the accepted value keeps its address", value.canonicalText.contains("example.org/notice"))
    }

    @Test
    fun addingABlankUserValueIsRefusedAndNeverReachesTheGateway() {
        val source = source(UUID(0L, 44L))
        val facts = FakeFactsGateway(readingId = READING)
        openFacts(source, facts)

        composeRule.onNodeWithText(context.getString(R.string.facts_add)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.facts_add_save)).performClick()

        awaitText(context.getString(R.string.facts_refused_blank))
        assertTrue("a refused value must not reach the repository", facts.additions.isEmpty())
    }

    @Test
    fun staleFactIsGreyedWithItsSentenceAndFindFactsAgainRunsExtraction() {
        val source = source(UUID(0L, 45L))
        val row = FakeFactsGateway.row(
            source.id,
            FactKind.URL,
            FactOrigin.EXTRACTED,
            FactStatus.STALE,
            FactValue("{\"url\":\"https://example.org/old\"}", "https://example.org/old"),
        )
        val facts = FakeFactsGateway(rows = listOf(row), readingId = READING)
        openFacts(source, facts)

        val changed = context.getString(R.string.facts_stale_changed)
        awaitText(changed)
        val again = context.getString(R.string.facts_find_again)
        composeRule.onNodeWithText(again).performClick()

        composeRule.waitUntil(WAIT_MS) { facts.findAgainSources.isNotEmpty() }
        assertEquals(listOf(source.id), facts.findAgainSources)
    }

    private fun openFacts(source: Source, facts: FakeFactsGateway) {
        setHost(FakeAppContainer(listOf(source), factsGateway = facts))
        composeRule.runOnIdle { navController.navigate(Routes.Facts(source.id.toString())) }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
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
        const val CANDIDATES_JSON = "{\"candidates\":[{\"date\":\"2026-04-03\"},{\"date\":\"2026-03-04\"}]}"
        val READING: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val SPAN: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val EARLIER: UUID = UUID.fromString("55555555-5555-4555-8555-555555555555")
    }

    @Test
    fun addingAUserTimeSavesTheChosenTimeWithNoEvidence() {
        val source = source(UUID(0L, 46L))
        val facts = FakeFactsGateway(readingId = READING)
        openFacts(source, facts)

        composeRule.onNodeWithText(context.getString(R.string.facts_add)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.facts_kind_time)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.facts_pick_time)).performClick()
        awaitText(context.getString(R.string.facts_picker_confirm))
        composeRule.onNodeWithText(context.getString(R.string.facts_picker_confirm)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.facts_add_save)).performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(WAIT_MS) { facts.additions.isNotEmpty() }
        val (_, kind, value) = facts.additions.single()
        assertEquals(FactKind.TIME, kind)
        assertTrue(Regex("""\d{2}:\d{2}""").matches(value.canonicalText))
    }

    @Test
    fun addingAUserAmountSavesTheAmountAndCurrencyWithNoEvidence() {
        val source = source(UUID(0L, 46L))
        val facts = FakeFactsGateway(readingId = READING)
        openFacts(source, facts)

        composeRule.onNodeWithText(context.getString(R.string.facts_add)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.facts_kind_money)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("12.50")
        composeRule.onNodeWithText("EUR").performClick()
        composeRule.onNodeWithText(context.getString(R.string.facts_add_save)).performClick()
        composeRule.waitUntil(WAIT_MS) { facts.additions.isNotEmpty() }
        val (_, kind, value) = facts.additions.single()
        assertEquals(FactKind.MONEY, kind)
        assertTrue(value.canonicalText, value.canonicalText.contains("12.50") && value.canonicalText.contains("EUR"))
    }

    /** Matches the picker's grid cell for one day of the shown month, by its spoken date text. */
    private fun dayCell(day: Int): SemanticsMatcher = SemanticsMatcher("day cell $day") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { " $day " in it.text } == true
    }
}
