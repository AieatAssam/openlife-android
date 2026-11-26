package org.openlife.app.boundary

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review N-02: no ViewModel or app container holds Compose UI state. A
 * LazyListState keeps its last layout (placeables, painters, bitmaps) and a
 * remeasurement node that reaches the activity; held in a ViewModel it
 * outlives the scrub and the activity. A source scan of properties in the
 * files that declare a ViewModel or the container. It cannot see such state
 * reached indirectly, through a type that wraps it.
 */
class ComposeStateBoundaryTest {
    @Test
    fun noViewModelOrContainerHoldsComposeUiState() {
        val holders = kotlinSources().filter { (_, text) ->
            text.contains(": ViewModel()") || text.contains("AppContainer {")
        }
        assertTrue("holders found: ${holders.size}", holders.size >= MIN_HOLDERS)
        val offending = holders.flatMap { (path, text) ->
            text.lines().withIndex()
                .filter { (_, line) -> PROPERTY.containsMatchIn(line) && COMPOSE_STATE.containsMatchIn(line) }
                .map { (index, line) -> "${path.fileName}:${index + 1}: ${line.trim()}" }
        }
        assertTrue("Compose UI state held by a ViewModel or container: $offending", offending.isEmpty())
    }

    private fun kotlinSources(): List<Pair<Path, String>> =
        Files.walk(Paths.get("src/main/java")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .map { it to it.readText() }
                .toList()
        }

    private companion object {
        const val MIN_HOLDERS = 4
        val PROPERTY = Regex("""^\s*(private |internal |override |protected )*(val|var) """)
        val COMPOSE_STATE = Regex(
            """\b(LazyListState|LazyGridState|ScrollState|PagerState|Painter|ImageBitmap|""" +
                """TransformableState|DrawerState|SnackbarHostState|TextFieldState)\b""",
        )
    }
}
