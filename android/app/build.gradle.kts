// The Gradle build of the Trap Android application.
//
// The native part of Trap is written in Rust and built with `cargo-ndk`.
//
// The engine of the browser is **CEF**, built from its own sources with the
// Android NDK by the `cefpack` service of the workspace.  CEF publishes no
// Android distribution, so the service builds one; the build of Gradle only
// consumes the result.  Nothing of the engine is fetched as a package.

import java.util.Properties
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    // The Compose compiler plugin of Kotlin 2.x; it has to use the version
    // of the Kotlin plugin.
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The bundle of the engine, as produced by `cefpack build`.
 *
 * It carries the engine (`lib/libcef.so`), the C API shim that the Rust
 * library links against (`lib/libcef_dll_wrapper.a`), the public headers of
 * the version that was built (`include/`) and the resources that the engine
 * reads at runtime (`Resources/`).
 *
 * The bundle is the output of a build that takes hours; the build of Gradle
 * therefore consumes it and never starts it.  The path comes from the
 * environment, because the bundle lives outside the tree.
 */
val cefBundle: File = File(
    System.getenv("CEF_ANDROID_ROOT")
        ?: rootProject.file("../.research/cef-bundle").absolutePath
)

/**
 * The ABI that Trap ships.
 */
val cefAbi = "arm64-v8a"

/**
 * The staging directory of the bundle.
 *
 * The engine goes into the native libraries of the APK, the resources into
 * the assets and the licences next to them.
 */
val cefStaging: File = layout.buildDirectory.dir("cef-staging").get().asFile

/**
 * Stage the bundle of the engine for the APK.
 *
 * The task runs the bundling service, which also checks that the bundle
 * carries what the application needs: the engine, the headers, the resources
 * and the licence.  A bundle that is incomplete stops the build here and not
 * on the device of a user.
 */
val stageCefBundle = tasks.register<Exec>("stageCefBundle") {
    group = "build"
    description = "Stages the bundle of the engine for the APK."

    val bundle = cefBundle
    val staging = cefStaging
    val abi = cefAbi

    inputs.dir(bundle).withPropertyName("cefBundle")
    outputs.dir(staging).withPropertyName("cefStaging")

    workingDir = rootProject.file("..")
    commandLine(
        "cargo", "run", "--release", "-p", "cefpack", "--",
        "pack", bundle.absolutePath, staging.absolutePath
    )

    environment("CARGO_TARGET_DIR", rootProject.file("../.cargo-target").absolutePath)
    environment("CEF_ANDROID_ROOT", bundle.absolutePath)

    onlyIf {
        System.getenv("ONIONVAULT_SKIP_CARGO") != "1"
    }
}

/**
 * Copy the engine and its resources into the tree of the application.
 *
 * They are written where the Android plugin looks for them: the engine into
 * the native libraries of the ABI, the resources into the assets.  The
 * resources must not be compressed, because the engine maps them out of the
 * APK.
 */
val installCefBundle = tasks.register<Copy>("installCefBundle") {
    group = "build"
    description = "Copies the engine and its resources into the application."

    dependsOn(stageCefBundle)

    from(File(cefStaging, "jniLibs")) {
        into(rootProject.file("app/src/main/jniLibs"))
    }
    from(File(cefStaging, "assets")) {
        into(rootProject.file("app/src/main/assets"))
    }
}

