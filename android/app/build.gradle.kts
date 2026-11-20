// Gradle build for the Trap Android application.
//
// Browser rendering is provided by Cefpack (`https://github.com/maceip/cefpack`,
// source-built Chromium 156 `//content/public/android` embedder `cefpack-android.aar`
// resolved directly from GitHub Releases with mandatory SHA-256 verification).
// Trap's Rust library (`libonion_vault.so`, built via `cargo-ndk`) handles Arti Tor
// SOCKS5 circuits, URL leak policy, Sequoia OpenPGP keyring/crypto, and zero-JS CDP
// DOM classification with zero browser `.so` linkage.

import java.io.FileInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val trapVersionFile = rootProject.file("../VERSION")
val trapDefaultVersionName = trapVersionFile
    .takeIf { it.isFile }
    ?.readText()
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "0.1.48"
val trapVersionCodeFloor = 48
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

// Pinned `maceip/cefpack` GitHub Release artifact coordinates & mandatory SHA-256 digest.
val cefpackPinnedTag = "v0.2.1"
val cefpackPinnedAsset = "cefpack-android.aar"
val cefpackPinnedSha256 = "b95f55648a8ff9229d53009c0cea1d1b0c28cc7db07ea959e225ce79da3e359c"

object CefpackAarResolver {
    private const val ASSET_NAME = "cefpack-android.aar"

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun assertZipHeader(file: File, label: String) {
        if (!file.isFile || file.length() < 4L) {
            throw GradleException("$label is missing or empty: ${file.absolutePath}")
        }
        val magic = ByteArray(4)
        FileInputStream(file).use { input ->
            if (input.read(magic) != 4) {
                throw GradleException("$label could not be read: ${file.absolutePath}")
            }
        }
        val isZip = magic[0] == 0x50.toByte() &&
            magic[1] == 0x4B.toByte() &&
            magic[2] == 0x03.toByte() &&
            magic[3] == 0x04.toByte()
        if (!isZip) {
            throw GradleException("$label is not a valid ZIP/AAR archive: ${file.absolutePath}")
        }
    }

