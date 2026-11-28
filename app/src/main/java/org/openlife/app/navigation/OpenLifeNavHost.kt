package org.openlife.app.navigation

import android.app.ActivityManager
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
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
import kotlinx.coroutines.flow.first
import org.openlife.app.AppContainer
import org.openlife.app.intake.InternalLink
import org.openlife.app.settings.AboutScreen
import org.openlife.app.settings.AboutViewModel
import org.openlife.app.settings.AppLockSettings
import org.openlife.app.settings.SettingsScreen
import org.openlife.app.ui.SourceListUiState
import org.openlife.app.ui.SourceListViewModel
import org.openlife.app.ui.facts.FactActions
import org.openlife.app.ui.facts.FactsScreen
import org.openlife.app.ui.facts.FactsViewModel
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
            composable<Routes.List> { entry -> ListDetailDestination(container, navController, entry, expanded) }
            composable<Routes.ListDetail> { entry -> ListDetailDestination(container, navController, entry, expanded) }
            composable<Routes.Search> { entry -> SearchDestination(container, navController, entry, expanded) }
            composable<Routes.Facts> { entry -> FactsDestination(container, navController, entry, expanded) }
            composable<Routes.Settings> { entry -> SettingsDestination(container, navController, entry) }
            composable<Routes.About> { entry -> AboutDestination(container, navController, entry) }
        }
        // Review N-04: a pending internal link opens here, where the width is known, and only
        // once this host is composed (MainActivity composes it after the lock gate).
        val currentExpanded by rememberUpdatedState(expanded)
        val pendingLink by container.internalLinks.pending.collectAsState()
        LaunchedEffect(pendingLink) {
            if (pendingLink == null) return@LaunchedEffect
            // The NavHost sets its graph during layout (P1-16 BoxWithConstraints); wait for it.
            navController.currentBackStackEntryFlow.first()
            when (val link = container.internalLinks.take()) {
                is InternalLink.OpenSource -> navController.openSource(link.sourceId, null, currentExpanded)
                null -> Unit
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

/** The root entry's list ViewModel, from the destination [entry] or any above it. */
@Composable
internal fun listViewModel(
    container: AppContainer,
    navController: NavHostController,
    entry: NavBackStackEntry,
): SourceListViewModel {
    val owner = remember(entry) { navController.getBackStackEntry<Routes.List>() }
    return viewModel(viewModelStoreOwner = owner, factory = SourceListViewModel.factory(container))
}

@Composable
private fun SearchDestination(
    container: AppContainer,
    navController: NavHostController,
    entry: NavBackStackEntry,
    expanded: Boolean,
) {
    val search: SearchViewModel = viewModel(viewModelStoreOwner = entry, factory = SearchViewModel.factory(container))
    val list = listViewModel(container, navController, entry)
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
private fun FactsDestination(
    container: AppContainer,
    navController: NavHostController,
    entry: NavBackStackEntry,
    expanded: Boolean,
) {
    val sourceId = entry.toRoute<Routes.Facts>().sourceId.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: return
    val facts: FactsViewModel = viewModel(
        viewModelStoreOwner = entry,
        factory = FactsViewModel.factory(container, sourceId),
    )
    // The facts are read while this entry is started and dropped when it stops (P3-05-R1).
    LifecycleStartEffect(facts) {
        facts.start()
        onStopOrDispose { facts.clear() }
    }
    val state by facts.state.collectAsState()
    val message by facts.message.collectAsState()
    FactsScreen(
        state = state,
        message = message,
        actions = FactActions(
            // P3-05-R1: a tap opens the viewer with the evidence span selected, through the search path.
            onOpenEvidence = { evidence ->
                runCatching { UUID.fromString(evidence.ocrSpanId) }.getOrNull()
                    ?.let { navController.openSource(sourceId, it, expanded) }
            },
            onReview = facts::review,
            onChoose = facts::choose,
            onCorrect = facts::correct,
            onFindAgain = facts::findAgain,
        ),
        onAdd = facts::add,
        onBack = { navController.popBackStack() },
    )
}

@Composable
private fun SettingsDestination(container: AppContainer, navController: NavHostController, entry: NavBackStackEntry) {
    val list = listViewModel(container, navController, entry)
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

@Composable
private fun AboutDestination(container: AppContainer, navController: NavHostController, entry: NavBackStackEntry) {
    val about: AboutViewModel = viewModel(viewModelStoreOwner = entry, factory = AboutViewModel.factory(container))
    val state by about.controller.state.collectAsState()
    val context = LocalContext.current
    val lowRam = remember { context.getSystemService(ActivityManager::class.java).isLowRamDevice }
    AboutScreen(
        state = state,
        onToggleDocument = about.controller::toggle,
        onBack = { navController.popBackStack() },
        lowRamDevice = lowRam,
    )
}

/** Material's expanded width class (840dp and up) from the space this host actually has. */
private fun isExpandedWidth(width: Dp, height: Dp): Boolean =
    WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(width.value, height.value)
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
