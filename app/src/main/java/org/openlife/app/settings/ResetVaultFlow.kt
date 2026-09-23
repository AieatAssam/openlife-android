package org.openlife.app.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun ResetVaultFlow(onReset: () -> Unit, onBusy: () -> Unit = {}) {
    Text("Reset")
}