android {
    namespace = "dev.onionvault"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.onionvault"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Trap ships a single ABI: the arm64 one.
            abiFilters += listOf(cefAbi)
        }

    }

    // There is no CMake project: the whole native part of Trap is
    // written in Rust and built by cargo-ndk.

    buildFeatures {
        // The user interface is written with Jetpack Compose.
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    dependencies {
        // --- the user interface ------------------------------------------------
        // Every version of the Compose libraries comes from the same BOM, so
        // that they cannot drift apart.
        val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
        implementation(composeBom)
        androidTestImplementation(composeBom)

        // `PredictiveBackHandler` lives in the activity library.
        implementation("androidx.activity:activity-compose:1.9.3")
        implementation("androidx.compose.foundation:foundation")
        implementation("androidx.compose.material3:material3")
        implementation("androidx.compose.ui:ui")
        implementation("androidx.compose.ui:ui-graphics")
        implementation("androidx.compose.ui:ui-tooling-preview")
        debugImplementation("androidx.compose.ui:ui-tooling")
        debugImplementation("androidx.compose.ui:ui-test-manifest")

        // The biometric prompt confirms the use of the master wrapping key.
        implementation("androidx.biometric:biometric:1.1.0")
        implementation("androidx.core:core-ktx:1.13.1")

        // --- the screenshot tests ----------------------------------------------
        // The previews are rendered on the JVM, so that the continuous
        // integration can prove that every screen inflates without an
        // emulator and without a device.
        testImplementation("junit:junit:4.13.2")
        testImplementation("org.robolectric:robolectric:4.14.1")
        testImplementation("androidx.compose.ui:ui-test-junit4")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // The screenshot tests need the resources of the application.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // The Kotlin support is built into AGP 9; the compiler options live in
    // the `kotlin` block of the module, not in `android.kotlinOptions`.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    packaging {
        jniLibs {
            // The engine is a shared object with its own resources; it has to
            // be stored in the APK and extracted from it, not compressed.
            useLegacyPackaging = true
            pickFirsts += "**/libcef.so"
        }
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    // The resources of the engine (icudtl.dat, the .pak files, the locales)
    // are mapped out of the APK by the engine; they must not be compressed.
    androidResources {
        noCompress += listOf("pak", "dat", "bin")
    }
}

/** Build the Rust part of the application with cargo-ndk. */
val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "build"
    description = "Builds the Rust part of Trap with cargo-ndk."

    dependsOn(stageCefBundle)

    workingDir = rootProject.file("..")
    commandLine(
        "cargo", "ndk",
        "-t", cefAbi,
        // The API level of the NDK: `libnativewindow`, which carries the
        // `ANativeWindow` of the off-screen rendering, exists from API 24;
        // the application supports API 29 and later.
        "--platform", "29",
        "-o", rootProject.file("app/src/main/jniLibs").absolutePath,
        "build", "--release"
    )

    environment("ANDROID_NDK_HOME", System.getenv("ANDROID_NDK_HOME") ?: "")
    environment("CARGO_TARGET_DIR", rootProject.file("../.cargo-target").absolutePath)
    // The Rust code is compiled against the headers of the very engine that
    // is shipped, and it links against the very engine that is shipped.
    environment("CEF_ANDROID_ROOT", cefBundle.absolutePath)

    onlyIf {
        System.getenv("ONIONVAULT_SKIP_CARGO") != "1"
    }
}

/**
 * Copy the licence documents into the assets of the APK.
 *
 * The LGPL-3.0 of the engine asks for the recipient of the binary to be able
 * to read the licence of the library: the texts therefore travel with the
 * application and are shown by the interface.
 *
 * The texts of the repository are in `licenses/`; the notice of the engine
 * belongs to the exact build of the engine and is part of the bundle.
 */
val copyLegalNotices = tasks.register<Copy>("copyLegalNotices") {
    group = "build"
    description = "Copies the licence documents into the assets of the application."

    from(rootProject.file("../licenses")) {
        include("*.txt")
    }
    // The notice of the engine belongs to the exact build of the engine.
    val engineLicence = File(cefBundle, "LICENSE.txt")
    if (engineLicence.isFile) {
        from(engineLicence) {
            rename { "CEF-LICENSE.txt" }
        }
    }
    into(rootProject.file("app/src/main/assets/licenses"))
}

tasks.named("preBuild") {
    dependsOn(cargoBuild)
    dependsOn(copyLegalNotices)
    dependsOn(installCefBundle)
}

/**
 * The screenshot tests of the previews.
 *
 * Robolectric downloads the Android image of the tests; the image is cached
 * in the local Maven repository, so that the continuous integration does not
 * fetch it again for every build.
 */
tasks.withType<Test>().configureEach {
    // A local image of Android that was downloaded before avoids the rate
    // limit of the Maven mirror; the continuous integration uses the
    // dependency itself.
    // The image of Android is downloaded from a mirror of Maven Central:
    // the canonical mirror limits the number of downloads per address.
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
