// P2-04: the synthetic OCR evaluation set (eval/ocr/v1), its generator, and
// the metric and gate code. A plain JVM module: no shipped configuration of
// :app or :vault depends on it, and its only dependency is JUnit for tests.
//
// src/core/kotlin uses the Kotlin standard library and java.security only. It
// is compiled here and, as a source directory, into vault's androidTest APK so
// the on-device harness scores with the same tested code. The generator in
// src/main/kotlin uses java.awt and stays on the host.
//
// The Kotlin JVM plugin comes from the Kotlin Gradle plugin already on the
// root build classpath (applied there through kotlin.android), so it is
// requested without a version and adds no plugin artefact.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.detekt)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

sourceSets {
    named("main") {
        kotlin.srcDir("src/core/kotlin")
    }
}

// The JVM plugin puts Kotlin's scripting compiler plugin on the compiler
// plugin classpath. This module has no scripts, and those artefacts are not in
// gradle/verification-metadata.xml, so they are left out rather than pinned.
configurations.matching { it.name.startsWith("kotlinCompilerPluginClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-compiler-embeddable")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-compiler-impl-embeddable")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-common")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-jvm")
}

dependencies {
    testImplementation(libs.junit)
    detektPlugins(libs.detekt.rules.ktlint.wrapper)
}

val ocrSetDir = rootProject.layout.projectDirectory.dir("eval/ocr/v1")

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
    systemProperty("openlife.rootDir", rootProject.projectDir.absolutePath)
    inputs.files(fileTree(ocrSetDir)).withPropertyName("ocrSet")
    inputs.files(fileTree("fonts")).withPropertyName("fonts")
}

tasks.register<JavaExec>("generateOcrFixtures") {
    group = "evaluation"
    description = "Regenerate eval/ocr/v1 (images, truth, manifest) from the committed generator."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.openlife.eval.ocr.fixtures.GenerateOcrFixturesKt")
    systemProperty("java.awt.headless", "true")
    args(rootProject.projectDir.absolutePath, ocrSetDir.asFile.absolutePath)
}

// Fails unless every required engine has a results file that passes the
// gate. With no results file the verdict is NO_MEASUREMENT and the task
// fails: the gate never passes on absent data. P2-05 runs this before the
// engine switch. -PocrResults=a.json,b.json  -PocrRequire=tesseract-eng-fast
tasks.register<JavaExec>("checkOcrGate") {
    group = "evaluation"
    description = "Evaluate OCR results files against eval/ocr/v1/gate.json."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.openlife.eval.ocr.report.OcrEvalReportKt")
    val results = providers.gradleProperty("ocrResults").orElse("")
    val require = providers.gradleProperty("ocrRequire").orElse("")
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(ocrSetDir.asFile.absolutePath, "--results=${results.get()}", "--require=${require.get()}")
        },
    )
}

detekt {
    toolVersion = libs.versions.detekt.get()
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    autoCorrect = false
    source.setFrom("src/main/kotlin", "src/core/kotlin", "src/test/kotlin")
}
