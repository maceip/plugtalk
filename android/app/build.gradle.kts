// Gradle build for the Trap Android application.
//
// Browser rendering is provided by Cefrium (`com.cefrium:cefrium-sdk:0.19.0`,
// Chromium 156 / CEF for native Android). Trap's Rust library (`libonion_vault.so`,
// built via `cargo-ndk`) handles Arti Tor SOCKS5 circuits, URL leak policy,
// Sequoia OpenPGP keyring/crypto, and zero-JS CDP DOM classification without
// linking against `libcef.so`.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.cefrium")
}

val trapVersionFile = rootProject.file("../VERSION")
val trapDefaultVersionName = trapVersionFile
    .takeIf { it.isFile }
    ?.readText()
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "0.1.27"
val trapVersionCodeFloor = 27
val trapResolvedVersionName = providers.gradleProperty("trap.versionName")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: trapDefaultVersionName
val trapResolvedVersionCode = providers.gradleProperty("trap.versionCode")
    .orNull
    ?.trim()
    ?.toIntOrNull()
    ?: trapResolvedVersionName.split(".")
        .takeIf { parts -> parts.size == 3 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) } }
        ?.let { (maj, min, pat) ->
            val major = maj.toInt()
            val minor = min.toInt()
            val patch = pat.toInt()
            val encoded = if (major > 0 || minor > 1) {
                major * 100_000 + minor * 1_000 + patch
            } else {
                patch
            }
            maxOf(trapVersionCodeFloor, encoded)
        }
        ?: trapVersionCodeFloor

android {
    namespace = "dev.onionvault"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.onionvault"
        minSdk = 29
        targetSdk = 35
        versionCode = trapResolvedVersionCode
        versionName = trapResolvedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64-v8a for physical Android devices and Apple Silicon AVDs;
            // x86_64 for Linux CI KVM emulators.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    dependencies {
        // Cefrium 0.19.0 (Chromium 156 / CEF for Android: Java SDK, libcef.so,
        // Chromium assets, and isolated child-process services).
        implementation("com.cefrium:cefrium-sdk:0.19.0")
        implementation("androidx.appcompat:appcompat:1.7.0")
        implementation("androidx.mediarouter:mediarouter:1.7.0")
        implementation("androidx.browser:browser:1.8.0")
        implementation("androidx.biometric:biometric:1.1.0")
        implementation("androidx.core:core-ktx:1.13.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

        // Jetpack Compose UI
        val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
        implementation(composeBom)
        androidTestImplementation(composeBom)

        implementation("androidx.activity:activity-compose:1.9.3")
        implementation("androidx.compose.foundation:foundation")
        implementation("androidx.compose.material3:material3")
        implementation("androidx.compose.ui:ui")
        implementation("androidx.compose.ui:ui-graphics")
        implementation("androidx.compose.ui:ui-tooling-preview")
        debugImplementation("androidx.compose.ui:ui-tooling")
        debugImplementation("androidx.compose.ui:ui-test-manifest")

        // JVM unit & Robolectric Compose preview/UI tests
        testImplementation("junit:junit:4.13.2")
        testImplementation("org.robolectric:robolectric:4.18")
        testImplementation("androidx.test:core:1.6.1")
        testImplementation("androidx.compose.ui:ui-test-junit4")

        // On-device / emulator instrumentation tests
        androidTestImplementation("androidx.compose.ui:ui-test-junit4")
        androidTestImplementation("androidx.test.ext:junit:1.2.1")
        androidTestImplementation("androidx.test:runner:1.6.2")
        androidTestImplementation("androidx.test:rules:1.6.1")
        androidTestImplementation("androidx.test:core:1.6.1")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        jniLibs {
            // Chromium maps native libraries directly from the filesystem.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.directories.add("src/main/kotlin")
        }
        getByName("test") {
            kotlin.directories.add("src/test/kotlin")
        }
        getByName("androidTest") {
            kotlin.directories.add("src/androidTest/kotlin")
        }
    }

    androidResources {
        noCompress += listOf("pak", "dat", "bin")
    }
}

/**
 * Build `libonion_vault.so` with `cargo-ndk` for Android ABIs (`arm64-v8a` and `x86_64` by default).
 * Override via `ONIONVAULT_ANDROID_ABIS=arm64-v8a` when iterating locally on a single-ABI device.
 */
val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "build"
    description = "Builds Trap's Rust library (libonion_vault.so) with cargo-ndk."

    workingDir = rootProject.file("..")

    val configuredAbis = (System.getenv("ONIONVAULT_ANDROID_ABIS") ?: "arm64-v8a,x86_64")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val args = mutableListOf("cargo", "ndk")
    configuredAbis.forEach { abi ->
        args += listOf("-t", abi)
    }
    args += listOf(
        "--platform", "29",
        "-o", rootProject.file("app/src/main/jniLibs").absolutePath,
        "build", "--release",
    )
    commandLine(args)

    environment("ANDROID_NDK_HOME", System.getenv("ANDROID_NDK_HOME") ?: "")
    environment("CARGO_TARGET_DIR", rootProject.file("../.cargo-target").absolutePath)

    onlyIf {
        System.getenv("ONIONVAULT_SKIP_CARGO") != "1"
    }
}

/**
 * Bundle license texts (`GPL-3.0.txt`, `LGPL-2.1.txt`, `LGPL-3.0.txt`) into APK assets
 * so the in-app Open-Source Licences sheet always displays verbatim legal texts offline.
 */
val copyLegalNotices = tasks.register<Copy>("copyLegalNotices") {
    group = "build"
    description = "Copies license documents into APK assets."

    from(rootProject.file("../licenses")) {
        include("*.txt")
    }
    into(rootProject.file("app/src/main/assets/licenses"))
}

tasks.named("preBuild") {
    dependsOn(cargoBuild)
    dependsOn(copyLegalNotices)
}

tasks.withType<Test>().configureEach {
    systemProperty(
        "robolectric.dependency.repo.url",
        "https://maven-central.storage-download.googleapis.com/maven2",
    )
    systemProperty("robolectric.dependency.repo.id", "central")
    val localRobolectric = File(System.getProperty("user.home"), ".m2/robolectric-local")
    if (localRobolectric.listFiles()?.any { it.name.endsWith(".jar") } == true) {
        systemProperty("robolectric.dependency.dir", localRobolectric.absolutePath)
        systemProperty("robolectric.offline", "true")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}