    fun materialize(
        localOverridePath: String?,
        releaseTag: String,
        releaseUrl: String,
        expectedSha256Raw: String,
        userCacheRoot: File,
        destAar: File,
        metadataJsonOut: File,
    ) {
        val expectedSha256 = expectedSha256Raw.trim().lowercase()
        val sha256HexRegex = Regex("^[0-9a-f]{64}$")

        destAar.parentFile.mkdirs()
        metadataJsonOut.parentFile.mkdirs()

        val resolvedSha256: String
        val sourceUrlOrPath: String
        val isLocalOverride: Boolean

        if (!localOverridePath.isNullOrBlank()) {
            isLocalOverride = true
            val overrideFile = File(localOverridePath)
            if (!overrideFile.isFile) {
                throw GradleException(
                    "Local cefpack AAR override not found at '${overrideFile.absolutePath}' " +
                        "(set via -PcefpackAarPath or CEFPACK_AAR_PATH).",
                )
            }
            assertZipHeader(overrideFile, "Local cefpack AAR override")
            val actualSha = sha256Hex(overrideFile)
            if (expectedSha256.isNotEmpty() && sha256HexRegex.matches(expectedSha256) && actualSha != expectedSha256) {
                throw GradleException(
                    "SHA-256 mismatch for local cefpack AAR override '${overrideFile.absolutePath}': " +
                        "expected $expectedSha256, got $actualSha",
                )
            }
            if (overrideFile.canonicalPath != destAar.canonicalPath) {
                val tmp = File(destAar.parentFile, "${destAar.name}.tmp")
                Files.copy(overrideFile.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.move(tmp.toPath(), destAar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            resolvedSha256 = actualSha
            sourceUrlOrPath = overrideFile.canonicalPath
        } else {
            isLocalOverride = false
            if (!sha256HexRegex.matches(expectedSha256)) {
                throw GradleException(
                    "Cefpack AAR SHA-256 pin is not a valid 64-character hex digest ('$expectedSha256Raw'). " +
                        "Pin cefpackPinnedSha256 in android/app/build.gradle.kts from https://github.com/maceip/cefpack/releases " +
                        "or pass -PcefpackAarPath=/path/to/cefpack-android.aar for local builds.",
                )
            }

            if (destAar.isFile && sha256Hex(destAar) == expectedSha256) {
                assertZipHeader(destAar, "Cached cefpack AAR")
                resolvedSha256 = expectedSha256
            } else {
                val sharedCacheFile = File(userCacheRoot, "$expectedSha256/$ASSET_NAME")
                if (sharedCacheFile.isFile && sha256Hex(sharedCacheFile) == expectedSha256) {
                    assertZipHeader(sharedCacheFile, "Gradle cache cefpack AAR")
                    val tmp = File(destAar.parentFile, "${destAar.name}.tmp")
                    Files.copy(sharedCacheFile.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Files.move(tmp.toPath(), destAar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    resolvedSha256 = expectedSha256
                } else {
                    println("Downloading $releaseUrl -> ${destAar.absolutePath} (expecting SHA-256 $expectedSha256)...")
                    val partFile = File(destAar.parentFile, "${destAar.name}.part")
                    Files.deleteIfExists(partFile.toPath())

                    val client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(30))
                        .build()
                    val reqBuilder = HttpRequest.newBuilder(URI.create(releaseUrl))
                        .timeout(Duration.ofMinutes(10))
                        .header("Accept", "application/octet-stream")
                        .header("User-Agent", "trap-gradle-cefpack-resolver")
                    val ghToken = (System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN"))?.trim()
                    if (!ghToken.isNullOrEmpty() && releaseUrl.startsWith("https://github.com/")) {
                        reqBuilder.header("Authorization", "Bearer $ghToken")
                    }

                    val response = client.send(reqBuilder.GET().build(), HttpResponse.BodyHandlers.ofFile(partFile.toPath()))
                    if (response.statusCode() != 200) {
                        Files.deleteIfExists(partFile.toPath())
                        throw GradleException(
                            "Failed to download cefpack AAR from $releaseUrl (HTTP ${response.statusCode()}).",
                        )
                    }

                    val downloadedSha = sha256Hex(partFile)
                    if (downloadedSha != expectedSha256) {
                        Files.deleteIfExists(partFile.toPath())
                        throw GradleException(
                            "SHA-256 verification failed for $releaseUrl: expected $expectedSha256, got $downloadedSha",
                        )
                    }
                    assertZipHeader(partFile, "Downloaded cefpack AAR")

                    sharedCacheFile.parentFile.mkdirs()
                    val cacheTmp = File(sharedCacheFile.parentFile, "${sharedCacheFile.name}.tmp")
                    Files.copy(partFile.toPath(), cacheTmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Files.move(cacheTmp.toPath(), sharedCacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Files.move(partFile.toPath(), destAar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    resolvedSha256 = downloadedSha
                }
            }
            sourceUrlOrPath = releaseUrl
        }

        val escapedSource = sourceUrlOrPath.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedPath = destAar.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedTag = releaseTag.replace("\\", "\\\\").replace("\"", "\\\"")
        metadataJsonOut.writeText(
            """
            {
              "group": "dev.cefpack",
              "artifact": "cefpack-android",
              "tag": "$escapedTag",
              "version": "${escapedTag.removePrefix("v")}",
              "sha256": "$resolvedSha256",
              "sizeBytes": ${destAar.length()},
              "source": "$escapedSource",
              "aarPath": "$escapedPath",
              "localOverride": $isLocalOverride
            }
            """.trimIndent() + "\n",
        )
    }
}

@DisableCachingByDefault(because = "Verifies SHA-256 of local/cached or GitHub Release cefpack-android.aar")
abstract class ResolveCefpackAarTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val localOverridePath: Property<String>

    @get:Input
    @get:Optional
    abstract val localOverrideStamp: Property<String>

    @get:Input
    abstract val releaseTag: Property<String>

    @get:Input
    abstract val releaseUrl: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:Input
    abstract val userCacheDir: Property<String>

    @get:OutputFile
    abstract val outputAar: RegularFileProperty

    @get:OutputFile
    abstract val outputMetadataJson: RegularFileProperty

    @TaskAction
    fun resolve() {
        CefpackAarResolver.materialize(
            localOverridePath = localOverridePath.orNull,
            releaseTag = releaseTag.get(),
            releaseUrl = releaseUrl.get(),
            expectedSha256Raw = expectedSha256.get(),
            userCacheRoot = File(userCacheDir.get()),
            destAar = outputAar.get().asFile,
            metadataJsonOut = outputMetadataJson.get().asFile,
        )
    }
}

val cefpackAarOverrideRaw = providers.gradleProperty("cefpackAarPath").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: providers.environmentVariable("CEFPACK_AAR_PATH").orNull?.trim()?.takeIf { it.isNotEmpty() }
val cefpackResolvedLocalOverride = cefpackAarOverrideRaw?.let { raw ->
    val candidate = File(raw)
    if (candidate.isAbsolute) candidate.absolutePath else rootProject.file("..").resolve(raw).absolutePath
}
val cefpackResolvedTag = providers.gradleProperty("cefpackReleaseTag").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: providers.environmentVariable("CEFPACK_RELEASE_TAG").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: cefpackPinnedTag
val cefpackResolvedUrl = providers.gradleProperty("cefpackAarUrl").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: providers.environmentVariable("CEFPACK_AAR_URL").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: "https://github.com/maceip/cefpack/releases/download/$cefpackResolvedTag/$cefpackPinnedAsset"
val cefpackResolvedSha256 = providers.gradleProperty("cefpackAarSha256").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: providers.environmentVariable("CEFPACK_AAR_SHA256").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: if (cefpackResolvedLocalOverride != null) "" else cefpackPinnedSha256

val cefpackCacheSubdir = if (cefpackResolvedLocalOverride != null) "local" else cefpackResolvedTag
val cefpackCachedAarFile = layout.buildDirectory.file("cefpack/$cefpackCacheSubdir/$cefpackPinnedAsset")
val cefpackMetadataFile = layout.buildDirectory.file("cefpack/resolved-aar.json")
val cefpackSharedUserCacheRoot = File(gradle.gradleUserHomeDir, "caches/cefpack")

// Materialize eagerly during IDE Gradle Sync so Android Studio indexes `dev.cefpack.android.*` immediately.
if (System.getProperty("idea.sync.active") == "true") {
    CefpackAarResolver.materialize(
        localOverridePath = cefpackResolvedLocalOverride,
        releaseTag = cefpackResolvedTag,
        releaseUrl = cefpackResolvedUrl,
        expectedSha256Raw = cefpackResolvedSha256,
        userCacheRoot = cefpackSharedUserCacheRoot,
        destAar = cefpackCachedAarFile.get().asFile,
        metadataJsonOut = cefpackMetadataFile.get().asFile,
    )
}

val resolveCefpackAar = tasks.register<ResolveCefpackAarTask>("resolveCefpackAar") {
    group = "build setup"
    description = "Downloads or stages cefpack-android.aar from maceip/cefpack with mandatory SHA-256 verification."
    if (cefpackResolvedLocalOverride != null) {
        localOverridePath.set(cefpackResolvedLocalOverride)
        val f = File(cefpackResolvedLocalOverride)
        localOverrideStamp.set(if (f.isFile) "${f.length()}:${f.lastModified()}" else "missing")
    }
    releaseTag.set(cefpackResolvedTag)
    releaseUrl.set(cefpackResolvedUrl)
    expectedSha256.set(cefpackResolvedSha256)
    userCacheDir.set(cefpackSharedUserCacheRoot.absolutePath)
    outputAar.set(cefpackCachedAarFile)
    outputMetadataJson.set(cefpackMetadataFile)
}

android {
    namespace = "dev.onionvault"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.onionvault"
        minSdk = 29
        targetSdk = 37
        versionCode = trapResolvedVersionCode
        versionName = trapResolvedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64-v8a for physical Android devices and Apple Silicon AVDs;
            // x86_64 for Linux CI KVM emulators. Allow -PtrapAbiFilters=arm64-v8a for local single-ABI smoke tests.
            val configuredAbiFilters = providers.gradleProperty("trapAbiFilters")
                .orNull
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf("arm64-v8a", "x86_64")
            abiFilters += configuredAbiFilters
        }
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        getByName("debug") {
            // Deterministic repository debug keystore shared by local and CI builds so
            // APK upgrades across GitHub releases never fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    dependencies {
        // Cefpack (`https://github.com/maceip/cefpack` — source-built Chromium 156
        // `//content/public/android` embedder AAR, SHA-256 verified by `resolveCefpackAar`).
        implementation(files(resolveCefpackAar.flatMap { it.outputAar }))

        implementation("androidx.appcompat:appcompat:1.7.0")
        implementation("androidx.mediarouter:mediarouter:1.7.0")
        implementation("androidx.browser:browser:1.8.0")
        implementation("androidx.biometric:biometric:1.1.0")
        implementation("androidx.core:core-ktx:1.19.1")
        implementation("androidx.core:core-animation:1.0.0")
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

        // On-device / emulator instrumentation tests (espresso-core 3.7.0+ supports Android 16+ InputManagerGlobal)
        androidTestImplementation("androidx.compose.ui:ui-test-junit4")
        androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
        androidTestImplementation("androidx.test.ext:junit:1.3.0")
        androidTestImplementation("androidx.test:runner:1.7.0")
        androidTestImplementation("androidx.test:rules:1.6.1")
        androidTestImplementation("androidx.test:core:1.7.0")
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
    dependsOn(resolveCefpackAar)
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
