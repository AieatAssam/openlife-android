package org.openlife.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import java.util.UUID
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceListScreen(
    state: SourceListUiState,
    loadThumbnail: suspend (UUID) -> android.graphics.Bitmap?,
    thumbnailGeneration: Long = 0L,
    onOpen: (UUID) -> Unit,
    onDelete: (UUID) -> Unit,
    onImportFromPhotoPicker: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Source?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenLife") },
                actions = {
                    IconButton(onClick = onImportFromPhotoPicker) {
                        Icon(Icons.Filled.Add, contentDescription = "Import from photos")
                    }
                }
            )
        }
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                SourceListUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…")
                }

                is SourceListUiState.VaultUnavailable -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("The vault is unavailable: ${state.reason}")
                }

                is SourceListUiState.Loaded -> {
                    if (state.sources.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Nothing imported yet. Share an image to OpenLife, or use + above.")
                        }
                    } else {
                        LazyColumn {
                            items(state.sources, key = { it.id }) { source ->
                                SourceRow(
                                    source = source,
                                    loadThumbnail = loadThumbnail,
                                    thumbnailGeneration = thumbnailGeneration,
                                    onOpen = { onOpen(source.id) },
                                    onDeleteRequested = { pendingDelete = source },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { source ->
        DeleteConfirmationDialog(
            itemLabel = sourceLabel(source),
            onConfirm = {
                onDelete(source.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun SourceRow(
    source: Source,
    loadThumbnail: suspend (UUID) -> android.graphics.Bitmap?,
    thumbnailGeneration: Long,
    onOpen: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    val deletionPending = source.state == SourceState.DELETING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (deletionPending) Modifier else Modifier.clickable(onClick = onOpen))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (source.state == SourceState.CORRUPT || deletionPending) {
                Icon(Icons.Filled.Warning, contentDescription = "Content unavailable")
            } else {
                val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, source.id, thumbnailGeneration) {
                    value = loadThumbnail(source.id)
                }
                bitmap?.let { Image(it.asImageBitmap(), contentDescription = null) }
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(sourceLabel(source))
            if (source.state == SourceState.CORRUPT) {
                Text("Content unavailable", style = MaterialTheme.typography.bodySmall)
            } else if (deletionPending) {
                Text("Deletion pending — retry", style = MaterialTheme.typography.bodySmall)
                Text("Content unavailable", style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = onDeleteRequested) {
            Icon(Icons.Filled.Delete, contentDescription = if (deletionPending) "Retry deletion" else "Delete")
        }
    }
}
