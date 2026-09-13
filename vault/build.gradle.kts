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
}

android {
    namespace = "org.openlife.vault"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
