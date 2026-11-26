package org.openlife.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.openlife.app.intake.IntakeActivity
import org.openlife.app.lock.AppLockGate
import org.openlife.app.lock.AppLockOfferScreen
import org.openlife.app.lock.AppLockPolicy
import org.openlife.app.lock.LockAvailability
import org.openlife.app.lock.LockStatus
import org.openlife.app.navigation.LocalPhotoPickerLauncher
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.ui.FirstRunExplanationScreen
import org.openlife.app.ui.FirstRunPreferences
import org.openlife.app.ui.applySecureWindow
import org.openlife.app.ui.theme.OpenLifeTheme

/**
 * The launcher: the app lock gate in front of the navigation host (P1-20).
 * It holds no screen state. It reads no navigation extra from any intent
 * (P1-20-R5): internal links arrive through the non-exported
 * InternalLinkActivity and wait in [AppContainer.internalLinks].
 */
class MainActivity : FragmentActivity() {
    private val firstRunAcknowledged = MutableStateFlow<Boolean?>(null)

    private val container: AppContainer get() = (application as OpenLifeApp).container

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStart() {
        // Review N-01: apply the time away before any destination starts and reads; the
        // process callback that also does this may run after the entries' ON_START. Idempotent.
        (application as OpenLifeApp).appLock.state.onForeground()
        super.onStart()
        // P1-01-R3: on foreground, remove stages no screen owns; a busy vault is retried next time.
        lifecycleScope.launch { container.sources.cleanAbandonedStages() }
    }

    override fun onStop() {
        // P1-20-R4: drop decoded images, OCR text and search results now, while
        // this content-bearing activity is hidden. The back stack and its route
        // ids stay; each destination reloads by id after the lock gate.
        container.scrubber.scrub(hidden = true)
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
        // Above the gate (P1-20-R4): a relock hides the destinations but keeps the back stack.
        val navController = rememberNavController()

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
                        UnlockedContent(navController)
                    }
                }
            }
        }
    }

    /** Everything content-bearing. Composed only while unlocked (P1-07-R2). */
    @Composable
    private fun UnlockedContent(navController: NavHostController) {
        // Pending internal links are opened inside OpenLifeNavHost, which knows the width (review N-04).
        CompositionLocalProvider(LocalPhotoPickerLauncher provides rememberPhotoPickerLauncher()) {
            OpenLifeNavHost(container, navController)
        }
    }

    /** Test seam (P1-16-R2): how many times hiding the activity has scrubbed content. */
    @VisibleForTesting
    internal fun scrubCountForTest(): Long = container.scrubber.hiddenScrubs

    /** Test seam: the Photo Picker result path (P1-02). */
    @VisibleForTesting
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
}
