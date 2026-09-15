package org.openlife.app.intake

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.openlife.app.MainActivity
import org.openlife.app.OpenLifeApp
import org.openlife.app.ui.FirstRunExplanationScreen
import org.openlife.app.ui.FirstRunPreferences
import org.openlife.app.ui.IntakeScreen
import org.openlife.app.ui.IntakeUiState
import org.openlife.app.ui.IntakeViewModel
import org.openlife.app.ui.applySecureWindow
import org.openlife.vault.model.IntakeKind

/**
 * The single exported intake activity (design §8/§11). Every incoming
 * intent is validated here regardless of the declared `<intent-filter>`,
 * because an exported activity can be started directly with an arbitrary
 * intent (design §8: "Validate every incoming intent regardless of the
 * filter"). Hands a validated, already-opened, bounded stream to
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
    fun currentStatusForTest(): String = describeForTest(viewModel.state.value)

    override fun onStart() {
        super.onStart()
        viewModel.restoreSensitiveContentAfterForeground()
    }

    override fun onStop() {
        viewModel.clearSensitiveContentForBackground()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            MaterialTheme {
                if (!acknowledged) {
                    FirstRunExplanationScreen(
                        onContinue = {
                            FirstRunPreferences.setAcknowledged(this)
                            acknowledged = true
                        }
                    )
                } else {
                    if (validation != null) {
                        LaunchedEffect(validation) {
                            when (validation) {
                                is IntakeValidationResult.Rejected ->
                                    viewModel.showRejected(describeIntentRejection(validation.reason))
                                is IntakeValidationResult.Valid ->
                                    startImportFromUri(Uri.parse(validation.uriString))
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
                                }
                            )
                            finish()
                        },
                    )
                }
            }
        }
    }

    private fun startImportFromUri(uri: Uri) {
        // contentResolver.getType/openInputStream are ordinary blocking JVM
        // calls into another (possibly slow or hostile) content provider,
        // with no timeout of their own - calling them directly from this
        // LaunchedEffect (the main/Compose thread) blocks the entire UI for
        // as long as the provider takes to answer. A ~6s test provider
        // delay reproduced a real Android ANR ("Input dispatching timed
        // out... Waited 5000ms") during Stage 8's C0-17 pass; the 15s
        // cooperative-cancellation deadline in IntakeViewModel.startImport
        // only covers reading an *already-opened* stream, not this open
        // call itself. Dispatching to Dispatchers.IO keeps the open call
        // off the main thread so a slow provider degrades to a stuck
        // "Preparing…" spinner (recoverable by leaving the screen) instead
        // of freezing the app.
        lifecycleScope.launch(Dispatchers.IO) {
            var openedStream: java.io.InputStream? = null
            try {
                val providerType = try {
                    contentResolver.getType(uri)
                } catch (e: SecurityException) {
                    viewModel.showRejected("could not access the selected item; please select it again")
                    return@launch
                } catch (e: java.io.FileNotFoundException) {
                    viewModel.showRejected("could not access the selected item; please select it again")
                    return@launch
                } ?: run {
                    viewModel.showRejected("the selected item type did not match its contents")
                    return@launch
                }
                val normalizedProviderType = providerType.lowercase()
                if (!IntakeIntentValidator.mimeTypesMatch(intent.type, normalizedProviderType)) {
                    viewModel.showRejected("the selected item type did not match its contents")
                    return@launch
                }
                openedStream = try {
                    contentResolver.openInputStream(uri)
                } catch (e: SecurityException) {
                    // Missing or expired URI grant (design §11 step 1: "If a
                    // share grant expires or a provider disappears, ask the
                    // user to select the item again").
                    viewModel.showRejected("could not access the selected item; please select it again")
                    return@launch
                } catch (e: java.io.FileNotFoundException) {
                    viewModel.showRejected("could not access the selected item; please select it again")
                    return@launch
                }
                val stream = openedStream ?: run {
                    viewModel.showRejected("could not access the selected item; please select it again")
                    return@launch
                }
                val intakeKind = if (intent.getStringExtra(EXTRA_INTAKE_KIND) == IntakeKind.PHOTO_PICKER.name) {
                    IntakeKind.PHOTO_PICKER
                } else {
                    IntakeKind.SHARE
                }
                viewModel.startImport(stream, normalizedProviderType, intakeKind)
                // Ownership transfers to IntakeViewModel, which closes it on
                // completion, cancellation, failure, or teardown.
                openedStream = null
            } finally {
                // If lifecycle cancellation or a rejected validation happens
                // after open but before handoff, do not leak the descriptor.
                openedStream?.let { stream ->
                    try {
                        stream.close()
                    } catch (_: Exception) {
                        // Best-effort release on a hostile provider.
                    }
                }
            }
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

private fun describeIntentRejection(reason: IntakeRejectionReason): String = when (reason) {
    IntakeRejectionReason.WRONG_ACTION -> "unsupported action"
    IntakeRejectionReason.NO_CANDIDATE -> "no image was included"
    IntakeRejectionReason.MULTIPLE_OR_CONFLICTING_CANDIDATES -> "more than one item was included"
    IntakeRejectionReason.UNSUPPORTED_URI_SCHEME -> "unsupported source"
    IntakeRejectionReason.OWN_AUTHORITY -> "invalid source"
    IntakeRejectionReason.MALFORMED_URI -> "invalid source"
    IntakeRejectionReason.MISSING_READ_GRANT -> "the selected item was not shared with read access"
    IntakeRejectionReason.UNSUPPORTED_OR_MISSING_MIME_TYPE -> "unsupported or missing image type"
}

private fun describeForTest(state: IntakeUiState): String = when (state) {
    IntakeUiState.Preparing -> "Preparing"
    is IntakeUiState.Preview -> "Prepared ${state.format} ${state.width}x${state.height}"
    is IntakeUiState.Saving -> "Saving"
    is IntakeUiState.Saved -> "Saved on this device"
    is IntakeUiState.Duplicate -> "Not imported again"
    is IntakeUiState.Rejected -> "Not imported: ${state.message}"
    IntakeUiState.Busy -> "Another import is already in progress."
    IntakeUiState.Failed -> "Import failed"
    is IntakeUiState.VaultUnavailable -> "Vault unavailable: ${state.reason}"
    IntakeUiState.Cancelled -> "Cancelled"
}
