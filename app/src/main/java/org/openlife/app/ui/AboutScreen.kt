package org.openlife.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openlife.app.R
import org.openlife.app.ui.brand.FoldedCornerCard

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val licenseText = produceState(initialValue = "") {
        value = withContext(Dispatchers.IO) {
            context.resources.openRawResource(R.raw.ofl_1_1).bufferedReader().use { it.readText() }
        }
    }.value
    Surface(modifier = Modifier.fillMaxSize()) {
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
