pluginManagement {
    repositories {
        // Cefrium (CEF for Android) publishes its SDK and Gradle plugin here.
        exclusiveContent {
            forRepository { maven("https://maven.cefrium.com") }
            filter { includeGroup("com.cefrium") }
        }
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2") // Maven Central mirror; the canonical host rate-limits
        mavenCentral()
        gradlePluginPortal()
    }
}

// The toolchain of the build.  The engine of CEF is built from its sources
// with the Android NDK; the service that does it is the `cefpack` crate of
// the workspace.  Nothing of the engine is fetched as a package.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2") // Maven Central mirror; the canonical host rate-limits
        mavenCentral()
        exclusiveContent {
            forRepository { maven("https://maven.cefrium.com") }
            filter { includeGroup("com.cefrium") }
        }
    }
}

rootProject.name = "Trap"
include(":app")
