package org.openlife.app.ui

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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openlife.app.R
import org.openlife.app.VaultFailureDiagnostics
import org.openlife.app.ui.brand.FoldedCornerCard

@Composable
fun AboutScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val licenseText = produceState(initialValue = "", resources) {
        value = withContext(Dispatchers.IO) {
            resources.openRawResource(R.raw.ofl_1_1).bufferedReader().use { it.readText() }
        }
    }.value
    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = { BackTopAppBar(title = stringResource(R.string.about_title), onBack = onBack) },
    ) { padding ->
        AboutContent(modifier = Modifier.padding(padding), licenseText = licenseText, context = context)
    }
}

@Composable
private fun AboutContent(modifier: Modifier, licenseText: String, context: android.content.Context) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(
                stringResource(R.string.about_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            FoldedCornerCard(modifier = Modifier.padding(top = 24.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.about_font_credits))
                    // The counts live in a preferences file; read it off main (P1-13-R3).
                    val diagnostics = produceState(initialValue = "", context) {
                        value = withContext(Dispatchers.IO) { VaultFailureDiagnostics.summary(context) }
                    }.value
                    if (diagnostics.isNotEmpty()) {
                        Text(
                            stringResource(R.string.about_vault_diagnostics, diagnostics),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (licenseText.isNotEmpty()) {
                        Text(
                            licenseText,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
