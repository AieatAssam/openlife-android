package org.openlife.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.app.ui.brand.FoldedCornerCard
import org.openlife.app.ui.brand.PerforationDivider
import org.openlife.app.ui.brand.StampBadge
import org.openlife.app.ui.brand.StampState
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
    onRetryVault: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onFinishReset: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<Source?>(null) }

    val hasItems = state is SourceListUiState.Loaded && state.sources.isNotEmpty()

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_title)) },
                actions = { ListOverflowMenu(onOpenSettings = onOpenSettings, onOpenAbout = onOpenAbout) },
            )
        },
        // VISUAL_IDENTITY §7: one filled primary action per screen. An empty
        // list carries it in the body instead; loading and an unavailable
        // vault offer no import at all.
        floatingActionButton = {
            if (hasItems) {
                // The content-slot overload keeps the label a plain Text; the
                // icon/text overload hides it from semantics while animating.
                ExtendedFloatingActionButton(onClick = onImportFromPhotoPicker) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.list_import_from_photos), modifier = Modifier.padding(start = 12.dp))
                }
            }
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                SourceListUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.list_loading))
                }

                is SourceListUiState.VaultUnavailable -> VaultUnavailableScreen(
                    cause = state.cause,
                    onRetry = onRetryVault,
                    onOpenResetSettings = onOpenSettings,
                    onFinishReset = onFinishReset,
                )

                is SourceListUiState.Loaded -> {
                    if (state.sources.isEmpty()) {
                        EmptyList(onImportFromPhotoPicker)
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = LIST_BOTTOM_PADDING_FOR_FAB)) {
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
    // spacedBy, not padding(start): under RTL a wrapping label's unclipped
    // bounds would otherwise sit flush against the delete control.
    Column(modifier = Modifier.fillMaxWidth()) {
        FoldedCornerCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (deletionPending) Modifier else Modifier.clickable(onClick = onOpen))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surface),
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
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = stringResource(R.string.saved_image_thumbnail_content_description),
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        withDataSpan(sourceLabel(source), importedTime(source)),
                        modifier = Modifier.testTag("source_row_label"),
                    )
                    if (source.state == SourceState.READY) {
                        StampBadge(StampState.Saved, modifier = Modifier.padding(top = 4.dp))
                    } else if (source.state == SourceState.CORRUPT) {
                        Text(stringResource(R.string.content_unavailable), style = MaterialTheme.typography.bodySmall)
                    } else if (deletionPending) {
                        Text(
                            stringResource(R.string.deletion_pending_retry),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(stringResource(R.string.content_unavailable), style = MaterialTheme.typography.bodySmall)
                    }
                }
                IconButton(onClick = onDeleteRequested) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(
                            if (deletionPending) {
                                R.string.retry_deletion_content_description
                            } else {
                                R.string.delete_content_description
                            },
                        ),
                    )
                }
            }
        }
        PerforationDivider(modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
private fun ListOverflowMenu(onOpenSettings: () -> Unit, onOpenAbout: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.list_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_title)) },
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.about_title)) },
                onClick = {
                    expanded = false
                    onOpenAbout()
                },
            )
        }
    }
}

/** VISUAL_IDENTITY §7: a typographic empty state with exactly one action. */
@Composable
private fun EmptyList(onImportFromPhotoPicker: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.list_empty_title),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.list_empty),
            modifier = Modifier.padding(top = 12.dp),
            textAlign = TextAlign.Center,
        )
        Button(onClick = onImportFromPhotoPicker, modifier = Modifier.padding(top = 24.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.list_import_from_photos), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private val LIST_BOTTOM_PADDING_FOR_FAB = 96.dp
