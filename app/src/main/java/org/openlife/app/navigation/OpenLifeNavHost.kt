package org.openlife.app.navigation

import android.app.ActivityManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import org.openlife.app.R
import org.openlife.app.settings.SettingsScreen
import org.openlife.app.ui.AboutScreen
import org.openlife.app.ui.SourceListScreen
import org.openlife.app.ui.SourceListUiState
import org.openlife.app.ui.ViewerScreen
import org.openlife.app.ui.search.SearchScreen
import org.openlife.app.ui.search.SearchUiState
import org.openlife.app.ui.sourceLabel
import org.openlife.vault.model.Source
import org.openlife.vault.model.UserContentKind
import org.openlife.vault.repository.ReadyReadResult
import org.openlife.vault.repository.VaultResetResult
import org.openlife.vault.repository.VerifyReport
import java.util.UUID

/** Everything the list and viewer destinations need; one bundle so the panes share it. */
private class SourceScreens(
    val listState: SourceListUiState,
    val thumbnailGeneration: Long,
    val ocrState: @Composable (UUID) -> org.openlife.app.ui.OcrUiState,
    val loadThumbnail: suspend (UUID) -> android.graphics.Bitmap?,
    val loadReadyContent: suspend (UUID) -> ReadyReadResult,
    val delete: (UUID, () -> Unit) -> Unit,
    val extractText: (UUID) -> Unit,
    val cancelOcr: (UUID) -> Unit,
    val correct: (revisionId: UUID, spanId: UUID, correctedText: String) -> Unit,
    val review: (revisionId: UUID, org.openlife.vault.ocr.OcrReviewState) -> Unit,
    val rejectSpan: (spanId: UUID) -> Unit,
    val undoRejectSpan: (spanId: UUID) -> Unit,
    val setUserContentKind: (UUID, UserContentKind?) -> Unit,
    val onImportFromPhotoPicker: () -> Unit,
    val onRetryVault: () -> Unit,
    val onFinishReset: () -> Unit,
) {
    fun find(id: String?): Source? {
        val uuid = id?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        return (listState as? SourceListUiState.Loaded)?.sources?.find { it.id == uuid }
    }

    @Composable
    fun List(
        onOpen: (UUID) -> Unit,
        onOpenSettings: () -> Unit,
        onOpenAbout: () -> Unit,
        onOpenSearch: (() -> Unit)? = null,
    ) {
        SourceListScreen(
            state = listState,
            loadThumbnail = loadThumbnail,
            thumbnailGeneration = thumbnailGeneration,
            onOpen = onOpen,
            onDelete = { id -> delete(id) {} },
            onImportFromPhotoPicker = onImportFromPhotoPicker,
            onRetryVault = onRetryVault,
            onFinishReset = onFinishReset,
            onOpenSettings = onOpenSettings,
            onOpenAbout = onOpenAbout,
            onOpenSearch = onOpenSearch,
        )
    }

    @Composable
    fun Viewer(source: Source, onClose: () -> Unit, initialSpanId: UUID? = null) {
        val ocr = ocrState(source.id)
        ViewerScreen(
            source = source,
            loadContent = { loadReadyContent(source.id) },
            onBack = onClose,
            onDeleteRequested = { delete(source.id, onClose) },
            ocrState = ocr,
            onExtractText = { extractText(source.id) },
            onCancelOcr = { cancelOcr(source.id) },
            // Both carry the revision they act on, from the state that was rendered.
            onCorrect = { span, correctedText -> correct(span.revisionId, span.id, correctedText) },
            onReview = { state ->
                (ocr as? org.openlife.app.ui.OcrUiState.Ready)?.let { review(it.revisionId, state) }
            },
            onRejectSpan = { span -> rejectSpan(span.id) },
            onUndoRejectSpan = { span -> undoRejectSpan(span.id) },
            onSetUserContentKind = { kind -> setUserContentKind(source.id, kind) },
            initialSelectedSpanId = initialSpanId,
        )
    }
}

