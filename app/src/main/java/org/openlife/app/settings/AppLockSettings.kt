package org.openlife.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.app.lock.AppLockPolicy
import org.openlife.app.lock.LockAvailability
import org.openlife.app.lock.refusalText

/** What Settings needs to show and change the app lock (P1-07-R1/R4). */
class AppLockSettings(
    val policy: AppLockPolicy,
    val availability: () -> LockAvailability,
    val onChange: (AppLockPolicy) -> Unit,
)

@Composable
fun AppLockSettingsSection(settings: AppLockSettings) {
    var refusal by remember { mutableStateOf<LockAvailability?>(null) }
    val policy = settings.policy

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = policy.enabled, role = Role.Switch) { enable ->
                    if (!enable) {
                        refusal = null
                        settings.onChange(policy.copy(enabled = false))
                    } else {
                        // Enabling needs something that can verify the user; never a lock with no key.
                        val availability = settings.availability()
                        refusal = availability.takeIf { it != LockAvailability.AVAILABLE }
                        if (refusal == null) settings.onChange(policy.copy(enabled = true))
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.app_lock_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = policy.enabled, onCheckedChange = null)
        }
        Text(stringResource(R.string.app_lock_explanation), style = MaterialTheme.typography.bodyMedium)
        refusal?.let { Text(stringResource(refusalText(it)), color = MaterialTheme.colorScheme.error) }
        if (policy.enabled) TimeoutChoices(policy, settings.onChange)
    }
}

@Composable
private fun TimeoutChoices(policy: AppLockPolicy, onChange: (AppLockPolicy) -> Unit) {
    Text(stringResource(R.string.app_lock_timeout_title), style = MaterialTheme.typography.titleSmall)
    Column(modifier = Modifier.selectableGroup()) {
        AppLockPolicy.TIMEOUT_OPTIONS_SECONDS.forEach { seconds ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = policy.timeoutSeconds == seconds,
                        role = Role.RadioButton,
                        onClick = { onChange(policy.copy(timeoutSeconds = seconds)) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = policy.timeoutSeconds == seconds, onClick = null)
                Text(stringResource(timeoutLabel(seconds)), modifier = Modifier.weight(1f))
            }
        }
    }
}

private val TIMEOUT_LABELS: Map<Int, Int> = AppLockPolicy.TIMEOUT_OPTIONS_SECONDS.zip(
    listOf(
        R.string.app_lock_timeout_immediately,
        R.string.app_lock_timeout_30s,
        R.string.app_lock_timeout_5m,
        R.string.app_lock_timeout_15m,
    ),
).toMap()

private fun timeoutLabel(seconds: Int): Int = TIMEOUT_LABELS.getValue(seconds)
