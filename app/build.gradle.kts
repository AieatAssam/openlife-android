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
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.dependency.license.report)
}

tasks.withType<CyclonedxAggregateTask>().configureEach {
    jsonOutput.set(layout.buildDirectory.file("reports/bom/bom.json"))
    xmlOutput.unsetConvention()
    includeBomSerialNumber.set(false)
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
        compose = true
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

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)

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
        val coordinates = configurations.getByName("releaseRuntimeClasspath")
            .incoming.resolutionResult.allComponents
            .mapNotNull { component ->
                component.moduleVersion?.let { id ->
                    "${id.group}:${id.name}:${id.version}"
                }
            }
            .distinct()
            .sorted()
        releaseClasspathReport.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("# Resolved releaseRuntimeClasspath coordinates")
                    coordinates.forEach(::appendLine)
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
    systemProperty("openlife.projectDir", rootProject.projectDir.absolutePath)
}
