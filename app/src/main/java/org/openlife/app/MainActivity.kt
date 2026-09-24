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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.openlife.app.intake.IntakeActivity
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.navigation.Routes
import org.openlife.app.ui.FirstRunExplanationScreen
import org.openlife.app.ui.FirstRunPreferences
import org.openlife.app.ui.OcrViewModel
import org.openlife.app.ui.SourceListViewModel
import org.openlife.app.ui.applySecureWindow
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.repository.ReadyReadResult
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
    private val firstRunAcknowledged = MutableStateFlow<Boolean?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSourceIdFrom(intent)?.let { openSourceRequests.value = it }
    }

    override fun onStart() {
        super.onStart()
        viewModel.cleanAbandonedStages()
    }

    override fun onStop() {
        // Drop decoded content and make the navigation host return to the list
        // while this content-bearing activity is hidden.
        viewModel.clearSensitiveContent()
        backgroundEpoch.value += 1
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Keep the splash up until the first-run flag has been read off the
        // main thread, so neither screen flashes before the other.
        installSplashScreen().setKeepOnScreenCondition { firstRunAcknowledged.value == null }
        lifecycleScope.launch {
            firstRunAcknowledged.value = FirstRunPreferences.loadAcknowledged(applicationContext)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySecureWindow()
        setContent { MainContent() }
    }

    @Composable
    private fun MainContent() {
        val firstRun = firstRunAcknowledged.collectAsState().value
        val acknowledged = firstRun == true
        val navController = rememberNavController()
        val requestedSourceId by openSourceRequests.collectAsState()
        val epoch by backgroundEpoch.collectAsState()
        val thumbnailGeneration by viewModel.sensitiveContentGeneration.collectAsState()
        val ocrStates by ocrViewModel.states.collectAsState()
        val listState by viewModel.state.collectAsState()
        val initialSourceId = remember { openSourceIdFrom(intent) }

        LaunchedEffect(acknowledged, initialSourceId) {
            if (acknowledged) {
                initialSourceId?.let { navController.navigate(Routes.Viewer(it.toString())) }
            }
        }
        LaunchedEffect(acknowledged, requestedSourceId) {
            if (acknowledged) {
                requestedSourceId?.let {
                    navController.navigate(Routes.Viewer(it.toString()))
                    openSourceRequests.value = null
                }
            }
        }
        LaunchedEffect(acknowledged, epoch) {
            if (acknowledged && epoch > 0L) {
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
            if (firstRun == false) {
                FirstRunExplanationScreen(
                    onContinue = {
                        firstRunAcknowledged.value = true
                        lifecycleScope.launch { FirstRunPreferences.acknowledge(applicationContext) }
                    },
                )
            } else if (acknowledged) {
                OpenLifeNavHost(
                    listState = listState,
                    thumbnailGeneration = thumbnailGeneration,
                    ocrStates = ocrStates,
                    loadThumbnail = viewModel::loadThumbnail,
                    loadReadyContent = { sourceId -> loadReadyContent(sourceId) },
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
                    onRetryVault = viewModel::retryVault,
                    onFinishReset = viewModel::finishVaultReset,
                    onResetVault = { (application as OpenLifeApp).resetVault() },
                    onResetComplete = {
                        viewModel.retryVault()
                        navController.navigate(Routes.List) { launchSingleTop = true }
                    },
                )
            }
        }
    }

    /** Test seam: the Photo Picker result path (P1-02). */
    internal fun forwardPickedUriForTest(uri: Uri) = forwardPickedUri(uri)

    private fun forwardPickedUri(uri: Uri) {
        startActivity(
            Intent(this, IntakeActivity::class.java).apply {
                action = Intent.ACTION_SEND
                // No ContentResolver call here (P1-02-R1/R2): the intake flow
                // resolves the provider type off the main thread.
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(IntakeActivity.EXTRA_PICKER_NONCE, (application as OpenLifeApp).pickerNonce.issue())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    private suspend fun loadReadyContent(sourceId: UUID): ReadyReadResult =
        ((application as OpenLifeApp).vault() as? VaultAccess.Ready)?.viewRepository?.readReadyBytes(sourceId)
            ?: ReadyReadResult.Unavailable

    companion object {
        const val EXTRA_OPEN_SOURCE_ID = "org.openlife.app.MainActivity.openSourceId"
    }
}

private fun openSourceIdFrom(intent: Intent): UUID? =
    intent.getStringExtra(MainActivity.EXTRA_OPEN_SOURCE_ID)?.let { raw ->
        runCatching { UUID.fromString(raw) }.getOrNull()
    }
