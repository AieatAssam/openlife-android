package org.openlife.app.intake

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.openlife.app.MainActivity
import org.openlife.app.OpenLifeApp
import org.openlife.app.R
import org.openlife.app.ui.FirstRunExplanationScreen
import org.openlife.app.ui.FirstRunPreferences
import org.openlife.app.ui.IntakeRejectionMessage
import org.openlife.app.ui.IntakeScreen
import org.openlife.app.ui.IntakeUiState
import org.openlife.app.ui.IntakeViewModel
import org.openlife.app.ui.applySecureWindow
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.model.IntakeKind
import java.io.FileNotFoundException

/**
 * The single exported intake activity (design §8/§11). Every incoming
 * intent is validated here regardless of the declared `<intent-filter>`,
 * because an exported activity can be started directly with an arbitrary
 * intent (design §8: "Validate every incoming intent regardless of the
 * filter"). Hands a validated stream opener to
 * [IntakeViewModel] without ever letting a `Uri`, filename, or claimed
 * sender identity reach the vault layer (design §7 repository layout).
 *
 * Also the entry point for Photo Picker imports: [MainActivity] forwards a
 * picked `content://` URI here as a same-app `ACTION_SEND` intent carrying
 * [EXTRA_INTAKE_KIND], so both routes share one validated pipeline and one
 * preview/save UI rather than two parallel implementations.
 */
class IntakeActivity : ComponentActivity() {

    private val viewModel: IntakeViewModel by viewModels {
        IntakeViewModel.factory(application as OpenLifeApp)
    }

    /** Test-only observation seam for the C0-05/C0-02/C0-03 instrumented tests. */
    fun currentStatusForTest(): String = describeForTest(this, viewModel.state.value)

    override fun onStart() {
        super.onStart()
        viewModel.restoreSensitiveContentAfterForeground()
    }

    override fun onStop() {
        viewModel.clearSensitiveContentForBackground()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySecureWindow()

        // Validate only on a fresh launch. On a configuration-change
        // recreation the ViewModel already holds (or is restoring) the
        // Source UUID via SavedStateHandle - re-validating an already
        // consumed Intent would be meaningless (design §8: rotation
        // survives through the ViewModel and UUID, never by re-deriving
        // from the original Intent/Uri).
        val validation = if (savedInstanceState == null) {
            IntakeIntentValidator.validate(extractShape(intent), packageName)
        } else {
            null
        }

        setContent {
            var acknowledged by remember { mutableStateOf(FirstRunPreferences.isAcknowledged(this)) }
            OpenLifeTheme {
                if (!acknowledged) {
                    FirstRunExplanationScreen(
                        onContinue = {
                            FirstRunPreferences.setAcknowledged(this)
                            acknowledged = true
                        },
                    )
                } else {
                    if (validation != null) {
                        LaunchedEffect(validation) {
                            when (validation) {
                                is IntakeValidationResult.Rejected ->
                                    viewModel.showRejected(describeIntentRejection(validation.reason))

                                is IntakeValidationResult.Valid ->
                                    startImportFromUri(validation.uriString.toUri())
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
                                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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

    private fun startImportFromUri(uri: Uri) {
        // contentResolver.getType is a blocking call into another (possibly
        // slow or hostile) provider. Keep metadata lookup off the main thread;
        // the ViewModel later opens and reads the stream on its provider-owned
        // dispatcher.
        lifecycleScope.launch(Dispatchers.IO) {
            val providerType = try {
                contentResolver.getType(uri)
            } catch (_: SecurityException) {
                viewModel.showRejected(IntakeRejectionMessage.ACCESS_RETRY)
                return@launch
            } catch (_: FileNotFoundException) {
                viewModel.showRejected(IntakeRejectionMessage.ACCESS_RETRY)
                return@launch
            } ?: run {
                viewModel.showRejected(IntakeRejectionMessage.TYPE_MISMATCH)
                return@launch
            }
            val normalizedProviderType = providerType.lowercase()
            if (!IntakeIntentValidator.mimeTypesMatch(intent.type, normalizedProviderType)) {
                viewModel.showRejected(IntakeRejectionMessage.TYPE_MISMATCH)
                return@launch
            }
            val intakeKind = if (intent.getStringExtra(EXTRA_INTAKE_KIND) == IntakeKind.PHOTO_PICKER.name) {
                IntakeKind.PHOTO_PICKER
            } else {
                IntakeKind.SHARE
            }
            viewModel.startImport(
                openStream = {
                    contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
                },
                declaredMimeType = normalizedProviderType,
                intakeKind = intakeKind,
            )
        }
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
        const val EXTRA_INTAKE_KIND = "org.openlife.app.intake.EXTRA_INTAKE_KIND"
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

    is IntakeUiState.VaultUnavailable -> "Vault unavailable: ${state.reason}"

    IntakeUiState.Cancelled -> "Cancelled"
}
