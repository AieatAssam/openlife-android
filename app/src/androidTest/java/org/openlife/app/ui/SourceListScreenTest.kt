package org.openlife.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState

@RunWith(AndroidJUnit4::class)
class SourceListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun failedDeletionRemainsVisibleAsRetryableUnavailableItem() {
        val source = Source(
            id = UUID.randomUUID(),
            state = SourceState.DELETING,
            importedAt = 0L,
            intakeKind = IntakeKind.SHARE,
            mimeType = null,
            byteCount = null,
            sha256 = null,
            width = null,
            height = null,
            orientation = null,
            wrappedDek = null,
            artefactVersion = null,
        )

        composeRule.setContent {
            SourceListScreen(
                state = SourceListUiState.Loaded(listOf(source)),
                loadThumbnail = { null },
                onOpen = {},
                onDelete = {},
                onImportFromPhotoPicker = {},
            )
        }

        composeRule.onNodeWithText("Deletion pending — retry").assertIsDisplayed()
        composeRule.onNodeWithText("Content unavailable").assertIsDisplayed()
    }
}
