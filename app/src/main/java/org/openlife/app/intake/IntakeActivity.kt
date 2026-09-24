package org.openlife.app.intake

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.openlife.app.MainActivity
import org.openlife.app.OpenLifeApp
import org.openlife.app.R
import org.openlife.app.lock.AppLockGate
import org.openlife.app.lock.LockStatus
import org.openlife.app.ui.FirstRunExplanationScreen
import org.openlife.app.ui.FirstRunPreferences
import org.openlife.app.ui.IntakeRejectionMessage
import org.openlife.app.ui.IntakeScreen
import org.openlife.app.ui.IntakeUiState
import org.openlife.app.ui.IntakeViewModel
import org.openlife.app.ui.applySecureWindow
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.IntakeKind

/**
 * The single exported intake activity (design §8/§11). Every incoming
 * intent is validated here regardless of the declared `<intent-filter>`,
 * because an exported activity can be started directly with an arbitrary
 * intent (design §8: "Validate every incoming intent regardless of the
 * filter"). Hands the validated URI string to [IntakeViewModel], which
 * owns the provider lookup, open and read (P1-13-R8), without ever letting
 * a `Uri`, filename, or claimed sender identity reach the vault layer
 * (design §7 repository layout).
 *
 * Also the entry point for Photo Picker imports: [MainActivity] forwards a
 * picked `content://` URI here as a same-app `ACTION_SEND` intent carrying
 * a one-shot [EXTRA_PICKER_NONCE], so both routes share one validated
 * pipeline and one preview/save UI rather than two parallel implementations.
 */
class IntakeActivity : FragmentActivity() {

    /** Null until read off the main thread; the splash screen stays up until then. */
    private val firstRunAcknowledged = MutableStateFlow<Boolean?>(null)

    private val viewModel: IntakeViewModel by viewModels {
        IntakeViewModel.factory(application as OpenLifeApp)
    }

    /** Test-only observation seam for the C0-05/C0-02/C0-03 instrumented tests. */
    fun currentStatusForTest(): String = describeForTest(this, viewModel.state.value)

    /** Test-only: cancels the active import deterministically, as the Cancel button would. */
    fun cancelForTest() = viewModel.cancel()

    override fun onStart() {
        super.onStart()
        viewModel.restoreSensitiveContentAfterForeground()
    }

    override fun onStop() {
        viewModel.clearSensitiveContentForBackground()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as OpenLifeApp
        installSplashScreen().setKeepOnScreenCondition {
            firstRunAcknowledged.value == null || app.appLock.status.value == LockStatus.UNKNOWN
        }
        lifecycleScope.launch {
            firstRunAcknowledged.value = FirstRunPreferences.loadAcknowledged(applicationContext)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySecureWindow()

        // A fresh launch validates the intent. So does a configuration
        // change: the retained ViewModel ignores a repeat start and the
        // intent and its grant are still attached, so a share that had not
        // started yet (first run, splash) still starts. Only a new process
        // restored from saved state refuses to reopen a URI (design §8);
        // the ViewModel restores a saved stage by UUID or asks to reselect.
        val retainedViewModel = viewModel.attach()
        val validation = if (savedInstanceState == null || retainedViewModel) {
            IntakeIntentValidator.validate(extractShape(intent), packageName)
        } else {
            viewModel.onRestoredAfterProcessDeath()
            null
        }

        setContent {
            val acknowledged = firstRunAcknowledged.collectAsState().value
            val lockStatus by app.appLock.status.collectAsState()
            OpenLifeTheme {
                if (acknowledged == null) {
                    Unit
                } else if (!acknowledged) {
                    FirstRunExplanationScreen(
                        onContinue = {
                            firstRunAcknowledged.value = true
                            lifecycleScope.launch { FirstRunPreferences.acknowledge(applicationContext) }
                        },
                    )
                } else {
                    // P1-07-R5: while locked nothing below is composed, so the
                    // share's stream is opened only after the user unlocks.
                    AppLockGate(lockStatus, app.appLock.state, app.appLock.authenticator, this) {
                        if (validation != null) {
                            LaunchedEffect(validation) {
                                when (validation) {
                                    is IntakeValidationResult.Rejected ->
                                        viewModel.showRejected(describeIntentRejection(validation.reason))

                                    is IntakeValidationResult.Valid -> viewModel.startImportFromUri(
                                        uriString = validation.uriString,
                                        intentMimeType = intent.type,
                                        intakeKind = intakeKindOf(intent),
                                    )
                                }
                            }
                        }
                        val state by viewModel.state.collectAsState()
                        IntakeScreen(
                            state = state,
                            onSave = viewModel::confirmSave,
                            onCancel = viewModel::cancel,
                            onDone = { finish() },
                            onOpenExisting = { existingSourceId ->
                                startActivity(
                                    Intent(this, MainActivity::class.java).apply {
                                        addFlags(openExistingIntentFlags())
                                        putExtra(MainActivity.EXTRA_OPEN_SOURCE_ID, existingSourceId.toString())
                                    },
                                )
                                finish()
                            },
                        )
                    }
                }
            }
        }
    }

    /** PHOTO_PICKER only with this process's one-shot picker nonce; anything else is a share (F-34). */
    private fun intakeKindOf(intent: Intent): IntakeKind =
        if ((application as OpenLifeApp).pickerNonce.consume(intent.getStringExtra(EXTRA_PICKER_NONCE))) {
            IntakeKind.PHOTO_PICKER
        } else {
            IntakeKind.SHARE
        }

    private fun extractShape(intent: Intent): IntentShape {
        val extraStreamUri = getParcelableExtraCompat(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val clipDataUris = buildList {
            val clipData: ClipData? = intent.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i).uri?.let { add(it.toString()) }
                }
            }
        }
        return IntentShape(
            action = intent.action,
            dataUri = intent.data?.toString(),
            extraStreamUri = extraStreamUri?.toString(),
            clipDataUris = clipDataUris,
            intentMimeType = intent.type,
            hasReadUriPermission = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
    }

