package org.openlife.app.dependencies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

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
        val policy = policyDocument()
        val denylistedPrefixes = fencedSection(policy, "Denylist")
        val documentedMlKitExceptionPrefixes = policy
            .first { it.startsWith("coordinates:") }
            .substringAfter(':')
            .split(',')
            .map(String::trim)
        val violations = coordinates.filter { coordinate ->
            denylistedPrefixes.any(coordinate::startsWith) &&
                documentedMlKitExceptionPrefixes.none(coordinate::startsWith)
        }
        assertTrue("denylisted release coordinates: $violations", violations.isEmpty())
    }

    @Test
    fun documentedMlKitExceptionHasAReleaseGraphProvenancePath() {
        assertTrue("release classpath report is missing: $classpathFile", Files.isRegularFile(classpathFile))

        val policy = policyDocument()
        val exceptionIds = policy.filter { it.startsWith("id:") }.map { it.substringAfter(':').trim() }
        assertEquals(listOf("mlkit-transitive-transport"), exceptionIds)
        val exceptionCoordinates = policy
            .first { it.startsWith("coordinates:") }
            .substringAfter(':')
            .split(',')
            .map(String::trim)
        val origin = policy.first { it.startsWith("origin:") }.substringAfter(':').trim()
        val lines = Files.readAllLines(classpathFile)
        assertTrue(
            "ML Kit exception origin is no longer present: $origin",
            lines.any { it.startsWith("$origin:") },
        )

        val denylisted = lines.filter { line -> exceptionCoordinates.any(line::startsWith) }
        assertTrue("the documented exception has no resolved coordinates", denylisted.isNotEmpty())
        denylisted.forEach { coordinate ->
            assertTrue(
                "exception coordinate has no ML Kit provenance: $coordinate",
                lines.any { line ->
                    line.startsWith("denylist-path:") &&
                        line.contains(origin) &&
                        line.contains(coordinate)
                },
            )
        }
    }

    @Test
    fun everyRuntimeDependencyHasAnAllowlistedLicence() {
        assertTrue("licence report is missing: $licenseReport", Files.isRegularFile(licenseReport))

        val allowedLicences = fencedSection(policyDocument(), "Allowlisted licences")
        val dependencyPattern = Regex(
            "\\*\\*Group:\\*\\* `([^`]+)` \\*\\*Name:\\*\\* `([^`]+)` \\*\\*Version:\\*\\* `([^`]+)`",
        )
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

    /**
     * P1-08 review K-06: About shows docs/generated/THIRD_PARTY_LICENSES.md as
     * the app's licences, so it must name exactly the release runtime
     * classpath (project modules, version "unspecified", aside). Regenerate it
     * with `./gradlew :app:generateLicenseReport` when this fails.
     */
    @Test
    fun licenceReportListsExactlyTheReleaseRuntimeClasspath() {
        assertTrue("release classpath report is missing: $classpathFile", Files.isRegularFile(classpathFile))
        val classpath = Files.readAllLines(classpathFile)
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("denylist-path:") }
            .filterNot { it.endsWith(":unspecified") }
            .toSet()
        val report = Files.readAllLines(licenseReport)
            .mapNotNull { line -> REPORT_ROW.find(line)?.groupValues?.drop(1)?.joinToString(":") }
            .toSet()
        assertTrue("classpath report has no coordinates", classpath.isNotEmpty())
        assertEquals(emptyList<String>(), licenceDrift(classpath, report))

        // Recorded mutations: a new dependency without a report row, and a row for one no longer shipped.
        assertFalse(licenceDrift(classpath + "org.example:new-library:1.0", report).isEmpty())
        assertFalse(licenceDrift(classpath, report + "org.example:removed-library:1.0").isEmpty())
    }

    /**
     * ADR-0017: a test-only dependency (sqlite-jdbc, which bundles host native
     * libraries) reaches no release, debug or instrumented classpath of `app`
     * or `vault`. The vault unit-test classpath must contain it, so the scan
     * cannot pass by reading the wrong report.
     */
    @Test
    fun testOnlyDependenciesReachNoReleaseDebugOrInstrumentedClasspath() {
        val testOnly = fencedSection(policyDocument(), "Test-only dependencies")
        assertTrue("no test-only coordinates in the policy", testOnly.isNotEmpty())
        val reports = BOUNDARY_MODULES.associateWith(::boundaryReport)
        assertEquals(emptyList<String>(), testOnlyLeaks(reports, testOnly))

        val unitTest = reports.getValue("vault").getValue(UNIT_TEST_CLASSPATH)
        testOnly.forEach { prefix ->
            assertTrue(
                "positive control: vault unit tests resolve $prefix",
                unitTest.any { it.startsWith("$prefix:") },
            )
        }

        // Recorded mutation: the same coordinate on vault's instrumented classpath.
        val leaked = reports + ("vault" to reports.getValue("vault") + (INSTRUMENTED to unitTest))
        assertFalse(testOnlyLeaks(leaked, testOnly).isEmpty())
    }

    private fun testOnlyLeaks(reports: Map<String, Map<String, List<String>>>, testOnly: Set<String>): List<String> =
        reports.flatMap { (module, classpaths) ->
            GUARDED_CLASSPATHS.flatMap { name ->
                val coordinates = classpaths[name] ?: return@flatMap listOf("$module: $name is missing from the report")
                coordinates.filter { coordinate -> testOnly.any { coordinate.startsWith("$it:") } }
                    .map { "$module $name: $it" }
            }
        }

    private fun boundaryReport(module: String): Map<String, List<String>> {
        val file = projectDir.resolve("$module/build/reports/classpath-boundary.txt")
        assertTrue("classpath boundary report is missing: $file", Files.isRegularFile(file))
        val classpaths = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        Files.readAllLines(file).map(String::trim).filter(String::isNotEmpty).forEach { line ->
            if (line.startsWith("## ")) {
                current = mutableListOf<String>().also { classpaths[line.removePrefix("## ")] = it }
            } else {
                current?.add(line)
            }
        }
        return classpaths
    }

    private fun licenceDrift(classpath: Set<String>, report: Set<String>): List<String> =
        (classpath - report).sorted().map { "shipped but not in the licence report: $it" } +
            (report - classpath).sorted().map { "in the licence report but not shipped: $it" }

    private fun policyDocument(): List<String> {
        val resource = checkNotNull(javaClass.getResourceAsStream("/dependency-policy.md")) {
            "dependency policy test resource is missing"
        }.bufferedReader().use { it.readLines() }
        assertTrue(
            "test policy resource diverges from docs/dependency-policy.md",
            resource == Files.readAllLines(projectDir.resolve("docs/dependency-policy.md")),
        )
        return resource
    }

    private fun fencedSection(lines: List<String>, heading: String): Set<String> {
        val start = lines.indexOf("## $heading")
        assertTrue("policy section is missing: $heading", start >= 0)
        val firstFence = (start until lines.size).firstOrNull { lines[it] == "```text" } ?: -1
        val closingFence = if (firstFence >= 0) {
            (firstFence + 1 until lines.size).firstOrNull { lines[it] == "```" } ?: -1
        } else {
            -1
        }
        assertTrue("policy block is missing: $heading", firstFence >= 0 && closingFence > firstFence)
        return lines.subList(firstFence + 1, closingFence)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
    }

    private companion object {
        val BOUNDARY_MODULES = listOf("app", "vault")
        const val INSTRUMENTED = "debugAndroidTestRuntimeClasspath"
        const val UNIT_TEST_CLASSPATH = "debugUnitTestRuntimeClasspath"
        val GUARDED_CLASSPATHS = listOf("releaseRuntimeClasspath", "debugRuntimeClasspath", INSTRUMENTED)
        val REPORT_ROW = Regex(
            "\\*\\*Group:\\*\\* `([^`]+)` \\*\\*Name:\\*\\* `([^`]+)` \\*\\*Version:\\*\\* `([^`]+)`",
        )
    }
}