/** P2-08: what the Search route shows; the query and results live in the app's SearchViewModel. */
class SearchHostState(
    val query: String = "",
    val state: SearchUiState = SearchUiState.Idle,
    val onQueryChange: (String) -> Unit = {},
)

@Composable
@Suppress("LongParameterList")
fun OpenLifeNavHost(
    listState: SourceListUiState,
    thumbnailGeneration: Long,
    ocrState: @Composable (UUID) -> org.openlife.app.ui.OcrUiState,
    loadThumbnail: suspend (UUID) -> android.graphics.Bitmap?,
    loadReadyContent: suspend (UUID) -> ReadyReadResult,
    delete: (UUID, () -> Unit) -> Unit,
    extractText: (UUID) -> Unit,
    cancelOcr: (UUID) -> Unit,
    correct: (revisionId: UUID, spanId: UUID, correctedText: String) -> Unit,
    review: (revisionId: UUID, org.openlife.vault.ocr.OcrReviewState) -> Unit,
    rejectSpan: (spanId: UUID) -> Unit = {},
    undoRejectSpan: (spanId: UUID) -> Unit = {},
    setUserContentKind: (UUID, UserContentKind?) -> Unit = { _, _ -> },
    onImportFromPhotoPicker: () -> Unit,
    onRetryVault: () -> Unit,
    onFinishReset: () -> Unit,
    onResetVault: suspend () -> VaultResetResult,
    onVerifyAll: suspend () -> VerifyReport?,
    appLock: org.openlife.app.settings.AppLockSettings,
    onResetComplete: () -> Unit,
    navController: NavHostController = rememberNavController(),
    search: SearchHostState? = null,
) {
    val screens = SourceScreens(
        listState, thumbnailGeneration, ocrState, loadThumbnail, loadReadyContent, delete, extractText,
        cancelOcr, correct, review, rejectSpan, undoRejectSpan, setUserContentKind, onImportFromPhotoPicker,
        onRetryVault, onFinishReset,
    )
    val currentEntry by navController.currentBackStackEntryAsStateCompat()
    val isNested = currentEntry?.destination?.route != null &&
        currentEntry?.destination?.route != navController.graph.startDestinationRoute
    BackHandler(enabled = isNested) { navController.popBackStack() }
    // P1-16-R1: the item shown in the detail pane at expanded width.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // P2-08: the span a search result asked for; a UUID string like selectedId, never content.
    var selectedSpanId by rememberSaveable { mutableStateOf<String?>(null) }
    val openSearch: (() -> Unit)? = search?.let { { navController.navigate(Routes.Search) } }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expanded = isExpandedWidth(maxWidth, maxHeight)
        BackHandler(enabled = expanded && !isNested && selectedId != null) { selectedId = null }
        NavHost(navController = navController, startDestination = Routes.List) {
            composable<Routes.List> {
                if (expanded) {
                    ListDetail(
                        screens = screens,
                        selected = screens.find(selectedId),
                        selectedSpanId = selectedSpanId.toUuidOrNull(),
                        onSelect = {
                            selectedId = it?.toString()
                            selectedSpanId = null
                        },
                        onOpenSettings = { navController.navigate(Routes.Settings) },
                        onOpenAbout = { navController.navigate(Routes.About) },
                        onOpenSearch = openSearch,
                    )
                } else {
                    // A selection made at expanded width continues as the full-screen viewer.
                    LaunchedEffect(selectedId) {
                        selectedId?.let { navController.navigate(Routes.Viewer(it, selectedSpanId)) }
                        selectedId = null
                        selectedSpanId = null
                    }
                    screens.List(
                        onOpen = { id -> navController.navigate(Routes.Viewer(id.toString())) },
                        onOpenSettings = { navController.navigate(Routes.Settings) },
                        onOpenAbout = { navController.navigate(Routes.About) },
                        onOpenSearch = openSearch,
                    )
                }
            }
            composable<Routes.Viewer> { entry ->
                val route = entry.toRoute<Routes.Viewer>()
                if (expanded) {
                    // At expanded width the viewer is the list's detail pane, not a separate screen.
                    LaunchedEffect(route.sourceId, route.spanId) {
                        selectedId = route.sourceId
                        selectedSpanId = route.spanId
                        // Back to the list itself, also when opened from Search (P2-08).
                        navController.popBackStack<Routes.List>(inclusive = false)
                    }
                } else {
                    ViewerDestination(screens, route.sourceId, route.spanId.toUuidOrNull(), navController)
                }
            }
            composable<Routes.Search> {
                val host = search ?: SearchHostState()
                SearchScreen(
                    query = host.query,
                    state = host.state,
                    onQueryChange = host.onQueryChange,
                    onOpenResult = { hit ->
                        navController.navigate(Routes.Viewer(hit.sourceId.toString(), hit.spanId?.toString()))
                    },
                    onBack = { navController.popBackStack() },
                    labelFor = { id -> screens.find(id.toString())?.let { sourceLabel(it) } },
                )
            }
            composable<Routes.Settings> {
                SettingsScreen(
                    onResetVault,
                    onResetComplete,
                    onBack = { navController.popBackStack() },
                    onVerifyAll = onVerifyAll,
                    appLock = appLock,
                )
            }
            composable<Routes.About> {
                val context = LocalContext.current
                val lowRam = remember { context.getSystemService(ActivityManager::class.java).isLowRamDevice }
                AboutScreen(onBack = { navController.popBackStack() }, lowRamDevice = lowRam)
            }
        }
    }
}

