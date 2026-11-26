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
}
