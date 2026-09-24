package org.openlife.app.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.openlife.app.R
import org.openlife.app.ui.BackTopAppBar
import org.openlife.vault.repository.VaultResetResult
import org.openlife.vault.repository.VerifyReport

@Composable
fun SettingsScreen(
    onResetVault: suspend () -> VaultResetResult,
    onResetComplete: () -> Unit,
    onBack: () -> Unit = {},
    onVerifyAll: (suspend () -> VerifyReport?)? = null,
    appLock: AppLockSettings? = null,
) {
    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = { BackTopAppBar(title = stringResource(R.string.settings_title), onBack = onBack) },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                appLock?.let { AppLockSettingsSection(it) }
                onVerifyAll?.let { VerifyAllSection(it) }
                ResetVaultFlow(onResetVault = onResetVault, onResetComplete = onResetComplete)
            }
        }
    }
}

/**
 * P1-14-R3: deep verification is explicit. [onVerifyAll] returns null when
 * the vault is unavailable; the result stays on screen until run again.
 */
@Composable
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun VerifyAllSection(onVerifyAll: suspend () -> VerifyReport?) {
    var running by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<VerifyOutcome?>(null) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.verify_all_explanation), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            enabled = !running,
            onClick = {
                scope.launch {
                    running = true
                    try {
                        outcome = onVerifyAll()?.let(VerifyOutcome::Done) ?: VerifyOutcome.Unavailable
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A reset or storage failure mid-pass; items already checked keep their state.
                        outcome = VerifyOutcome.Failed
                    } finally {
                        running = false
                    }
                }
            },
        ) {
            Text(stringResource(if (running) R.string.verify_all_running else R.string.verify_all_action))
        }
        when (val current = outcome) {
            is VerifyOutcome.Done -> {
                val report = current.report
                Text(
                    listOf(
                        pluralStringResource(R.plurals.verify_all_verified, report.verified, report.verified),
                        pluralStringResource(R.plurals.verify_all_damaged, report.markedCorrupt, report.markedCorrupt),
                        pluralStringResource(R.plurals.verify_all_unchecked, report.transient, report.transient),
                    ).joinToString(", "),
                )
                if (report.transient > 0) Text(stringResource(R.string.verify_all_transient_hint))
            }

            VerifyOutcome.Unavailable -> Text(stringResource(R.string.verify_all_unavailable))

            VerifyOutcome.Failed -> Text(
                stringResource(R.string.verify_all_failed),
                color = MaterialTheme.colorScheme.error,
            )

            null -> Unit
        }
    }
}

private sealed interface VerifyOutcome {
    data class Done(val report: VerifyReport) : VerifyOutcome

    data object Unavailable : VerifyOutcome

    data object Failed : VerifyOutcome
}

@Composable
@Suppress("TooGenericExceptionCaught", "SwallowedException")
fun ResetVaultFlow(onResetVault: suspend () -> VaultResetResult, onResetComplete: () -> Unit = {}) {
    var stage by remember { mutableStateOf(ResetStage.CLOSED) }
    var word by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val requiredWord = stringResource(R.string.reset_vault_required_word)
    val busyText = stringResource(R.string.reset_vault_busy)
    val failedText = stringResource(R.string.reset_vault_failed)

    OutlinedButton(
        onClick = {
            stage = ResetStage.TYPE_WORD
            word = ""
            failure = null
        },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Text(stringResource(R.string.reset_vault_action))
    }

    if (stage == ResetStage.TYPE_WORD) {
        TypeWordDialog(
            word = word,
            requiredWord = requiredWord,
            onWordChange = { word = it },
            onContinue = { stage = ResetStage.FINAL_CONFIRMATION },
            onDismiss = { stage = ResetStage.CLOSED },
        )
    }

    if (stage == ResetStage.FINAL_CONFIRMATION) {
        FinalConfirmationDialog(
            running = running,
            failure = failure,
            onDismiss = { stage = ResetStage.CLOSED },
            onConfirm = {
                scope.launch {
                    running = true
                    try {
                        when (onResetVault()) {
                            VaultResetResult.COMPLETED -> {
                                stage = ResetStage.CLOSED
                                onResetComplete()
                            }

                            VaultResetResult.BUSY -> failure = busyText

                            VaultResetResult.FAILED -> failure = failedText
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (exception: Exception) {
                        failure = failedText
                    } finally {
                        running = false
                    }
                }
            },
        )
    }
}

@Composable
private fun TypeWordDialog(
    word: String,
    requiredWord: String,
    onWordChange: (String) -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reset_vault_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.reset_vault_explanation))
                Text(stringResource(R.string.reset_vault_consequences))
                OutlinedTextField(
                    value = word,
                    onValueChange = onWordChange,
                    label = { Text(stringResource(R.string.reset_vault_word_label, requiredWord)) },
                    singleLine = true,
                    modifier = Modifier.testTag("reset-word"),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = word == requiredWord, onClick = onContinue) {
                Text(stringResource(R.string.reset_vault_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_action)) }
        },
    )
}

@Composable
private fun FinalConfirmationDialog(running: Boolean, failure: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.reset_vault_final_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.reset_vault_final_explanation))
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running,
                onClick = onConfirm,
                modifier = Modifier.testTag("final-reset-action"),
            ) { Text(stringResource(R.string.reset_vault_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_action)) }
        },
    )
}

private enum class ResetStage { CLOSED, TYPE_WORD, FINAL_CONFIRMATION }
