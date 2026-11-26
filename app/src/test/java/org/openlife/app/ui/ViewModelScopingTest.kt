package org.openlife.app.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.app.AppContainer
import org.openlife.app.MainActivity
import org.openlife.app.ui.search.SearchViewModel

/**
 * P1-20-R1 (AS-1): no screen ViewModel belongs to the activity, and every
 * screen ViewModel is built from the app container, not the Application.
 * Reflection over the compiled classes, not a scan of source text: a
 * `by viewModels` property leaves a ViewModel-typed getter and a Lazy field.
 */
class ViewModelScopingTest {
    @Test
    fun noScreenViewModelIsActivityScoped() {
        val activity = MainActivity::class.java
        val viewModelGetters = activity.declaredMethods.filter { ViewModel::class.java.isAssignableFrom(it.returnType) }
        val lazyFields = activity.declaredFields.filter { Lazy::class.java.isAssignableFrom(it.type) }
        val viewModelFields = activity.declaredFields.filter { ViewModel::class.java.isAssignableFrom(it.type) }

        assertTrue("activity-scoped ViewModel getters: $viewModelGetters", viewModelGetters.isEmpty())
        assertTrue("activity-scoped ViewModel delegates: $lazyFields", lazyFields.isEmpty())
        assertTrue("ViewModel fields on the activity: $viewModelFields", viewModelFields.isEmpty())
    }

    @Test
    fun screenViewModelsAreBuiltFromTheContainer() {
        val screenViewModels = listOf(
            SourceListViewModel::class.java,
            ViewerViewModel::class.java,
            SearchViewModel::class.java,
        )
        screenViewModels.forEach { type ->
            val constructors = type.constructors.toList()
            assertTrue("$type has a public constructor", constructors.isNotEmpty())
            constructors.forEach { constructor ->
                val parameters = constructor.parameterTypes.toList()
                assertTrue(
                    "$type is built from the container: $parameters",
                    parameters.any { AppContainer::class.java.isAssignableFrom(it) },
                )
                assertTrue(
                    "$type takes no Application: $parameters",
                    parameters.none { Application::class.java.isAssignableFrom(it) },
                )
            }
        }
    }

    /**
     * Review N-05: every `viewModel(...)` call in the app names its owner, and
     * the owner is a navigation back-stack entry (the destination's `entry`, or
     * the list root `owner` taken from the back stack). Catches a call that
     * falls back to LocalViewModelStoreOwner (in an activity's setContent that
     * is the activity) and a `by viewModels()`
     * delegate in the activity or the graph. It cannot catch an
     * explicit owner that is the wrong entry: that is AdaptiveLayoutTest's and
     * NavigationBackTest's job on a device.
     */
    @Test
    fun everyViewModelCallNamesABackStackEntryAsItsOwner() {
        val calls = mainSources().flatMap { (name, text) ->
            VIEW_MODEL_CALL.findAll(text).map { match ->
                name to text.substring(match.range.first, minOf(text.length, match.range.last + CALL_WINDOW))
            }
        }
        assertTrue("viewModel calls found: ${calls.size}", calls.size >= MIN_CALLS)
        val unowned = calls.filterNot { (_, call) -> OWNER.containsMatchIn(call) }
        assertTrue("viewModel calls without an entry owner: $unowned", unowned.isEmpty())
        // IntakeActivity is a single-screen activity: its ViewModel is the activity's by design.
        val delegates = mainSources().filter { (name, text) ->
            name in NAVIGATION_FILES && text.contains("by viewModels")
        }
        assertTrue("activity ViewModel delegates: ${delegates.map { it.first }}", delegates.isEmpty())
    }

    private fun mainSources(): List<Pair<String, String>> =
        java.nio.file.Files.walk(java.nio.file.Paths.get("src/main/java")).use { paths ->
            paths.filter { java.nio.file.Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .map { it.fileName.toString() to it.toFile().readText() }
                .toList()
        }

    private companion object {
        const val MIN_CALLS = 3
        const val CALL_WINDOW = 120
        val NAVIGATION_FILES = setOf("MainActivity.kt", "OpenLifeNavHost.kt", "ListDetailDestination.kt")
        val VIEW_MODEL_CALL = Regex("""(?<![\w.])viewModel\(""")
        val OWNER = Regex("""^viewModel\(\s*viewModelStoreOwner = (entry|owner)\b""")
    }
}

