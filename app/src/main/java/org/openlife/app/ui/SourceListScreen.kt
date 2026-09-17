package org.openlife.app.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import java.util.UUID

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
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_title)) },
                actions = {
                    IconButton(onClick = onImportFromPhotoPicker) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.list_import_from_photos),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                SourceListUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.list_loading))
                }

                is SourceListUiState.VaultUnavailable -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.list_vault_unavailable, state.reason))
                }

                is SourceListUiState.Loaded -> {
                    if (state.sources.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.list_empty))
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
                Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.content_unavailable))
            } else {
                val bitmap by produceState<android.graphics.Bitmap?>(
                    initialValue = null,
                    source.id,
                    thumbnailGeneration,
                ) {
                    value = loadThumbnail(source.id)
                }
                bitmap?.let {
                    Image(it.asImageBitmap(), contentDescription = stringResource(R.string.saved_image_thumbnail_content_description))
                }
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(sourceLabel(source))
            if (source.state == SourceState.CORRUPT) {
                Text(stringResource(R.string.content_unavailable), style = MaterialTheme.typography.bodySmall)
            } else if (deletionPending) {
                Text(stringResource(R.string.deletion_pending_retry), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.content_unavailable), style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = onDeleteRequested) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(
                    if (deletionPending) R.string.retry_deletion_content_description
                    else R.string.delete_content_description,
                ),
            )
        }
    }
}
