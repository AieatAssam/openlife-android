package org.openlife.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import org.openlife.app.intake.IntakeActivity
import org.openlife.app.ui.DeleteConfirmationDialog
import org.openlife.app.ui.FirstRunExplanationScreen
import org.openlife.app.ui.FirstRunPreferences
import org.openlife.app.ui.SourceListScreen
import org.openlife.app.ui.SourceListUiState
import org.openlife.app.ui.SourceListViewModel
import org.openlife.app.ui.ViewerScreen
import org.openlife.app.ui.applySecureWindow
import org.openlife.app.ui.sourceLabel
import org.openlife.vault.model.IntakeKind

private sealed interface Screen {
    data object List : Screen
    data class Viewer(val sourceId: UUID) : Screen
}

/**
 * The persistent app: source list, viewer, and deletion (design §8). The
 * transient intake flow (progress -> preview -> save) lives in
 * `IntakeActivity`; Photo Picker selections are forwarded there as a
 * same-app `ACTION_SEND` intent so both intake routes share one validated
 * pipeline (design §8: "Photo Picker through the AndroidX contract...").
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SourceListViewModel by viewModels {
        SourceListViewModel.factory(application as OpenLifeApp)
    }
    private val ocrViewModel: org.openlife.app.ui.OcrViewModel by viewModels {
        org.openlife.app.ui.OcrViewModel.factory(application as OpenLifeApp)
    }
    private val backgroundEpoch = MutableStateFlow(0L)
    private val openSourceRequests = MutableStateFlow<UUID?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSourceIdFrom(intent)?.let { openSourceRequests.value = it }
    }

    override fun onStop() {
        // Drop decoded thumbnails and force the viewer composable out of the
        // tree while this content-bearing activity is hidden.
        viewModel.clearSensitiveContent()
        backgroundEpoch.value += 1
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureWindow()

        setContent {
            var acknowledged by remember { mutableStateOf(FirstRunPreferences.isAcknowledged(this)) }
            var screen by rememberSaveable(stateSaver = ScreenSaver) {
                mutableStateOf<Screen>(openSourceIdFrom(intent)?.let { Screen.Viewer(it) } ?: Screen.List)
            }
            val requestedSourceId by openSourceRequests.collectAsState()
            val epoch by backgroundEpoch.collectAsState()
            val thumbnailGeneration by viewModel.sensitiveContentGeneration.collectAsState()
            val ocrStates by ocrViewModel.states.collectAsState()

            androidx.compose.runtime.LaunchedEffect(epoch) {
                if (epoch > 0L) screen = Screen.List
            }
            androidx.compose.runtime.LaunchedEffect(requestedSourceId) {
                requestedSourceId?.let {
                    screen = Screen.Viewer(it)
                    openSourceRequests.value = null
                }
            }

            val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                if (uri != null) {
                    startActivity(
                        Intent(this, IntakeActivity::class.java).apply {
                            action = Intent.ACTION_SEND
                            type = contentResolver.getType(uri) ?: "image/*"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(IntakeActivity.EXTRA_INTAKE_KIND, IntakeKind.PHOTO_PICKER.name)
                            // Forward the Picker's one-shot read grant to the
                            // exported intake activity. IntakeActivity checks
                            // this flag before opening the URI.
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                }
            }

            MaterialTheme {
                if (!acknowledged) {
                    FirstRunExplanationScreen(
                        onContinue = {
                            FirstRunPreferences.setAcknowledged(this)
                            acknowledged = true
                        }
                    )
                } else {
                    when (val current = screen) {
                        Screen.List -> {
                            val state by viewModel.state.collectAsState()
                            SourceListScreen(
                                state = state,
                                loadThumbnail = viewModel::loadThumbnail,
                                thumbnailGeneration = thumbnailGeneration,
                                onOpen = { screen = Screen.Viewer(it) },
                                onDelete = { viewModel.delete(it) {} },
                                onImportFromPhotoPicker = {
                                    pickMedia.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                            )
                        }

                        is Screen.Viewer -> {
                            val listState = viewModel.state.collectAsState().value
                            val source = (listState as? SourceListUiState.Loaded)
                                ?.sources?.find { it.id == current.sourceId }
                            if (source == null) {
                                if (listState is SourceListUiState.Loaded) {
                                    screen = Screen.List
                                } else {
                                    SourceListScreen(
                                        state = listState,
                                        loadThumbnail = viewModel::loadThumbnail,
                                        thumbnailGeneration = thumbnailGeneration,
                                        onOpen = { screen = Screen.Viewer(it) },
                                        onDelete = { viewModel.delete(it) {} },
                                        onImportFromPhotoPicker = {
                                            pickMedia.launch(
                                                androidx.activity.result.PickVisualMediaRequest(
                                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                                )
                                            )
                                        },
                                    )
                                }
                            } else {
                                var pendingDelete by remember { mutableStateOf(false) }
                                ViewerScreen(
                                    source = source,
                                    loadBytes = { viewModelLoadReadyBytes(current.sourceId) },
                                    onBack = { screen = Screen.List },
                                    onDeleteRequested = { pendingDelete = true },
                                    ocrState = ocrStates[current.sourceId] ?: org.openlife.app.ui.OcrUiState.Idle,
                                    onExtractText = { ocrViewModel.run(current.sourceId) },
                                    onCancelOcr = { ocrViewModel.cancel(current.sourceId) },
                                    onCorrect = { span, correctedText ->
                                        val ready = ocrStates[current.sourceId] as? org.openlife.app.ui.OcrUiState.Ready
                                        if (ready != null) {
                                            ocrViewModel.correct(current.sourceId, ready.revisionId, span.id, correctedText)
                                        }
                                    },
                                    onReview = { reviewState ->
                                        val ready = ocrStates[current.sourceId] as? org.openlife.app.ui.OcrUiState.Ready
                                        if (ready != null) ocrViewModel.review(current.sourceId, ready.revisionId, reviewState)
                                    },
                                )
                                if (pendingDelete) {
                                    DeleteConfirmationDialog(
                                        itemLabel = sourceLabel(source),
                                        onConfirm = {
                                            pendingDelete = false
                                            viewModel.delete(current.sourceId) {}
                                            screen = Screen.List
                                        },
                                        onDismiss = { pendingDelete = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun viewModelLoadReadyBytes(sourceId: UUID): ByteArray? =
        ((application as OpenLifeApp).vault() as? VaultAccess.Ready)?.viewRepository?.loadReadyBytes(sourceId)

    companion object {
        const val EXTRA_OPEN_SOURCE_ID = "org.openlife.app.MainActivity.openSourceId"
    }
}

private fun openSourceIdFrom(intent: Intent): UUID? =
    intent.getStringExtra(MainActivity.EXTRA_OPEN_SOURCE_ID)?.let { raw ->
        runCatching { UUID.fromString(raw) }.getOrNull()
    }

private val ScreenSaver = androidx.compose.runtime.saveable.Saver<Screen, String>(
    save = { screen ->
        when (screen) {
            Screen.List -> "list"
            is Screen.Viewer -> "viewer:${screen.sourceId}"
        }
    },
    restore = { raw ->
        if (raw == "list") {
            Screen.List
        } else {
            Screen.Viewer(UUID.fromString(raw.removePrefix("viewer:")))
        }
    }
)
