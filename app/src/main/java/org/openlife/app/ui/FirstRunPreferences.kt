package org.openlife.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import org.openlife.app.R

/**
 * Design §8: "First-run explanation must state that the vault has no sync
 * or recovery backup and that uninstalling or losing the device can lose
 * saved content. It must also explain the device-lock-only policy... Do
 * not block every later import with the same onboarding" - shown once,
 * gated by a plain (unencrypted) preference flag. The flag itself reveals
 * nothing about vault content, so it does not need to live behind the
 * vault's encryption.
 */
object FirstRunPreferences {
    private const val PREFS_NAME = "first_run"
    private const val KEY_ACKNOWLEDGED = "acknowledged"

    fun isAcknowledged(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ACKNOWLEDGED, false)

    fun setAcknowledged(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ACKNOWLEDGED, true)
        }
    }
}

@Composable
fun FirstRunExplanationScreen(onContinue: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            // Keep the explanation reachable when the user enables a large
            // accessibility font. The screen is intentionally scrollable
            // instead of relying on a particular viewport height.
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.first_run_title), style = MaterialTheme.typography.headlineSmall)
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.first_run_body))
                Text(stringResource(R.string.first_run_device_lock))
            }
            Button(
                modifier = Modifier.padding(top = 24.dp),
                onClick = onContinue,
            ) {
                Text(stringResource(R.string.first_run_continue))
            }
        }
    }
}
