package org.openlife.app.dependencies

import java.nio.file.Files
import java.nio.file.Path
import kotlin.text.Regex
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
            "Apache License, Version 2.0",
            "Android Software Development Kit License",
            "ML Kit Terms of Service",
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
        val dependencyPattern = Regex("\\*\\*Group:\\*\\* `([^`]+)` \\*\\*Name:\\*\\* `([^`]+)` \\*\\*Version:\\*\\* `([^`]+)`")
        val licensePattern = Regex("\\*\\*(?:(?:POM|Manifest) License|License URL)\\*\\*: ?([^\\n]+)")
        val dependencies = linkedMapOf<String, MutableSet<String>>()
        var currentCoordinate: String? = null
        Files.readAllLines(licenseReport).forEach { line ->
            dependencyPattern.find(line)?.let { match ->
                val coordinate = "${match.groupValues[1]}:${match.groupValues[2]}:${match.groupValues[3]}"
                currentCoordinate = coordinate
                dependencies[coordinate] = linkedSetOf()
            }
            licensePattern.find(line)?.let { match ->
                currentCoordinate?.let { coordinate ->
                    val rawLicense = match.groupValues[1].substringBefore(" - [").trim()
                    val recognized = allowedLicences.filter(rawLicense::contains)
                    dependencies.getValue(coordinate).addAll(recognized.ifEmpty { listOf(rawLicense) })
                }
            }
        }
        assertTrue("licence report has no dependency rows: $licenseReport", dependencies.isNotEmpty())
        val unknown = dependencies.filterValues { licences ->
            licences.isEmpty() || licences.any { it !in allowedLicences }
        }
        assertTrue("dependencies without an allowlisted licence: $unknown", unknown.isEmpty())
    }
}
