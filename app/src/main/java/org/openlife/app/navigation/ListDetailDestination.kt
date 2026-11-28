package org.openlife.app.navigation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import org.openlife.app.AppContainer
import org.openlife.app.R
import org.openlife.app.ui.DecodedContent
import org.openlife.app.ui.OcrUiState
import org.openlife.app.ui.SourceListScreen
import org.openlife.app.ui.SourceListViewModel
import org.openlife.app.ui.SourceLookup
import org.openlife.app.ui.ViewerActions
import org.openlife.app.ui.ViewerBody
import org.openlife.app.ui.ViewerContentState
import org.openlife.app.ui.ViewerViewModel
import org.openlife.app.ui.evidence.EvidenceViewport

/**
 * P1-20-R3: the list and list-detail destinations. [entry] is the list root
 * or a [Routes.ListDetail] entry; the selection comes from its route and the
 * width decides the panes ([ListDetailNavigation.panes]).
 */
@Composable
internal fun ListDetailDestination(
    container: AppContainer,
    navController: NavHostController,
    entry: NavBackStackEntry,
    expanded: Boolean,
) {
    val selected = entry.takeIf { it.destination.hasRoute<Routes.ListDetail>() }
    val selectedId = selected?.toRoute<Routes.ListDetail>()?.sourceId
    val list = listViewModel(container, navController, entry)
    when (ListDetailNavigation.panes(selectedId, expanded)) {
        ListDetailPanes.LIST_ONLY -> ListPane(list, navController, expanded)

        ListDetailPanes.DETAIL_ONLY -> DetailPane(container, navController, requireNotNull(selected))

        ListDetailPanes.LIST_AND_PLACEHOLDER -> TwoPanes(list, navController) { Placeholder() }

        ListDetailPanes.LIST_AND_DETAIL -> TwoPanes(list, navController) {
            DetailPane(container, navController, requireNotNull(selected))
        }
    }
}

@Composable
private fun TwoPanes(list: SourceListViewModel, navController: NavHostController, detail: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.width(LIST_PANE_WIDTH).fillMaxHeight()) { ListPane(list, navController, true) }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { detail() }
    }
}

@Composable
private fun ListPane(list: SourceListViewModel, navController: NavHostController, expanded: Boolean) {
    val state by list.state.collectAsState()
    val generation by list.sensitiveContentGeneration.collectAsState()
    val position = list.listPosition
    val listState = rememberLazyListState(position.index, position.offset)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> position.save(index, offset) }
    }
    SourceListScreen(
        state = state,
        loadThumbnail = list::loadThumbnail,
        thumbnailGeneration = generation,
        onOpen = { id -> navController.openSource(id, null, expanded) },
        onDelete = { id -> list.delete(id) },
        onImportFromPhotoPicker = LocalPhotoPickerLauncher.current,
        onRetryVault = list::retryVault,
        onOpenSettings = { navController.navigate(Routes.Settings) },
        onFinishReset = list::finishVaultReset,
        onOpenAbout = { navController.navigate(Routes.About) },
        onOpenSearch = { navController.navigate(Routes.Search) },
        listState = listState,
    )
}

@Composable
private fun Placeholder() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp)) {
            Text(
                stringResource(R.string.list_detail_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** P1-20-R2/R4/R9: the source by id, its content held only while this entry is started. */
@Composable
private fun DetailPane(container: AppContainer, navController: NavHostController, entry: NavBackStackEntry) {
    val viewer: ViewerViewModel = viewModel(viewModelStoreOwner = entry, factory = ViewerViewModel.factory(container))
    val session = viewer.session
    LifecycleStartEffect(session, lifecycleOwner = entry) {
        session.onVisible()
        onStopOrDispose { session.onHidden() }
    }
    val exit by session.exitRequested.collectAsState()
    val current by navController.currentBackStackEntryFlow.collectAsState(entry)
    // A-10: the delete callback and the source flow both request the exit; it pops once, only from this entry.
    LaunchedEffect(exit, current) {
        if (exit && current.id == entry.id && session.consumeExit()) navController.popBackStack()
    }
    val lookup by session.source.collectAsState()
    when (val found = lookup) {
        is SourceLookup.Found -> ViewerFor(
            found = found,
            viewer = viewer,
            onOpenFacts = { navController.navigate(Routes.Facts(found.source.id.toString())) },
            onBack = { navController.popBackStack() },
        )

        SourceLookup.Missing -> CenteredNote(stringResource(R.string.content_unavailable), "viewer_unavailable")

        SourceLookup.Loading -> CenteredNote(stringResource(R.string.viewer_verifying), "viewer_loading")
    }
}

@Composable
private fun ViewerFor(
    found: SourceLookup.Found,
    viewer: ViewerViewModel,
    onOpenFacts: () -> Unit,
    onBack: () -> Unit,
) {
    val session = viewer.session
    val decoded by session.content.collectAsState()
    val ocr by session.ocr.collectAsState()
    val selectedSpanId by session.selectedSpanId.collectAsState()
    val content = remember(decoded) { decoded.toViewerContent() }
    val viewport = remember(session) { EvidenceViewport(session.zoom, Offset(session.panX, session.panY)) }
    LaunchedEffect(viewport) {
        snapshotFlow { viewport.zoom.floatValue to viewport.pan.value }
            .collect { (zoom, pan) -> session.setViewport(zoom, pan.x, pan.y) }
    }
    ViewerBody(
        source = found.source,
        content = content,
        ocrState = ocr,
        selectedSpanId = selectedSpanId,
        onSelectSpan = session::selectSpan,
        onRetryLoad = session::retry,
        readOnly = session.readOnly,
        actions = ViewerActions(
            onBack = onBack,
            onDeleteRequested = session::delete,
            onExtractText = viewer::extractText,
            onCancelOcr = viewer::cancelOcr,
            // Both carry the revision they act on, from the state that was rendered.
            onCorrect = { span, text -> viewer.correct(span.revisionId, span.id, text) },
            onReview = { state -> (ocr as? OcrUiState.Ready)?.let { viewer.review(it.revisionId, state) } },
            onRejectSpan = { span -> viewer.rejectSpan(span.id) },
            onUndoRejectSpan = { span -> viewer.undoRejectSpan(span.id) },
            onSetUserContentKind = viewer::setUserContentKind,
            onOpenFacts = onOpenFacts,
        ),
        viewport = viewport,
    )
}

@Composable
private fun CenteredNote(text: String, tag: String) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp).testTag(tag)) { Text(text) }
    }
}

private fun DecodedContent<Bitmap>.toViewerContent(): ViewerContentState = when (this) {
    DecodedContent.Loading -> ViewerContentState.Loading
    is DecodedContent.Shown -> ViewerContentState.Shown(value)
    DecodedContent.Transient -> ViewerContentState.Transient
    DecodedContent.Unreadable -> ViewerContentState.Unreadable
}

private val LIST_PANE_WIDTH = 360.dp
