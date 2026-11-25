package org.openlife.app.navigation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * P1-20 ethos check / SavedStateTest (JVM part): the back stack is saved
 * with the activity, so every route argument is a UUID string or null.
 * No route can carry a query, span text or bytes.
 */
class RouteSavedStateTest {
    @Test
    fun everyRouteArgumentIsAnIdString() {
        val routes = Routes::class.java.declaredClasses.filter { Routes::class.java.isAssignableFrom(it) }
        assertTrue("routes found: $routes", routes.size >= ROUTE_COUNT)
        val arguments = routes.flatMap { route ->
            route.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { route.simpleName to it }
        }
        val notIds = arguments.filterNot { (_, field) -> field.type == String::class.java && field.name.endsWith("Id") }
        assertTrue("route arguments that are not id strings: $notIds", notIds.isEmpty())
    }

    @Test
    fun theListDetailRouteRoundTripsOnlyIds() {
        val route = Routes.ListDetail(UUID(0L, 1L).toString(), UUID(0L, 2L).toString())
        val encoded = Json.encodeToString(Routes.ListDetail.serializer(), route)

        assertEquals("""{"sourceId":"${route.sourceId}","spanId":"${route.spanId}"}""", encoded)
        assertEquals(route, Json.decodeFromString(Routes.ListDetail.serializer(), encoded))
    }

    private companion object {
        /** List, ListDetail, Search, Settings, About. */
        const val ROUTE_COUNT = 5
    }
}
