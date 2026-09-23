// Root build file. Plugins are declared here with apply false and applied per
// module, per the standard AGP/Kotlin DSL convention.
apply(from = "gradle/version.gradle.kts")

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.cyclonedx) apply false
    alias(libs.plugins.dependency.license.report) apply false
}

tasks.register("detekt") {
    group = "verification"
    description = "Run detekt for every shipped Kotlin module."
    dependsOn(":app:detekt", ":vault:detekt")
}

tasks.register("generateLicenseReport") {
    dependsOn(":app:generateLicenseReport")
}

val openLifeVersionCode = rootProject.extensions.extraProperties["openLifeVersionCode"] as Int
tasks.register("printVersionCode") {
    group = "versioning"
    description = "Print the versionCode derived from VERSION."
    notCompatibleWithConfigurationCache("prints a value captured from the version convention")
    doLast {
        println(openLifeVersionCode)
    }
}
