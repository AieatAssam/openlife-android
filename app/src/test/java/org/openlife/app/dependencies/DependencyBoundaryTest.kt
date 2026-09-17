package org.openlife.app.dependencies

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyBoundaryTest {
    private val projectDir: Path = Path.of(System.getProperty("openlife.projectDir", "."))
    private val classpathFile: Path = projectDir.resolve("app/build/reports/classpath-release.txt")
    private val licenseReport: Path = projectDir.resolve("docs/generated/THIRD_PARTY_LICENSES.md")

    @Test
    fun releaseRuntimeClasspathContainsNoNetworkingOrTelemetryLibrary() {
        assertTrue("release classpath report is missing: $classpathFile", Files.isRegularFile(classpathFile))

        val coordinates = Files.readAllLines(classpathFile)
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val denylistedPrefixes = listOf(
            "com.google.firebase",
            "com.google.android.datatransport",
            "com.google.android.gms:play-services-measurement",
            "io.sentry",
            "com.bugsnag",
            "com.squareup.okhttp3",
            "io.ktor",
            "com.android.volley",
            "com.google.android.gms:play-services-ads",
            "org.apache.httpcomponents",
            "retrofit2",
        )
        val documentedMlKitExceptionPrefixes = setOf(
            "com.google.android.datatransport",
            "com.google.firebase",
        )
        val violations = coordinates.filter { coordinate ->
            denylistedPrefixes.any(coordinate::startsWith) &&
                documentedMlKitExceptionPrefixes.none(coordinate::startsWith)
        }
        assertTrue("denylisted release coordinates: $violations", violations.isEmpty())
    }

    @Test
    fun everyRuntimeDependencyHasAnAllowlistedLicence() {
        assertTrue("licence report is missing: $licenseReport", Files.isRegularFile(licenseReport))

        val allowedLicences = setOf(
            "Apache-2.0",
            "MIT",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "EPL-1.0",
            "EPL-2.0",
            "MPL-2.0",
            "ISC",
            "Unicode-DFS-2016",
            "CC0-1.0",
        )
        val rows = Files.readAllLines(licenseReport)
            .map(String::trim)
            .filter { it.startsWith("|") && !it.startsWith("|---") && !it.startsWith("| Module") }
        assertTrue("licence report has no dependency rows: $licenseReport", rows.isNotEmpty())
        val unknown = rows.filter { row ->
            val columns = row.trim('|').split('|').map(String::trim)
            columns.size < 2 || columns[1] !in allowedLicences
        }
        assertTrue("dependencies without an allowlisted licence: $unknown", unknown.isEmpty())
    }
}
