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
