package org.openlife.app.navigation

import android.app.ActivityManager
import android.graphics.Bitmap
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import org.openlife.app.AppContainer
import org.openlife.app.R
import org.openlife.app.settings.AppLockSettings
import org.openlife.app.settings.SettingsScreen
import org.openlife.app.ui.AboutScreen
import org.openlife.app.ui.DecodedContent
import org.openlife.app.ui.OcrUiState
import org.openlife.app.ui.SourceListScreen
import org.openlife.app.ui.SourceListUiState
import org.openlife.app.ui.SourceListViewModel
import org.openlife.app.ui.SourceLookup
import org.openlife.app.ui.ViewerActions
import org.openlife.app.ui.ViewerBody
import org.openlife.app.ui.ViewerContentState
import org.openlife.app.ui.ViewerViewModel
import org.openlife.app.ui.evidence.EvidenceViewport
import org.openlife.app.ui.search.SearchScreen
import org.openlife.app.ui.search.SearchViewModel
import org.openlife.app.ui.sourceLabel
import java.util.UUID

/** Starts the Photo Picker; provided by MainActivity, which owns the activity-result launcher. */
val LocalPhotoPickerLauncher = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * P1-20-R1/R3: the app's destinations. Every screen ViewModel is built from
 * [container] inside its destination and scoped to its back-stack entry; the
 * list's ViewModel belongs to the root [Routes.List] entry and is shared by
 * the list pane wherever it appears. The selected source is a route argument
 * ([Routes.ListDetail]); the width only decides which panes show, so a resize
 * never navigates. Back is the NavHost's own pop. No transitions: two panes
 * never animate over each other.
 */
@Composable
fun OpenLifeNavHost(container: AppContainer, navController: NavHostController = rememberNavController()) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expanded = isExpandedWidth(maxWidth, maxHeight)
        NavHost(
            navController = navController,
            startDestination = Routes.List,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable<Routes.List> { ListDetailDestination(container, navController, null, expanded) }
            composable<Routes.ListDetail> { entry -> ListDetailDestination(container, navController, entry, expanded) }
            composable<Routes.Search> { SearchDestination(container, navController, expanded) }
            composable<Routes.Settings> { SettingsDestination(container, navController) }
            composable<Routes.About> {
                val context = LocalContext.current
                val lowRam = remember { context.getSystemService(ActivityManager::class.java).isLowRamDevice }
                AboutScreen(onBack = { navController.popBackStack() }, lowRamDevice = lowRam)
            }
        }
    }
}

/** Opens [sourceId] by the rules in [ListDetailNavigation.select]; ids only. */
fun NavHostController.openSource(sourceId: UUID, spanId: UUID?, expanded: Boolean) {
    val destination = currentBackStackEntry?.destination
    val top = when {
        destination == null -> TopDestination.OTHER
        destination.hasRoute<Routes.List>() -> TopDestination.LIST
        destination.hasRoute<Routes.ListDetail>() -> TopDestination.LIST_DETAIL
        destination.hasRoute<Routes.Search>() -> TopDestination.SEARCH
        else -> TopDestination.OTHER
    }
    val route = Routes.ListDetail(sourceId.toString(), spanId?.toString())
    when (ListDetailNavigation.select(top, expanded)) {
        SelectAction.PUSH -> navigate(route)
        SelectAction.REPLACE_TOP -> navigate(route) { popUpTo<Routes.ListDetail> { inclusive = true } }
        SelectAction.POP_TO_LIST_THEN_PUSH -> navigate(route) { popUpTo<Routes.List> { inclusive = false } }
    }
}

/** The root entry's list ViewModel, from any destination above it. */
@Composable
private fun listViewModel(container: AppContainer, navController: NavHostController): SourceListViewModel {
    val owner = remember(navController) { navController.getBackStackEntry<Routes.List>() }
    return viewModel(viewModelStoreOwner = owner, factory = SourceListViewModel.factory(container))
}

@Composable
private fun ListDetailDestination(
    container: AppContainer,
    navController: NavHostController,
    selected: NavBackStackEntry?,
    expanded: Boolean,
) {
    val selectedId = selected?.toRoute<Routes.ListDetail>()?.sourceId
    val list = listViewModel(container, navController)
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
        listState = list.listPosition,
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
        is SourceLookup.Found -> ViewerFor(found, viewer) { navController.popBackStack() }
        SourceLookup.Missing -> CenteredNote(stringResource(R.string.content_unavailable), "viewer_unavailable")
        SourceLookup.Loading -> CenteredNote(stringResource(R.string.viewer_verifying), "viewer_loading")
    }
}

@Composable
private fun ViewerFor(found: SourceLookup.Found, viewer: ViewerViewModel, onBack: () -> Unit) {
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

@Composable
private fun SearchDestination(container: AppContainer, navController: NavHostController, expanded: Boolean) {
    val search: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
    val list = listViewModel(container, navController)
    val query by search.query.collectAsState()
    val state by search.state.collectAsState()
    val listState by list.state.collectAsState()
    SearchScreen(
        query = query,
        state = state,
        onQueryChange = search::onQueryChange,
        onOpenResult = { hit -> navController.openSource(hit.sourceId, hit.spanId, expanded) },
        onBack = { navController.popBackStack() },
        // A label only; the viewer itself loads by id.
        labelFor = { id ->
            (listState as? SourceListUiState.Loaded)?.sources?.find { it.id == id }?.let { sourceLabel(it) }
        },
    )
}

@Composable
private fun SettingsDestination(container: AppContainer, navController: NavHostController) {
    val list = listViewModel(container, navController)
    val policy by container.settings.lockPolicy.collectAsState()
    SettingsScreen(
        onResetVault = container.settings::resetVault,
        onResetComplete = {
            list.retryVault()
            navController.popBackStack<Routes.List>(inclusive = false)
        },
        onBack = { navController.popBackStack() },
        onVerifyAll = container.settings::verifyAll,
        appLock = AppLockSettings(policy, container.settings::lockAvailability, container.settings::updateLockPolicy),
    )
}

private fun DecodedContent<Bitmap>.toViewerContent(): ViewerContentState = when (this) {
    DecodedContent.Loading -> ViewerContentState.Loading
    is DecodedContent.Shown -> ViewerContentState.Shown(value)
    DecodedContent.Transient -> ViewerContentState.Transient
    DecodedContent.Unreadable -> ViewerContentState.Unreadable
}

/** Material's expanded width class (840dp and up) from the space this host actually has. */
private fun isExpandedWidth(width: Dp, height: Dp): Boolean =
    WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(width.value, height.value)
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

private val LIST_PANE_WIDTH = 360.dp
