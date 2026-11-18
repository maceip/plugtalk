package dev.onionvault

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import dev.cefpack.android.CefpackEngine
import org.chromium.base.CommandLine
import org.chromium.content_public.browser.ChildProcessCreationParams

/**
 * Main application entry point for Trap.
 *
 * Strictly enforces process isolation: Chromium spawns isolated `:sandboxed_process*`
 * and `:privileged_process*` child services from the same APK. All processes call
 * [CefpackEngine.initProcess] to bind their `LibraryProcessType`, while only the primary
 * browser process installs [CrashReporter], loads `libonion_vault.so`, configures
 * Chromium's zero-clearnet command-line switches, and initializes [CefpackEngine].
 */
class TrapApplication : Application() {

    companion object {
        private const val TAG = "Trap"
        private const val BROWSER_LOG_TAG = "TrapBrowser"

        /** Fail-closed loopback blackhole used until Arti binds its SOCKS5 listener. */
        const val FAIL_CLOSED_SOCKS_SERVER: String = "socks5://127.0.0.1:1"

        /**
         * Chromium 156 enables `PartitionAllocMemoryTagging` by default on ARMv9 (`FEAT_MTE`).
         * Combined with `android:memtagMode="off"`, disabling this feature prevents
         * `PartitionAllocSupport::ReconfigureAfterFeatureListInit` from calling
         * `SetHeapTaggingLevel` (`SIGTRAP` under locked MTE) or `DumpWithoutCrashing`.
         */
        const val PARTITION_ALLOC_MEMORY_TAGGING_FEATURE: String = "PartitionAllocMemoryTagging"

        /**
         * Chromium 156 enables `JavalessRendererExperimentOn` by default on Android 17+
         * (`SDK_INT >= 37`), switching `ChildProcessCreationParamsImpl.getSandboxedServicesName()`
         * to `NativeOnlySandboxedProcessService0`, whereas `cefpack-android.aar` declares
         * `SandboxedProcessService0..39`. Pinning `forceNativeSandboxedService = false` and
         * `--javaless-renderers=disabled` keeps child renderer allocation on
         * `SandboxedProcessService0..39` across Android 10 through Android 17+.
         */
        const val JAVALESS_RENDERERS_FEATURE: String = "JavalessRendererExperimentOn"
        private const val LIBRARY_PROCESS_TYPE_CHILD: Int = 2

        /**
         * Chromium features disabled at startup to prevent unsolicited Google background
         * endpoints (`content-autofill.googleapis.com`, optimization hints, translate, cast),
         * WebRTC mDNS local-IP leakage, `.onion` `HttpsUpgrades` / `HttpsFirstBalancedMode`
         * speculative HTTPS rewrites, ARMv9 MTE `SetHeapTaggingLevel` traps, and Android 17+
         * unbundled `NativeOnlySandboxedProcessService` renderer routing.
         */
        val HARDENED_DISABLED_FEATURES: List<String> = listOf(
            PARTITION_ALLOC_MEMORY_TAGGING_FEATURE,
            JAVALESS_RENDERERS_FEATURE,
            "AutofillServerCommunication",
            "OptimizationHints",
            "Translate",
            "MediaRouter",
            "WebRtcHideLocalIpsWithMdns",
            "HttpsUpgrades",
            "HttpsFirstBalancedMode",
            "HttpsFirstBalancedModeAutoEnable",
        )

        @Volatile
        private var childProcessParamsConfigured: Boolean = false

        @Volatile
        var isEngineReady: Boolean = false
            private set

        @Volatile
        var engineInitError: String? = null
            private set

        @Volatile
        var nativeLoaded: Boolean = false
            private set

        @Volatile
        var nativeLoadError: String? = null
            private set

        @Volatile
        private var currentSocksServer: String = FAIL_CLOSED_SOCKS_SERVER

        fun isBrowserProcess(context: Context? = null): Boolean {
            val processName = runCatching { getProcessName() }.getOrNull()
                ?: context?.packageName
                ?: return true
            return !processName.contains(":")
        }

        private fun isJvmUnitTestRuntime(): Boolean =
            Build.FINGERPRINT.equals("robolectric", ignoreCase = true)

        @Synchronized
        fun currentSocksProxyServer(): String = currentSocksServer

        @Synchronized
        fun resetEphemeralProfile() {
            runCatching {
                CefpackEngine.resetEphemeralProfile()
                CefpackEngine.setProxyServer(currentSocksServer)
            }.onFailure { err ->
                runCatching { Log.e(TAG, "Could not rotate ephemeral Cefpack profile", err) }
            }
        }

        @Synchronized
        fun applyProfileSocksServer(socksAddr: String) {
            val normalized = socksAddr.trim()
            val server = when {
                normalized.isEmpty() -> FAIL_CLOSED_SOCKS_SERVER
                normalized.startsWith("socks5://", ignoreCase = true) ->
                    "socks5://" + normalized.substring(9)
                normalized.startsWith("socks5h://", ignoreCase = true) ->
                    "socks5://" + normalized.substring(10)
                else -> "socks5://$normalized"
            }
            currentSocksServer = server
            runCatching {
                CefpackEngine.setProxyServer(server)
            }.onSuccess {
                runCatching { Log.i(BROWSER_LOG_TAG, "Applied SOCKS5 proxy to CefpackEngine: $server") }
            }.onFailure { err ->
                runCatching { Log.e(TAG, "Could not apply SOCKS5 proxy ($server) on CefpackEngine", err) }
            }
        }

        internal fun mergeDisabledFeature(existing: String?, feature: String): String {
            val incoming = feature
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (incoming.isEmpty()) {
                return existing.orEmpty().trim()
            }
            val items = existing
                .orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()
            for (item in incoming) {
                if (item !in items) {
                    items.add(item)
                }
            }
            return items.joinToString(",")
        }

        internal fun appendDisabledFeature(commandLine: CommandLine, feature: String) {
            val existing = commandLine.getSwitchValue("disable-features", "")
            val merged = mergeDisabledFeature(existing, feature)
            if (merged.isEmpty()) {
                return
            }
            if (commandLine.hasSwitch("disable-features")) {
                commandLine.removeSwitch("disable-features")
            }
            commandLine.appendSwitchWithValue("disable-features", merged)
        }

        @Synchronized
        internal fun configureChildProcessCreationParams(packageName: String) {
            if (childProcessParamsConfigured) {
                return
            }
            ChildProcessCreationParams.set(
                packageName,
                packageName,
                false,
                LIBRARY_PROCESS_TYPE_CHILD,
                false,
                false,
                false,
            )
            childProcessParamsConfigured = true
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (!isJvmUnitTestRuntime()) {
            runCatching { CefpackEngine.initProcess(this) }.onFailure { error ->
                Log.e(TAG, "CefpackEngine.initProcess failed in attachBaseContext", error)
            }
        }
        if (!isBrowserProcess(base)) {
            return
        }
        CrashReporter.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        if (isJvmUnitTestRuntime()) {
            // Robolectric JVM unit tests inflate Compose previews without Android
            // native `.so` binaries; skip JNI/Cefpack native boot cleanly.
            return
        }

        runCatching { CefpackEngine.initProcess(this) }.onFailure { error ->
            Log.e(TAG, "CefpackEngine.initProcess failed in onCreate", error)
        }

        if (!isBrowserProcess(this)) {
            return
        }

        try {
            OvNative.load()
            nativeLoaded = true
            nativeLoadError = null
        } catch (error: Throwable) {
            nativeLoaded = false
            nativeLoadError = "${error.javaClass.simpleName}: ${error.message ?: "failed to load libonion_vault.so"}"
            Log.e(TAG, "Failed to load libonion_vault.so", error)
            CrashReporter.record(this, error)
        }

        try {
            initializeHardenedCefpack()
        } catch (error: Throwable) {
            isEngineReady = false
            engineInitError = "${error.javaClass.simpleName}: ${error.message ?: "Cefpack failed to initialize"}"
            Log.e(TAG, "Cefpack initialization failed", error)
            CrashReporter.record(this, error)
        }

        dev.onionvault.platform.DiagnosticProfilingController.install(this)
    }

    private fun initializeHardenedCefpack() {
        // Start fail-closed on loopback blackhole proxy (`socks5://127.0.0.1:1`) until Arti binds.
        CefpackEngine.setProxyServer(currentSocksServer)
        configureChildProcessCreationParams(packageName)

        if (!CommandLine.isInitialized()) {
            CommandLine.init(arrayOf("cefpack"))
        }
        CommandLine.getInstance().apply {
            // Block OS DNS resolution for every hostname except 127.0.0.1 (Arti's
            // local SOCKS5 listener); all `.onion` and remote names traverse SOCKS5.
            appendSwitchWithValue("host-resolver-rules", "MAP * ~NOTFOUND , EXCLUDE 127.0.0.1")
            appendSwitch("dns-prefetch-disable")
            appendSwitch("no-pings")
            appendSwitch("disable-background-networking")
            appendSwitchWithValue("force-webrtc-ip-handling-policy", "disable_non_proxied_udp")
            appendSwitchWithValue("js-flags", "--jitless,--no-expose-wasm")
            if (hasSwitch("javaless-renderers")) {
                removeSwitch("javaless-renderers")
            }
            appendSwitchWithValue("javaless-renderers", "disabled")
            HARDENED_DISABLED_FEATURES.forEach { feature ->
                appendDisabledFeature(this, feature)
            }
        }

        val initialized = CefpackEngine.initialize(this)
        if (!initialized) {
            throw IllegalStateException("CefpackEngine.initialize(Context) returned false in browser process")
        }

        // Ensure active and future NetworkContexts remain pinned to currentSocksServer.
        CefpackEngine.setProxyServer(currentSocksServer)

        isEngineReady = true
        engineInitError = null
        Log.i(TAG, "the engine is ready")
        Log.i(BROWSER_LOG_TAG, "CefpackEngine initialized (proxy=$currentSocksServer)")
    }
}
