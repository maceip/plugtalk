// The root build file of the Trap Android application.
plugins {
    // The Android Gradle plugin.
    id("com.android.application") version "9.4.0" apply false
    // The Compose compiler plugin of Kotlin 2.x.  On AGP 9 the Kotlin support
    // is built into the Android plugin, so the standalone Kotlin plugin is
    // not applied any more; only the Compose compiler plugin is.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

// The Rust part of the application is built by cargo-ndk; the task lives in
// the `app` module.
