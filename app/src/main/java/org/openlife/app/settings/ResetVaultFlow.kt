package org.openlife.app.settings

import android.icu.text.ListFormatter
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openlife.app.R
import org.openlife.app.ui.Announce
import org.openlife.app.ui.Announcement
import org.openlife.app.ui.StatusHost
import org.openlife.app.ui.asHeading
import org.openlife.app.ui.brand.OpenLifeOutlinedButton
import org.openlife.app.ui.brand.OpenLifeTextButton
import org.openlife.vault.repository.ResetConfirmation
import org.openlife.vault.repository.VaultResetResult
import org.openlife.vault.repository.VerifyReport

/**
 * P1-14-R3: deep verification is explicit. [onVerifyAll] returns null when
 * the vault is unavailable; the result stays on screen until run again.
 * The pass and its result live in [SettingsController] (P1-19-R15).
 */
@Composable
internal fun VerifyAllSection(controller: SettingsController, onVerifyAll: suspend () -> VerifyReport?) {
    val state by controller.verify.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.verify_all_explanation), style = MaterialTheme.typography.bodyMedium)
        OpenLifeOutlinedButton(enabled = !state.running, onClick = { controller.verifyAll(onVerifyAll) }) {
            Text(stringResource(if (state.running) R.string.verify_all_running else R.string.verify_all_action))
        }
        when (val current = state.outcome) {
            is VerifyOutcome.Done -> {
                val report = current.report
                val damaged = report.markedCorrupt
                Announce(
                    Announcement.countOf(R.plurals.verify_all_verified, report.verified) +
                        Announcement.countOf(R.plurals.verify_all_damaged, damaged) +
                        Announcement.countOf(R.plurals.verify_all_unchecked, report.transient),
                )
                Text(
                    localizedList(
                        listOf(
                            pluralStringResource(R.plurals.verify_all_verified, report.verified, report.verified),
                            pluralStringResource(R.plurals.verify_all_damaged, damaged, damaged),
                            pluralStringResource(R.plurals.verify_all_unchecked, report.transient, report.transient),
                        ),
                    ),
                )
                if (report.transient > 0) Text(stringResource(R.string.verify_all_transient_hint))
            }

            VerifyOutcome.Unavailable -> {
                Announce(Announcement.statusOf(R.string.verify_all_unavailable))
                Text(stringResource(R.string.verify_all_unavailable))
            }

            VerifyOutcome.Failed -> {
                Announce(Announcement.statusOf(R.string.verify_all_failed))
                Text(stringResource(R.string.verify_all_failed), color = MaterialTheme.colorScheme.error)
            }

            null -> Unit
        }
    }
}

/** Joins already-localised phrases the way the current locale lists things (P1-19-R20). */
@Composable
private fun localizedList(items: List<String>): String {
    val locale = LocalConfiguration.current.locales[0]
    return ListFormatter.getInstance(locale).format(items)
}

@Composable
fun ResetVaultFlow(
    onResetVault: suspend (ResetConfirmation) -> VaultResetResult,
    onResetComplete: () -> Unit = {},
    controller: SettingsController = viewModel<SettingsViewModel>().controller,
) {
    val state by controller.reset.collectAsState()
    val requiredWord = stringResource(R.string.reset_vault_required_word)

    LaunchedEffect(state.completed) {
        if (state.completed) {
            controller.consumeResetCompleted()
            onResetComplete()
        }
    }

    OpenLifeOutlinedButton(
        onClick = controller::openReset,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Text(stringResource(R.string.reset_vault_action))
    }

    if (state.stage == ResetStage.TYPE_WORD) {
        TypeWordDialog(
            word = state.word,
            requiredWord = requiredWord,
            onWordChange = controller::setWord,
            onContinue = { controller.continueReset(requiredWord) },
            onDismiss = controller::dismissReset,
        )
    }

    if (state.stage == ResetStage.FINAL_CONFIRMATION) {
        FinalConfirmationDialog(
            running = state.running,
            failure = state.failure?.let {
                when (it) {
                    ResetFailure.BUSY -> R.string.reset_vault_busy
                    ResetFailure.FAILED -> R.string.reset_vault_failed
                }
            },
            onDismiss = controller::dismissReset,
            onConfirm = { controller.confirmReset(onResetVault) },
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
        title = { Text(stringResource(R.string.reset_vault_title), modifier = Modifier.asHeading()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.reset_vault_explanation))
                Text(stringResource(R.string.reset_vault_consequences))
                // P1-19-R5 exemption: reset confirmation word. It holds only the
                // fixed word the user types to confirm, never user content.
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
            OpenLifeTextButton(enabled = word == requiredWord, onClick = onContinue) {
                Text(stringResource(R.string.reset_vault_continue))
            }
        },
        dismissButton = {
            OpenLifeTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_action)) }
        },
    )
}

@Composable
private fun FinalConfirmationDialog(
    running: Boolean,
    @StringRes failure: Int?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.reset_vault_final_title), modifier = Modifier.asHeading()) },
        text = {
            // A dialog is its own window, so its status needs its own host.
            StatusHost {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.reset_vault_final_explanation))
                    failure?.let {
                        Announce(Announcement.statusOf(it))
                        Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            OpenLifeTextButton(
                enabled = !running,
                onClick = onConfirm,
                modifier = Modifier.testTag("final-reset-action"),
            ) { Text(stringResource(R.string.reset_vault_action)) }
        },
        dismissButton = {
            OpenLifeTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_action)) }
        },
    )
}
