// The classic `android {}` extension type is flagged deprecated by AGP 9.x
// (superseded by com.android.build.api.dsl.ApplicationExtension under
// android.newDsl=true) even though this project has deliberately opted out
// via android.newDsl=false / android.builtInKotlin=false (see
// docs/decisions/0001-c0-defaults.md). The suppression below is scoped to
// that recorded, reviewed choice, not a blanket deprecation silence.
@file:Suppress("DEPRECATION")

import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryMarkdownReportRenderer
import com.github.jk1.license.render.ReportRenderer
import org.cyclonedx.gradle.CyclonedxAggregateTask
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test
import java.util.ArrayDeque

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.dependency.license.report)
    alias(libs.plugins.detekt)
}

tasks.withType<CyclonedxAggregateTask>().configureEach {
    jsonOutput.set(layout.buildDirectory.file("reports/bom/bom.json"))
    xmlOutput.unsetConvention()
    includeBomSerialNumber.set(false)
}

tasks.withType<CyclonedxDirectTask>().configureEach {
    includeConfigs.set(listOf("releaseRuntimeClasspath"))
}

android {
    namespace = "org.openlife.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.openlife"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1-c0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["profileInstallerReceiverClass"] =
            "androidx.profileinstaller.ProfileInstallReceiver"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Debug-key signing so CI can produce an installable release
            // APK/AAB for internal testing without a real keystore secret.
            // This is not store-distribution signing - replace with a real
            // signing config (from a secret keystore) before any real
            // release, per docs/decisions/0001-c0-defaults.md's pending
            // pre-distribution decisions.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // Debug-only hostile/adversarial content provider lives in src/debug and
    // must never be packaged into release (enforced by source-set placement,
    // not a build-type flag).

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        lintConfig = file("lint.xml")
        warning.add("Accessibility")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":vault"))

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    detektPlugins(libs.detekt.rules.ktlint.wrapper)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.uiautomator)
}

val releaseClasspathReport = layout.buildDirectory.file("reports/classpath-release.txt")
val writeReleaseRuntimeClasspath = tasks.register("writeReleaseRuntimeClasspath") {
    notCompatibleWithConfigurationCache("uses Android's resolved release configuration during task execution")
    outputs.file(releaseClasspathReport)
    outputs.upToDateWhen { false }
    doLast {
        val resolutionResult = configurations.getByName("releaseRuntimeClasspath")
            .incoming.resolutionResult
        val coordinates = resolutionResult.allComponents
            .mapNotNull { component ->
                component.moduleVersion?.let { id ->
                    "${id.group}:${id.name}:${id.version}"
                }
            }
            .distinct()
            .sorted()
        val denylistPaths = linkedSetOf<String>()
        fun coordinate(component: ResolvedComponentResult): String? = component.moduleVersion?.let { id ->
            "${id.group}:${id.name}:${id.version}"
        }
        fun shortestPathTo(target: ResolvedComponentResult): List<String>? {
            val queue = ArrayDeque<Pair<ResolvedComponentResult, List<String>>>()
            val visited = mutableSetOf<String>()
            queue.add(resolutionResult.root to emptyList())
            while (queue.isNotEmpty()) {
                val (component, path) = queue.removeFirst()
                if (!visited.add(component.id.displayName)) {
                    continue
                }
                val componentCoordinate = coordinate(component)
                val currentPath = if (componentCoordinate == null) {
                    path
                } else {
                    path + componentCoordinate
                }
                if (component.id.displayName == target.id.displayName) {
                    return currentPath
                }
                component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { dependency ->
                    queue.add(dependency.selected to currentPath)
                }
            }
            return null
        }
        resolutionResult.allComponents
            .filter { component ->
                coordinate(component)?.let { selectedCoordinate ->
                    selectedCoordinate.startsWith("com.google.firebase") ||
                        selectedCoordinate.startsWith("com.google.android.datatransport")
                } == true
            }
            .forEach { component ->
                shortestPathTo(component)?.let { path ->
                    denylistPaths += "denylist-path: ${path.joinToString(" -> ")}"
                }
            }
        releaseClasspathReport.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("# Resolved releaseRuntimeClasspath coordinates")
                    coordinates.forEach(::appendLine)
                    appendLine("# Denylist dependency paths")
                    denylistPaths.sorted().forEach(::appendLine)
                },
            )
        }
    }
}

licenseReport {
    outputDir = rootProject.layout.projectDirectory.dir("docs/generated").asFile.absolutePath
    projects = arrayOf(project)
    configurations = arrayOf("releaseRuntimeClasspath")
    renderers = arrayOf<ReportRenderer>(
        InventoryMarkdownReportRenderer(
            "THIRD_PARTY_LICENSES.md",
            "OpenLife runtime dependencies",
            rootProject.layout.projectDirectory.file("config/license-overrides.txt").asFile,
            false,
            true,
        ),
    )
    filters = arrayOf<DependencyFilter>(LicenseBundleNormalizer())
    allowedLicensesFile = rootProject.layout.projectDirectory.file("config/allowed-licenses.json").asFile
}

tasks.withType<Test>().configureEach {
    dependsOn(writeReleaseRuntimeClasspath)
    dependsOn("checkLicense")
    dependsOn("processReleaseMainManifest")
    dependsOn("processReleaseManifest")
    systemProperty("openlife.projectDir", rootProject.projectDir.absolutePath)
}

// Keep the repository boundary tests on the standard lint path so a manifest
// or source-egress regression cannot pass a lint-only pre-commit invocation.
tasks.named("lint") {
    dependsOn("test")
}

detekt {
    toolVersion = libs.versions.detekt.get()
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    autoCorrect = false
}

val releaseDependencyAudit = tasks.register("releaseDependencyAudit") {
    group = "verification"
    description = "Generate and validate all dependency audit artefacts for release."
    dependsOn(writeReleaseRuntimeClasspath, "checkLicense", "generateLicenseReport", "cyclonedxBom")
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        dependsOn(releaseDependencyAudit)
    }
}
