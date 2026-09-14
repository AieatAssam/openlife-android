package org.openlife.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState

/**
 * Design §8: "Opening an entry verifies and displays the saved artefact.
 * The details view can show import time, route and integrity status
 * without claiming authenticity." [loadBytes] only ever returns bytes that
 * have already authenticated (design §9); a null result here means
 * verification failed or the content is unreadable, never a shortcut past
 * authentication.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    source: Source,
    loadBytes: suspend () -> ByteArray?,
    onBack: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    val bytes by produceState<ByteArray?>(initialValue = null, source.id) { value = loadBytes() }
    val verified = bytes != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sourceLabel(source)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onDeleteRequested) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
            )
        }
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val decoded = remember(bytes) { bytes?.let(SampledBitmapDecoder::decode) }
                    when {
                        source.state == SourceState.CORRUPT -> Text("This item's saved content is unreadable.")
                        bytes == null -> Text("Verifying…")
                        decoded == null -> Text("This item's saved content is unreadable.")
                        else -> Image(decoded.asImageBitmap(), contentDescription = null)
                    }
                }
                DetailsSection(source = source, verified = verified)
            }
        }
    }
}

@Composable
private fun DetailsSection(source: Source, verified: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Details", style = MaterialTheme.typography.titleMedium)
        DetailRow("Imported", sourceLabel(source))
        DetailRow("Route", if (source.intakeKind.name == "SHARE") "Shared to OpenLife" else "Selected from photos")
        DetailRow(
            "Integrity",
            when {
                source.state == SourceState.CORRUPT -> "Unreadable — not verified"
                verified -> "Verified against the saved copy"
                else -> "Not yet verified"
            }
        )
        Text(
            "Verification confirms this is the exact copy OpenLife saved. It does not confirm " +
                "who sent it or that its contents are true.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Text("$label: $value", modifier = Modifier.padding(top = 4.dp))
}
