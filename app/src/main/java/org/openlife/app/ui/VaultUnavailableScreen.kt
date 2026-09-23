package org.openlife.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.vault.storage.VaultUnavailableCause
import org.openlife.vault.storage.retryable

@Composable
fun VaultUnavailableScreen(
    cause: VaultUnavailableCause,
    onRetry: () -> Unit,
    onOpenResetSettings: () -> Unit,
    onFinishReset: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.vault_unavailable_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(cause.explanationString()),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (expanded) {
                Text(
                    stringResource(R.string.vault_unavailable_details),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 8.dp)) {
                val detailsLabel = when (expanded) {
                    true -> R.string.vault_unavailable_hide_details
                    false -> R.string.vault_unavailable_what_means
                }
                Text(stringResource(detailsLabel))
            }
            if (cause == VaultUnavailableCause.RESET_INCOMPLETE) {
                Button(onClick = onFinishReset, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.vault_unavailable_finish_reset))
                }
            } else if (cause.retryable) {
                Button(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.vault_unavailable_try_again))
                }
            }
            OutlinedButton(onClick = onOpenResetSettings, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.vault_unavailable_open_settings))
            }
        }
    }
}

private fun VaultUnavailableCause.explanationString(): Int = when (this) {
    VaultUnavailableCause.KEYSTORE_TEMPORARILY_UNAVAILABLE -> R.string.vault_unavailable_keystore
    VaultUnavailableCause.KEY_FILE_CORRUPT -> R.string.vault_unavailable_corrupt_key
    VaultUnavailableCause.KEY_UNWRAP_FAILED -> R.string.vault_unavailable_unwrap
    VaultUnavailableCause.DATABASE_WITHOUT_KEY -> R.string.vault_unavailable_missing_key
    VaultUnavailableCause.DATABASE_OPEN_FAILED -> R.string.vault_unavailable_database
    VaultUnavailableCause.STORAGE_IO_ERROR -> R.string.vault_unavailable_storage
    VaultUnavailableCause.RESET_INCOMPLETE -> R.string.vault_unavailable_reset_incomplete
}
