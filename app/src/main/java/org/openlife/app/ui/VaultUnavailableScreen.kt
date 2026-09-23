package org.openlife.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import org.openlife.vault.storage.VaultUnavailableCause

@Composable
fun VaultUnavailableScreen(
    cause: VaultUnavailableCause,
    onRetry: () -> Unit,
    onOpenResetSettings: () -> Unit,
    onFinishReset: () -> Unit = {},
) {
    Text("Unavailable")
}
