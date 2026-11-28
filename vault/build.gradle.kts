// See app/build.gradle.kts for why this suppression is scoped and deliberate:
// this project opted out of AGP 9's newDsl/builtInKotlin defaults (recorded in
// docs/decisions/0001-c0-defaults.md), which leaves the classic `android {}`
// extension type flagged deprecated even though it is the one in effect.
@file:Suppress("DEPRECATION")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

android {
    namespace = "org.openlife.vault"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // The test-only fault-injection and adversarial-provider hooks live behind
        // this instrumentation runner; see vault/src/androidTest.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // vault MUST NOT declare INTERNET or any broad-access permission. This is a
    // library module with no manifest of its own beyond what AGP synthesizes;
    // any future manifest addition here must be reviewed against
    // docs/THREAT_MODEL.md before merging.

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // P2-04: the OCR evaluation harness. The fixture set is packaged into the
    // androidTest APK only (assets/v1/...), never into a shipped variant, and
    // the shared metric code compiles from eval's dependency-free core.
    // scripts/check-no-eval-fixtures.sh fails a release APK that carries them.
    sourceSets {
        getByName("androidTest") {
            java.srcDir(rootProject.file("eval/src/core/kotlin"))
            assets.srcDir(rootProject.file("eval/ocr"))
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        lintConfig = file("lint.xml")
        warning.add("Accessibility")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    // Exported Room schemas are committed so migrations can be tested against
    // real prior versions instead of asserted by hand.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)
    // The bundled recognizer is local. Its transitive transport classes are
    // retained only because the ML Kit client references them at class-load;
    // the app manifest removes INTERNET and network-state permissions, so no
    // outbound transport is available in a shipped variant.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.tesseract4android.openmp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // ADR-0017: the host-test SQLite, pinned to the SQLite inside SQLCipher.
    // Test-only: it bundles host native libraries, which must never reach an
    // APK (DependencyBoundaryTest checks every non-unit-test classpath).
    testImplementation(libs.sqlite.jdbc)
    // P2-04-R5: the stray-glyph policy cost on ground truth, with the production classifier.
    testImplementation(project(":eval"))
    detektPlugins(libs.detekt.rules.ktlint.wrapper)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    systemProperty("openlife.rootDir", rootProject.projectDir.absolutePath)
    inputs.files(rootProject.fileTree("eval/ocr/v1")).withPropertyName("ocrSet")
    inputs.files(rootProject.fileTree("eval/facts/v1")).withPropertyName("factsSet")
    // A failed test prints its exception message and causes, so a CI log
    // explains a host-SQLite failure (HostSqlite.Failure carries the SQLite
    // error, the statement kind and the engine version); skips print their
    // assumption message (for example a host SQLite without FTS5 secure-delete);
    // HostSqlite prints the engine version once per test JVM on standard error.
    testLogging {
        events("failed", "skipped", "standard_error")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

detekt {
    toolVersion = libs.versions.detekt.get()
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    autoCorrect = false
}

// ADR-0017: a test-only dependency (sqlite-jdbc bundles host native libraries)
// must reach no release, debug or instrumented classpath. This report lists the
// resolved coordinates of those classpaths, plus the unit-test classpath as
// the positive control; DependencyBoundaryTest (app) reads it.
val classpathBoundaryReport = layout.buildDirectory.file("reports/classpath-boundary.txt")
tasks.register("writeClasspathBoundaryReport") {
    notCompatibleWithConfigurationCache("resolves Android variant configurations during task execution")
    outputs.file(classpathBoundaryReport)
    outputs.upToDateWhen { false }
    doLast {
        val text = buildString {
            listOf(
                "releaseRuntimeClasspath",
                "debugRuntimeClasspath",
                "debugAndroidTestRuntimeClasspath",
                "debugUnitTestRuntimeClasspath",
            ).forEach { name ->
                appendLine("## $name")
                configurations.getByName(name).incoming.resolutionResult.allComponents
                    .mapNotNull { component -> component.moduleVersion?.let { "${it.group}:${it.name}:${it.version}" } }
                    .distinct()
                    .sorted()
                    .forEach(::appendLine)
            }
        }
        classpathBoundaryReport.get().asFile.apply {
            parentFile.mkdirs()
            writeText(text)
        }
    }
}
