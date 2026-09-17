// Root build file. Plugins are declared here with apply false and applied per
// module, per the standard AGP/Kotlin DSL convention.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.cyclonedx) apply false
    alias(libs.plugins.dependency.license.report) apply false
}

tasks.register("generateLicenseReport") {
    dependsOn(":app:generateLicenseReport")
}