    @Suppress("DEPRECATION")
    private fun <T : Parcelable> getParcelableExtraCompat(intent: Intent, name: String, type: Class<T>): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, type)
        } else {
            intent.getParcelableExtra(name)
        }

    companion object {
        /** Carries [OpenLifeApp.pickerNonce]; a caller-supplied route claim is never trusted. */
        const val EXTRA_PICKER_NONCE = "org.openlife.app.intake.EXTRA_PICKER_NONCE"
    }
}

private fun describeIntentRejection(reason: IntakeRejectionReason): IntakeRejectionMessage = when (reason) {
    IntakeRejectionReason.WRONG_ACTION -> IntakeRejectionMessage.UNSUPPORTED_ACTION

    IntakeRejectionReason.NO_CANDIDATE -> IntakeRejectionMessage.NO_IMAGE

    IntakeRejectionReason.MULTIPLE_OR_CONFLICTING_CANDIDATES -> IntakeRejectionMessage.MULTIPLE_ITEMS

    IntakeRejectionReason.UNSUPPORTED_URI_SCHEME -> IntakeRejectionMessage.UNSUPPORTED_SOURCE

    IntakeRejectionReason.OWN_AUTHORITY,
    IntakeRejectionReason.MALFORMED_URI,
    -> IntakeRejectionMessage.INVALID_SOURCE

    IntakeRejectionReason.MISSING_READ_GRANT -> IntakeRejectionMessage.MISSING_READ_ACCESS

    IntakeRejectionReason.UNSUPPORTED_OR_MISSING_MIME_TYPE -> IntakeRejectionMessage.UNSUPPORTED_OR_MISSING_TYPE
}

private fun describeForTest(activity: IntakeActivity, state: IntakeUiState): String = when (state) {
    IntakeUiState.Preparing -> "Preparing"

    is IntakeUiState.Preview -> "Prepared ${state.format} ${state.width}x${state.height}"

    is IntakeUiState.Saving -> "Saving"

    is IntakeUiState.Saved -> "Saved on this device"

    is IntakeUiState.Duplicate -> "Not imported again"

    // C0-R12: a vanished provider must prompt reselection. Report the same
    // user-visible copy the screen shows, not the enum name.
    is IntakeUiState.Rejected -> activity.getString(
        R.string.intake_not_imported,
        activity.getString(state.message.stringRes),
    )

    IntakeUiState.Busy -> "Another import is already in progress."

    IntakeUiState.Failed -> "Import failed"

    IntakeUiState.StorageUnavailable -> "Storage unavailable"

    is IntakeUiState.VaultUnavailable -> "Vault unavailable: ${state.cause.name}"

    IntakeUiState.Cancelled -> "Cancelled"
}
