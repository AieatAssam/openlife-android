package org.openlife.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openlife.app.R
import org.openlife.app.ui.BackTopAppBar
import org.openlife.app.ui.brand.PerforationDivider
import org.openlife.vault.repository.ResetConfirmation
import org.openlife.vault.repository.VaultResetResult
import org.openlife.vault.repository.VerifyReport

/**
 * Settings (P1-06, P1-07, P1-14; laid out by P1-08): the app lock, then the
 * vault's maintenance actions under one heading, separated by perforation.
 * Its [SettingsController] lives in the entry-scoped [SettingsViewModel]
 * (the destination's owner is its back-stack entry).
 */
@Composable
fun SettingsScreen(
    onResetVault: suspend (ResetConfirmation) -> VaultResetResult,
    onResetComplete: () -> Unit,
    onBack: () -> Unit = {},
    onVerifyAll: (suspend () -> VerifyReport?)? = null,
    appLock: AppLockSettings? = null,
) {
    val controller = viewModel<SettingsViewModel>().controller
    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = { BackTopAppBar(title = stringResource(R.string.settings_title), onBack = onBack) },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                appLock?.let {
                    AppLockSettingsSection(it)
                    PerforationDivider()
                }
                Text(
                    stringResource(R.string.settings_section_vault),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                onVerifyAll?.let { VerifyAllSection(controller, it) }
                ResetVaultFlow(onResetVault = onResetVault, onResetComplete = onResetComplete, controller = controller)
            }
        }
    }
}
