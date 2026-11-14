pluginManagement {
    repositories {
        google()
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
        mavenCentral()
    }
}

rootProject.name = "Trap"
include(":app")
