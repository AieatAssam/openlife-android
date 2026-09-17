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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

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
    Surface(modifier = Modifier.fillMaxSize()) {
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
            Text("Before you import anything", style = MaterialTheme.typography.headlineSmall)
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "OpenLife keeps what you import only on this device. There is no cloud " +
                        "backup and no sync — uninstalling the app or losing this device can " +
                        "permanently lose everything you've saved here.",
                )
                Text(
                    "Anyone who can unlock this device can open OpenLife. There is no " +
                        "separate app passcode in this version.",
                )
            }
            Button(modifier = Modifier.padding(top = 24.dp), onClick = onContinue) {
                Text("I understand")
            }
        }
    }
}
