pluginManagement {
    repositories {
        // Cefrium (CEF for native Android) publishes its Gradle plugin and SDK here.
        exclusiveContent {
            forRepository { maven("https://maven.cefrium.com") }
            filter { includeGroup("com.cefrium") }
        }
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven("https://maven.cefrium.com") }
            filter { includeGroup("com.cefrium") }
        }
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
    }
}

rootProject.name = "Trap"
include(":app")
