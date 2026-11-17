package dev.onionvault

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.cefrium.Cefrium
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumProfile
import org.chromium.base.CommandLine

/**
 * Main application entry point for Trap.
 *
 * Strictly enforces process isolation: Chromium spawns isolated `:sandboxed_process*`
 * and `:privileged_process*` child services from the same APK. Only the primary
 * browser process installs [CrashReporter], loads `libonion_vault.so`, configures
 * Chromium's zero-clearnet command-line switches, and initializes [Cefrium].
 */
class TrapApplication : Application() {

    companion object {
        private const val TAG = "Trap"

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
         * Chromium features disabled at startup to prevent unsolicited Google background
         * endpoints (`content-autofill.googleapis.com`, optimization hints, translate, cast)
         * and ARMv9 MTE `SetHeapTaggingLevel` traps.
         */
        val HARDENED_DISABLED_FEATURES: List<String> = listOf(
            PARTITION_ALLOC_MEMORY_TAGGING_FEATURE,
            "AutofillServerCommunication",
            "OptimizationHints",
            "Translate",
            "MediaRouter",
        )

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
        private var currentProfile: CefriumProfile? = null

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
        fun incognitoProfile(): CefriumProfile {
            currentProfile?.let { return it }
            val profile = CefriumProfile.incognito()
            applyHardenedProfilePolicy(profile, currentSocksServer)
            currentProfile = profile
            return profile
        }

        @Synchronized
        fun resetIncognitoProfile(): CefriumProfile {
            val fresh = CefriumProfile.incognito()
            applyHardenedProfilePolicy(fresh, currentSocksServer)
            currentProfile = fresh
            return fresh
        }

        @Synchronized
        fun applyProfileSocksServer(socksAddr: String) {
            val normalized = socksAddr.trim()
            val server = if (normalized.isEmpty()) {
                FAIL_CLOSED_SOCKS_SERVER
            } else if (normalized.startsWith("socks5://")) {
                normalized
            } else {
                "socks5://$normalized"
            }
            currentSocksServer = server
            currentProfile?.let { profile ->
                applyHardenedProfilePolicy(profile, server)
            }
        }

        private fun applyHardenedProfilePolicy(profile: CefriumProfile, socksServer: String) {
            runCatching {
                profile.setWebRtcIpHandlingPolicy(CefriumProfile.WEBRTC_DISABLE_NON_PROXIED_UDP)
            }.onFailure {
                Log.w(TAG, "Could not set WebRTC IP policy on CefriumProfile", it)
            }
            runCatching {
                profile.setProxy(CefriumBrowser.PROXY_FIXED, socksServer)
            }.onFailure {
                Log.e(TAG, "Could not apply SOCKS5 proxy ($socksServer) on CefriumProfile", it)
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
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (!isBrowserProcess(base)) {
            return
        }
        CrashReporter.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        if (!isBrowserProcess(this)) {
            return
        }
        if (isJvmUnitTestRuntime()) {
            // Robolectric JVM unit tests inflate Compose previews without Android
            // native `.so` binaries; skip JNI/Cefrium native boot cleanly.
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
            initializeHardenedCefrium()
        } catch (error: Throwable) {
            isEngineReady = false
            engineInitError = "${error.javaClass.simpleName}: ${error.message ?: "Cefrium failed to initialize"}"
            Log.e(TAG, "Cefrium initialization failed", error)
            CrashReporter.record(this, error)
        }

        dev.onionvault.platform.DiagnosticProfilingController.install(this)
    }

    private fun initializeHardenedCefrium() {
        // Isolate GPU execution inside `:privileged_process0` instead of `--in-process-gpu`.
        Cefrium.setGpuProcessEnabled(true)

        // Disable all background clearnet connections before starting Chromium.
        Cefrium.setComponentUpdatesEnabled(false)
        Cefrium.setNetworkTimeQueriesEnabled(false)
        Cefrium.setDrmPreprovisioningAtStartupEnabled(false)

        if (!CommandLine.isInitialized()) {
            CommandLine.init(null)
        }
        CommandLine.getInstance().apply {
            // Block OS DNS resolution for every hostname except 127.0.0.1 (Arti's
            // local SOCKS5 listener); all `.onion` and remote names traverse SOCKS5h.
            appendSwitchWithValue("host-resolver-rules", "MAP * ~NOTFOUND , EXCLUDE 127.0.0.1")
            appendSwitch("dns-prefetch-disable")
            appendSwitch("no-pings")
            appendSwitch("disable-background-networking")
            // Pre-populate `disable-features` before `Cefrium.initialize(this)` appends
            // its own disabled features (`Cefrium.disableFeature` is private static).
            HARDENED_DISABLED_FEATURES.forEach { feature ->
                appendDisabledFeature(this, feature)
            }
        }

        val initialized = Cefrium.initialize(this)
        if (!initialized) {
            throw IllegalStateException("Cefrium.initialize(Context) returned false in browser process")
        }

        // Disable system DNS-over-HTTPS fallback so Chromium never bypasses SOCKS5.
        Cefrium.setSecureDns(Cefrium.SECURE_DNS_OFF, null)

        // Prime the ephemeral in-memory profile with fail-closed loopback proxy until Arti binds.
        incognitoProfile()

        isEngineReady = true
        engineInitError = null
        Log.i(TAG, "the engine is ready")
    }
}
