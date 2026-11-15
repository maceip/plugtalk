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
    }

    private fun initializeHardenedCefrium() {
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
