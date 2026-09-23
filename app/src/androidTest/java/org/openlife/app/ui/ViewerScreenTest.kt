package org.openlife.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.repository.ReadyReadResult

/**
 * P1-13-R4 / F-33: a saved item that fails to load is explained, never left
 * on "Verifying…". Unreadable content offers deletion (design §11 recovery
 * table: "Explain unreadable content and offer deletion"); a temporary
 * failure offers a retry and does not claim the content is damaged.
 */
@RunWith(AndroidJUnit4::class)
class ViewerScreenTest {
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

        composeRule.onNodeWithText("This item's saved content is unreadable", substring = true)
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
}
