// The root build file of the Trap Android application.
plugins {
    id("com.android.application") version "9.5.1" apply false
    // The Compose compiler plugin of Kotlin 2.x; it uses the version of the
    // Kotlin plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.cefrium") version "0.19.0" apply false
}

// The Rust part of the application is built by cargo-ndk; the task lives in
// the `app` module.
