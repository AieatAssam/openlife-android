pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // P2-03 / ADR-0003: tesseract4android is published only on JitPack. The
        // repository serves that one group and nothing else, and every artefact
        // is SHA-256 pinned in gradle/verification-metadata.xml. Its Gradle
        // module file names a different group, so only the POM is trusted.
        exclusiveContent {
            forRepository {
                maven("https://jitpack.io") {
                    name = "JitPack"
                    metadataSources {
                        mavenPom()
                        artifact()
                    }
                }
            }
            filter { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

rootProject.name = "openlife"

include(":app")
include(":vault")
