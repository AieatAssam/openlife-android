package org.openlife.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import org.openlife.app.settings.SettingsScreen
import org.openlife.app.ui.AboutScreen
import org.openlife.app.ui.SourceListScreen
import org.openlife.app.ui.SourceListUiState
import org.openlife.app.ui.ViewerScreen
import org.openlife.vault.repository.ReadyReadResult
import org.openlife.vault.repository.VaultResetResult
import java.util.UUID

@Composable
fun OpenLifeNavHost(
    listState: SourceListUiState,
    thumbnailGeneration: Long,
    ocrStates: Map<UUID, org.openlife.app.ui.OcrUiState>,
    loadThumbnail: suspend (UUID) -> android.graphics.Bitmap?,
    loadReadyContent: suspend (UUID) -> ReadyReadResult,
    delete: (UUID, () -> Unit) -> Unit,
    extractText: (UUID) -> Unit,
    cancelOcr: (UUID) -> Unit,
    correct: (UUID, org.openlife.vault.ocr.OcrSpan, String) -> Unit,
    review: (UUID, org.openlife.vault.ocr.OcrReviewState) -> Unit,
    onImportFromPhotoPicker: () -> Unit,
    onRetryVault: () -> Unit,
    onFinishReset: () -> Unit,
    onResetVault: suspend () -> VaultResetResult,
    onResetComplete: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val currentEntry by navController.currentBackStackEntryAsStateCompat()
    val isNested = currentEntry?.destination?.route != null &&
        currentEntry?.destination?.route != navController.graph.startDestinationRoute
    BackHandler(enabled = isNested) { navController.popBackStack() }

    NavHost(
        navController = navController,
        startDestination = Routes.List,
    ) {
        composable<Routes.List> {
            SourceListScreen(
                state = listState,
                loadThumbnail = loadThumbnail,
                thumbnailGeneration = thumbnailGeneration,
                onOpen = { id -> navController.navigate(Routes.Viewer(id.toString())) },
                onDelete = { id -> delete(id) {} },
                onImportFromPhotoPicker = onImportFromPhotoPicker,
                onRetryVault = onRetryVault,
                onFinishReset = onFinishReset,
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onOpenAbout = { navController.navigate(Routes.About) },
            )
        }
        composable<Routes.Viewer> { entry ->
            val route = entry.toRoute<Routes.Viewer>()
            val sourceId = remember(route.sourceId) { runCatching { UUID.fromString(route.sourceId) }.getOrNull() }
            val source = (listState as? SourceListUiState.Loaded)?.sources?.find { it.id == sourceId }
            if (listState !is SourceListUiState.Loaded) {
                SourceListScreen(
                    state = listState,
                    loadThumbnail = loadThumbnail,
                    thumbnailGeneration = thumbnailGeneration,
                    onOpen = { id -> navController.navigate(Routes.Viewer(id.toString())) },
                    onDelete = { id -> delete(id) {} },
                    onImportFromPhotoPicker = onImportFromPhotoPicker,
                    onRetryVault = onRetryVault,
                    onFinishReset = onFinishReset,
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                    onOpenAbout = { navController.navigate(Routes.About) },
                )
            } else if (source == null) {
                LaunchedEffect(route.sourceId) { navController.popBackStack() }
            } else {
                ViewerScreen(
                    source = source,
                    loadContent = { loadReadyContent(source.id) },
                    onBack = { navController.popBackStack() },
                    onDeleteRequested = {
                        delete(source.id) { navController.popBackStack() }
                    },
                    ocrState = ocrStates[source.id] ?: org.openlife.app.ui.OcrUiState.Idle,
                    onExtractText = { extractText(source.id) },
                    onCancelOcr = { cancelOcr(source.id) },
                    onCorrect = { span, correctedText -> correct(source.id, span, correctedText) },
                    onReview = { state -> review(source.id, state) },
                )
            }
        }
        composable<Routes.Settings> {
            SettingsScreen(onResetVault, onResetComplete, onBack = { navController.popBackStack() })
        }
        composable<Routes.About> { AboutScreen(onBack = { navController.popBackStack() }) }
    }
}

/** Keeps this file compatible with Navigation Compose's state API across 2.9/2.10. */
@Composable
private fun NavHostController.currentBackStackEntryAsStateCompat():
    androidx.compose.runtime.State<androidx.navigation.NavBackStackEntry?> =
    currentBackStackEntryAsState()