@Composable
private fun ViewerDestination(
    screens: SourceScreens,
    sourceId: String,
    spanId: UUID?,
    navController: NavHostController,
) {
    val source = screens.find(sourceId)
    if (screens.listState !is SourceListUiState.Loaded) {
        // Deep links wait for a loaded list before resolving the item.
        screens.List(
            onOpen = { id -> navController.navigate(Routes.Viewer(id.toString())) },
            onOpenSettings = { navController.navigate(Routes.Settings) },
            onOpenAbout = { navController.navigate(Routes.About) },
        )
    } else if (source == null) {
        LaunchedEffect(sourceId) { navController.popBackStack() }
    } else {
        screens.Viewer(source, onClose = { navController.popBackStack() }, initialSpanId = spanId)
    }
}

@Composable
@Suppress("LongParameterList")
private fun ListDetail(
    screens: SourceScreens,
    selected: Source?,
    selectedSpanId: UUID?,
    onSelect: (UUID?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSearch: (() -> Unit)?,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.width(LIST_PANE_WIDTH).fillMaxHeight()) {
            screens.List(
                onOpen = { onSelect(it) },
                onOpenSettings = onOpenSettings,
                onOpenAbout = onOpenAbout,
                onOpenSearch = onOpenSearch,
            )
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (selected != null) {
                // key: a new selection gets a fresh viewer, never the previous item's decoded state.
                key(selected.id, selectedSpanId) {
                    screens.Viewer(selected, onClose = { onSelect(null) }, initialSpanId = selectedSpanId)
                }
            } else {
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
        }
    }
}

/** Material's expanded width class (840dp and up) from the space this host actually has. */
private fun isExpandedWidth(width: Dp, height: Dp): Boolean =
    WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(width.value, height.value)
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

private val LIST_PANE_WIDTH = 360.dp

private fun String?.toUuidOrNull(): UUID? = this?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/** Keeps this file compatible with Navigation Compose's state API across 2.9/2.10. */
@Composable
private fun NavHostController.currentBackStackEntryAsStateCompat():
    androidx.compose.runtime.State<androidx.navigation.NavBackStackEntry?> =
    currentBackStackEntryAsState()
