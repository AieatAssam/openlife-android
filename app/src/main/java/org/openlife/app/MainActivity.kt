package org.openlife.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import org.openlife.app.intake.IntakeActivity
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.navigation.Routes
import org.openlife.app.ui.FirstRunExplanationScreen
import org.openlife.app.ui.FirstRunPreferences
import org.openlife.app.ui.OcrViewModel
import org.openlife.app.ui.SourceListViewModel
import org.openlife.app.ui.applySecureWindow
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.IntakeKind
import java.util.UUID

/** Persistent source list and viewer host. Intake remains a separate activity. */
class MainActivity : ComponentActivity() {

    private val viewModel: SourceListViewModel by viewModels {
        SourceListViewModel.factory(application as OpenLifeApp)
    }
    private val ocrViewModel: OcrViewModel by viewModels {
        OcrViewModel.factory(application as OpenLifeApp)
    }
    private val backgroundEpoch = MutableStateFlow(0L)
    private val openSourceRequests = MutableStateFlow<UUID?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSourceIdFrom(intent)?.let { openSourceRequests.value = it }
    }

    override fun onStop() {
        // Drop decoded content and make the navigation host return to the list
        // while this content-bearing activity is hidden.
        viewModel.clearSensitiveContent()
        backgroundEpoch.value += 1
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySecureWindow()
        setContent { MainContent() }
    }

    @Composable
    private fun MainContent() {
        var acknowledged by remember { mutableStateOf(FirstRunPreferences.isAcknowledged(this@MainActivity)) }
        val navController = rememberNavController()
        val requestedSourceId by openSourceRequests.collectAsState()
        val epoch by backgroundEpoch.collectAsState()
        val thumbnailGeneration by viewModel.sensitiveContentGeneration.collectAsState()
        val ocrStates by ocrViewModel.states.collectAsState()
        val listState by viewModel.state.collectAsState()
        val initialSourceId = remember { openSourceIdFrom(intent) }

        LaunchedEffect(initialSourceId) {
            initialSourceId?.let { navController.navigate(Routes.Viewer(it.toString())) }
        }
        LaunchedEffect(requestedSourceId) {
            requestedSourceId?.let {
                navController.navigate(Routes.Viewer(it.toString()))
                openSourceRequests.value = null
            }
        }
        LaunchedEffect(epoch) {
            if (epoch > 0L) {
                navController.navigate(Routes.List) {
                    popUpTo(navController.graph.startDestinationId) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }

        val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let(::forwardPickedUri)
        }
        val launchPhotoPicker = {
            pickMedia.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        }

        OpenLifeTheme {
            if (!acknowledged) {
                FirstRunExplanationScreen(
                    onContinue = {
                        FirstRunPreferences.setAcknowledged(this@MainActivity)
                        acknowledged = true
                    },
                )
            } else {
                OpenLifeNavHost(
                    listState = listState,
                    thumbnailGeneration = thumbnailGeneration,
                    ocrStates = ocrStates,
                    loadThumbnail = viewModel::loadThumbnail,
                    loadReadyBytes = { sourceId -> viewModelLoadReadyBytes(sourceId) },
                    delete = { sourceId, onDone -> viewModel.delete(sourceId) { onDone() } },
                    extractText = ocrViewModel::run,
                    cancelOcr = ocrViewModel::cancel,
                    correct = { sourceId, span, correctedText ->
                        val ready = ocrStates[sourceId] as? org.openlife.app.ui.OcrUiState.Ready
                        if (ready != null) ocrViewModel.correct(ready.revisionId, span.id, correctedText)
                    },
                    review = { sourceId, reviewState ->
                        val ready = ocrStates[sourceId] as? org.openlife.app.ui.OcrUiState.Ready
                        if (ready != null) ocrViewModel.review(ready.revisionId, reviewState)
                    },
                    onImportFromPhotoPicker = launchPhotoPicker,
                    navController = navController,
                )
            }
        }
    }

    private fun forwardPickedUri(uri: Uri) {
        startActivity(
            Intent(this, IntakeActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = contentResolver.getType(uri) ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(IntakeActivity.EXTRA_INTAKE_KIND, IntakeKind.PHOTO_PICKER.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
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
