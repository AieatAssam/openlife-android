package org.openlife.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.AssessmentState
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.model.UserContentKind
import org.openlife.vault.repository.PrepareResult
import org.openlife.vault.repository.ReadyReadResult
import org.openlife.vault.repository.SaveResult

/**
 * P1-13-R4 / F-33: a saved item that fails to load is explained, never left
 * on "Verifying…". Unreadable content offers deletion (design §11 recovery
 * table: "Explain unreadable content and offer deletion"); a temporary
 * failure offers a retry and does not claim the content is damaged.
 */
@RunWith(AndroidJUnit4::class)
class ViewerScreenTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unreadableContentIsExplainedWithDeleteActionNotVerifying() {
        var deleteRequests = 0
        composeRule.setContent {
            OpenLifeTheme {
                ViewerScreen(
                    source = readySource(),
                    loadContent = { ReadyReadResult.Corrupt },
                    onBack = {},
                    onDeleteRequested = { deleteRequests++ },
                )
            }
        }

        composeRule.onNodeWithText("This item’s saved content is unreadable", substring = true)
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("Verifying…").fetchSemanticsNodes().let { nodes ->
            assertEquals("must not keep showing Verifying", 0, nodes.size)
        }
        composeRule.onNodeWithTag("viewer_unreadable_delete").performScrollTo().performClick()
        assertEquals("delete stays confirmed", 0, deleteRequests)
        composeRule.onNodeWithTag("delete_confirm").performClick()
        assertEquals(1, deleteRequests)
    }

    @Test
    fun transientReadOffersRetryAndDoesNotClaimDamage() {
        val attempts = AtomicInteger()
        composeRule.setContent {
            OpenLifeTheme {
                ViewerScreen(
                    source = readySource(),
                    loadContent = {
                        if (attempts.getAndIncrement() == 0) ReadyReadResult.Transient else ReadyReadResult.Unavailable
                    },
                    onBack = {},
                    onDeleteRequested = {},
                )
            }
        }

        composeRule.onNodeWithText("OpenLife could not open this item right now", substring = true)
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("unreadable", substring = true).fetchSemanticsNodes().let { nodes ->
            assertEquals("a temporary failure must not be described as damage", 0, nodes.size)
        }
        composeRule.onNodeWithText("Try again").performClick()
        composeRule.waitUntil(5_000) { attempts.get() == 2 }
    }

    /**
     * P2-07-R3/R4 through the real vault: the viewer shows "Safety check: not
     * assessed in this version" and the routing copy; Yes stores
     * COMMUNICATION with a USER timestamp, and Not sure stores no kind with a
     * newer timestamp. The viewer re-reads the source from the database, so
     * the selection shown is what persisted.
     *
     * Status: implemented; unverified (gap — pending physical-device run).
     */
    @Test
    fun userContentKindLabelPersistsWithTimestampAndShowsRoutingCopy(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenLifeApp
        val access = app.vault() as VaultAccess.Ready
        val sourceId = saveSyntheticSource(access)
        try {
            val repository = access.viewRepository
            composeRule.setContent {
                val sources by androidx.compose.runtime.remember { repository.observeVisibleSources() }
                    .collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                sources.find { it.id == sourceId }?.let { source ->
                    OpenLifeTheme {
                        ViewerScreen(
                            source = source,
                            loadContent = { awaitCancellation() },
                            onBack = {},
                            onDeleteRequested = {},
                            onSetUserContentKind = { kind ->
                                scope.launch { repository.setUserContentKind(sourceId, kind) }
                            },
                        )
                    }
                }
            }
            composeRule.waitUntil(TIMEOUT_MS) {
                composeRule.onAllNodesWithText("Safety check: not assessed in this version").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Safety check: not assessed in this version").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Is this a message or request from someone?").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("not proof of safety", substring = true).performScrollTo().assertIsDisplayed()
            val initial = repository.findSource(sourceId)!!
            assertEquals(AssessmentState.NOT_ASSESSED, initial.assessmentState)
            assertNull("never answered", initial.userContentKindAt)
            composeRule.onNodeWithTag("viewer_content_kind_YES").assertIsNotSelected()

            composeRule.onNodeWithTag("viewer_content_kind_YES").performScrollTo().performClick()
            val yes = awaitSource(access, sourceId) { it.userContentKindAt != null }
            assertEquals(UserContentKind.COMMUNICATION, yes.userContentKind)
            assertEquals("the label never changes the assessment", AssessmentState.NOT_ASSESSED, yes.assessmentState)
            composeRule.waitUntil(TIMEOUT_MS) {
                runCatching { composeRule.onNodeWithTag("viewer_content_kind_YES").assertIsSelected() }.isSuccess
            }
            composeRule.onNodeWithTag("viewer_content_kind_attribution").performScrollTo().assertIsDisplayed()

            composeRule.onNodeWithTag("viewer_content_kind_NOT_SURE").performScrollTo().performClick()
            val notSure = awaitSource(access, sourceId) { it.userContentKind == null }
            assertTrue("Not sure is attributed too", notSure.userContentKindAt!! >= yes.userContentKindAt!!)
            composeRule.waitUntil(TIMEOUT_MS) {
                runCatching { composeRule.onNodeWithTag("viewer_content_kind_NOT_SURE").assertIsSelected() }.isSuccess
            }
            val callsSafe = androidx.compose.ui.test.SemanticsMatcher("text calls something safe") { node ->
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty()
                    .any { SAFE_WORD.containsMatchIn(it.text) }
            }
            assertEquals("nothing is called safe", 0, composeRule.onAllNodes(callsSafe).fetchSemanticsNodes().size)
        } finally {
            access.deletionRepository.deleteSource(sourceId)
        }
    }

    private suspend fun awaitSource(access: VaultAccess.Ready, id: UUID, until: (Source) -> Boolean): Source =
        withTimeout(TIMEOUT_MS) {
            var source = access.viewRepository.findSource(id)!!
            while (!until(source)) {
                delay(POLL_MS)
                source = access.viewRepository.findSource(id)!!
            }
            source
        }

    private suspend fun saveSyntheticSource(access: VaultAccess.Ready): UUID {
        val bitmap = android.graphics.Bitmap.createBitmap(48, 32, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(UUID.randomUUID().hashCode() or 0xFF000000.toInt())
        val bytes = ByteArrayOutputStream().also {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        bitmap.recycle()
        val prepared = access.importRepository.prepareImport(ByteArrayInputStream(bytes), "image/jpeg", IntakeKind.SHARE)
            as PrepareResult.Prepared
        assertTrue(access.importRepository.saveImport(prepared.sourceId) is SaveResult.Saved)
        return prepared.sourceId
    }

    private fun readySource() = Source(
        id = UUID(0L, 3L),
        state = SourceState.READY,
        importedAt = 1L,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = 100,
        height = 100,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val POLL_MS = 50L
        val SAFE_WORD = Regex("\\bsafe(ly)?\\b", RegexOption.IGNORE_CASE)
    }
}
