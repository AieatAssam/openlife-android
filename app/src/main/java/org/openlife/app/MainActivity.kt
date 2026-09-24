package org.openlife.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openlife.app.intake.IntakeActivity
import org.openlife.app.lock.AppLockGate
import org.openlife.app.lock.AppLockOfferScreen
import org.openlife.app.lock.AppLockPolicy
import org.openlife.app.lock.LockAvailability
import org.openlife.app.lock.LockStatus
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.navigation.Routes
import org.openlife.app.settings.AppLockSettings
import org.openlife.app.ui.FirstRunExplanationScreen
import org.openlife.app.ui.FirstRunPreferences
import org.openlife.app.ui.OcrViewModel
import org.openlife.app.ui.SourceListViewModel
import org.openlife.app.ui.applySecureWindow
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.repository.ReadyReadResult
import java.util.UUID

/** Persistent source list and viewer host. Intake remains a separate activity. */
class MainActivity : FragmentActivity() {

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
        // P2-01-R4: no OCR text stays in memory while hidden; the database is the record.
        ocrViewModel.clearTransient()
        backgroundEpoch.value += 1
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Keep the splash up until the first-run flag has been read off the
        // main thread, so neither screen flashes before the other.
        val lock = (application as OpenLifeApp).appLock
        installSplashScreen().setKeepOnScreenCondition {
            firstRunAcknowledged.value == null || lock.status.value == LockStatus.UNKNOWN
        }
        lifecycleScope.launch {
            firstRunAcknowledged.value = FirstRunPreferences.loadAcknowledged(applicationContext)
        }
        super.onCreate(savedInstanceState)
        // Opened after unlock by UnlockedContent; a recreated activity has already consumed it.
        if (savedInstanceState == null) openSourceIdFrom(intent)?.let { openSourceRequests.value = it }
        enableEdgeToEdge()
        applySecureWindow()
        setContent { MainContent() }
    }

    @Composable
    private fun rememberPhotoPickerLauncher(): () -> Unit {
        val lock = (application as OpenLifeApp).appLock
        val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            lock.state.endExternalActivity()
            uri?.let(::forwardPickedUri)
        }
        return {
            // The picker is another app's activity that OpenLife opened; going there is not leaving (ADR-0004).
            lock.state.beginExternalActivity()
            pickMedia.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        }
    }

    @Composable
    private fun MainContent() {
        val app = application as OpenLifeApp
        val firstRun = firstRunAcknowledged.collectAsState().value
        val lockStatus by app.appLock.status.collectAsState()
        var offerAppLock by rememberSaveable { mutableStateOf(false) }

        OpenLifeTheme {
            when (firstRun) {
                null -> Unit

                false -> FirstRunExplanationScreen(
                    onContinue = {
                        firstRunAcknowledged.value = true
                        offerAppLock = true
                        lifecycleScope.launch { FirstRunPreferences.acknowledge(applicationContext) }
                    },
                )

                true -> AppLockGate(lockStatus, app.appLock.state, app.appLock.authenticator, this) {
                    if (offerAppLock) {
                        AppLockOfferScreen(
                            onEnable = {
                                app.appLock.authenticator.availability().also { availability ->
                                    if (availability == LockAvailability.AVAILABLE) {
                                        app.appLock.updatePolicy(AppLockPolicy(enabled = true))
                                        offerAppLock = false
                                    }
                                }
                            },
                            onSkip = { offerAppLock = false },
                        )
                    } else {
                        UnlockedContent(app)
                    }
                }
            }
        }
    }

    /**
     * Everything content-bearing. Composed only while unlocked (P1-07-R2); a
     * relock discards it, so unlocking starts again from the list.
     */
    @Composable
    private fun UnlockedContent(app: OpenLifeApp) {
        val navController = rememberNavController()
        val requestedSourceId by openSourceRequests.collectAsState()
        val epoch by backgroundEpoch.collectAsState()
        val epochAtStart = remember { epoch }
        val thumbnailGeneration by viewModel.sensitiveContentGeneration.collectAsState()
        val listState by viewModel.state.collectAsState()
        val lockPolicy by app.appLock.policy.collectAsState()

        LaunchedEffect(requestedSourceId) {
            requestedSourceId?.let {
                // The NavHost sets its graph during layout (P1-16 BoxWithConstraints); wait for it.
                navController.currentBackStackEntryFlow.first()
                navController.navigate(Routes.Viewer(it.toString()))
                openSourceRequests.value = null
            }
        }
        LaunchedEffect(epoch) {
            if (epoch != epochAtStart) {
                navController.currentBackStackEntryFlow.first()
                navController.navigate(Routes.List) {
                    popUpTo(navController.graph.startDestinationId) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }

        OpenLifeNavHost(
            listState = listState,
            thumbnailGeneration = thumbnailGeneration,
            ocrState = { id ->
                // A new generation after clearTransient re-reads from the database (P2-01-R4).
                val generation by ocrViewModel.generation.collectAsState()
                remember(id, generation) { ocrViewModel.state(id) }.collectAsState().value
            },
            loadThumbnail = viewModel::loadThumbnail,
            loadReadyContent = { sourceId -> loadReadyContent(sourceId) },
            delete = { sourceId, onDone -> viewModel.delete(sourceId) { onDone() } },
            extractText = ocrViewModel::run,
            cancelOcr = ocrViewModel::cancel,
            correct = ocrViewModel::correct,
            review = ocrViewModel::review,
            onImportFromPhotoPicker = rememberPhotoPickerLauncher(),
            navController = navController,
            onRetryVault = viewModel::retryVault,
            onFinishReset = viewModel::finishVaultReset,
            onResetVault = { app.resetVault() },
            onVerifyAll = { app.verifyAllItems() },
            appLock = AppLockSettings(lockPolicy, app.appLock.authenticator::availability, app.appLock::updatePolicy),
            onResetComplete = {
                viewModel.retryVault()
                navController.navigate(Routes.List) { launchSingleTop = true }
            },
        )
    }

    /** Test seam (P1-16-R2): how many times hiding the activity has scrubbed content. */
    internal fun scrubCountForTest(): Long = backgroundEpoch.value

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

    private suspend fun loadReadyContent(sourceId: UUID): ReadyReadResult {
        val app = application as OpenLifeApp
        app.appLock.awaitContentAccess()
        return (app.vault() as? VaultAccess.Ready)?.viewRepository?.readReadyBytes(sourceId)
            ?: ReadyReadResult.Unavailable
    }

    companion object {
        const val EXTRA_OPEN_SOURCE_ID = "org.openlife.app.MainActivity.openSourceId"
    }
}

private fun openSourceIdFrom(intent: Intent): UUID? =
    intent.getStringExtra(MainActivity.EXTRA_OPEN_SOURCE_ID)?.let { raw ->
        runCatching { UUID.fromString(raw) }.getOrNull()
    }
