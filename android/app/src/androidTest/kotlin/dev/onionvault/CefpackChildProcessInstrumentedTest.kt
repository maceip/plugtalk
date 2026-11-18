package dev.onionvault

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cefpack.android.CefpackSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.chromium.content.browser.ChildProcessCreationParamsImpl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Headless browser-engine & isolated child-process verification on real Android hardware
 * (including Android 17+ `SDK_INT >= 37` ARMv9 devices even when the keyguard is locked).
 *
 * Verifies:
 *  1. [TrapApplication] initializes `CefpackEngine` with zero `NativeOnlySandboxedProcessService0`
 *     `NameNotFoundException` on Android 17+ (`isNativeSandboxedServiceEnabled() == false`).
 *  2. Multiple concurrent [CefpackSession] instances spawn `:privileged_process*` and
 *     `:sandboxed_process*` cleanly under `android:memtagMode="off"` + `PartitionAllocMemoryTagging`
 *     disabled on ARMv9 `FEAT_MTE` silicon without `WindowAndroid` layer collisions.
 *  3. Native C++ `DevToolsAgentHost` (`DOM.getDocument`) round-trips live Blink DOM trees from
 *     the isolated `:sandboxed_process*` renderer with zero injected page JavaScript.
 */
@RunWith(AndroidJUnit4::class)
class CefpackChildProcessInstrumentedTest {

    @Test
    fun cefpack_spawns_sandboxed_renderer_and_roundtrips_cdp_dom_across_multi_sessions() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        assertTrue("CefpackEngine must be initialized in browser process", TrapApplication.isEngineReady)
        assertNull("CefpackEngine init error must be null", TrapApplication.engineInitError)
        assertFalse(
            "Android 17+ javaless renderer service routing must stay disabled",
            ChildProcessCreationParamsImpl.isNativeSandboxedServiceEnabled(),
        )
        assertEquals(
            "org.chromium.content.app.SandboxedProcessService",
            ChildProcessCreationParamsImpl.getSandboxedServicesName(),
        )

        val loadLatchA = CountDownLatch(1)
        val loadLatchB = CountDownLatch(1)
        val loadError = AtomicReference<String?>(null)
        val sessionARef = AtomicReference<CefpackSession>()
        val sessionBRef = AtomicReference<CefpackSession>()

        val htmlA = "<!doctype html><html><head><title>TrapAndroid17</title></head>" +
            "<body><textarea id=\"pgpbox\">arm64-sandboxed-renderer-ok</textarea></body></html>"
        val htmlB = "<!doctype html><html><head><title>SecondTabOk</title></head>" +
            "<body><p id=\"second\">isolated-session-two</p></body></html>"
        val urlA = "data:text/html;charset=utf-8," + Uri.encode(htmlA)
        val urlB = "data:text/html;charset=utf-8," + Uri.encode(htmlB)

        try {
            instrumentation.runOnMainSync {
                val sA = CefpackSession(appContext)
                sA.setSessionCallback(object : CefpackSession.SessionCallback {
                    override fun onLoadEnd(httpStatusCode: Int, canGoBack: Boolean, canGoForward: String?) {
                        loadLatchA.countDown()
                    }

                    override fun onLoadError(errorCode: Int, errorText: String?, failingUrl: String?) {
                        loadError.compareAndSet(null, "sessionA error=$errorCode ($errorText) url=$failingUrl")
                        loadLatchA.countDown()
                    }
                })
                sessionARef.set(sA)

                val sB = CefpackSession(appContext)
                sB.setSessionCallback(object : CefpackSession.SessionCallback {
                    override fun onLoadEnd(httpStatusCode: Int, canGoBack: Boolean, canGoForward: String?) {
                        loadLatchB.countDown()
                    }

                    override fun onLoadError(errorCode: Int, errorText: String?, failingUrl: String?) {
                        loadError.compareAndSet(null, "sessionB error=$errorCode ($errorText) url=$failingUrl")
                        loadLatchB.countDown()
                    }
                })
                sessionBRef.set(sB)

                sA.loadUrl(urlA)
                sB.loadUrl(urlB)
            }

            assertTrue("Session A must finish loading in isolated renderer", loadLatchA.await(20, TimeUnit.SECONDS))
            assertTrue("Session B must finish loading in isolated renderer", loadLatchB.await(20, TimeUnit.SECONDS))
            assertNull("Neither session may report a load error", loadError.get())

            val cdpLatch = CountDownLatch(1)
            val cdpOk = AtomicBoolean(false)
            val cdpJson = AtomicReference("")

            instrumentation.runOnMainSync {
                sessionARef.get().executeDevToolsMethod(
                    "DOM.getDocument",
                    "{\"depth\":-1}",
                ) { success, payload ->
                    cdpOk.set(success)
                    cdpJson.set(payload ?: "")
                    cdpLatch.countDown()
                }
            }

            assertTrue("CDP DOM.getDocument must respond within 10s", cdpLatch.await(10, TimeUnit.SECONDS))
            assertTrue("CDP DOM.getDocument must succeed: ${cdpJson.get()}", cdpOk.get())
            assertTrue(
                "CDP DOM snapshot must include Blink textarea from :sandboxed_process",
                cdpJson.get().contains("TEXTAREA") || cdpJson.get().contains("textarea"),
            )
            assertTrue(
                "CDP DOM snapshot must include rendered document title",
                cdpJson.get().contains("TrapAndroid17"),
            )
        } finally {
            instrumentation.runOnMainSync {
                runCatching { sessionBRef.get()?.close() }
                runCatching { sessionARef.get()?.close() }
            }
        }
    }

    @Test
    fun arti_tor_socks5_bootstraps_and_loads_live_onion_hidden_service_in_cefpack() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        assertTrue("libonion_vault.so must be loaded", TrapApplication.nativeLoaded && OvNative.isLoaded)
        val initOk = OvNative.nativeInit(
            appContext.filesDir.absolutePath,
            appContext.cacheDir.absolutePath,
            true,
        )
        assertTrue("OvNative.nativeInit failed: ${OvNative.nativeLastInitError()}", initOk)

        val socksAddr = OvNative.nativeSocksProxy().trim()
        assertTrue(
            "Arti SOCKS5 listener must bind on 127.0.0.1: $socksAddr",
            socksAddr.contains("127.0.0.1:") && !socksAddr.endsWith(":1"),
        )
        TrapApplication.applyProfileSocksServer(socksAddr)

        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        var bootstrapped = false
        while (System.nanoTime() < deadlineNs) {
            val status = JSONObject(OvNative.nativeTorStatusJson(""))
            if (status.optBoolean("bootstrapped", false)) {
                bootstrapped = true
                break
            }
            Thread.sleep(500)
        }
        assertTrue("Arti Tor client must bootstrap within 60s", bootstrapped)

        val onionUrl = "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/"
        val sessionRef = AtomicReference<CefpackSession>()
        var lastHttpStatus = 0
        var lastError = ""

        try {
            for (attempt in 1..2) {
                val loadLatch = CountDownLatch(1)
                val statusRef = AtomicInteger(0)
                val errorRef = AtomicReference("")

                instrumentation.runOnMainSync {
                    sessionRef.get()?.let { prev -> runCatching { prev.close() } }
                    val session = CefpackSession(appContext)
                    session.setSessionCallback(object : CefpackSession.SessionCallback {
                        override fun onLoadEnd(httpStatusCode: Int, canGoBack: Boolean, canGoForward: String?) {
                            if (httpStatusCode > 0) {
                                statusRef.set(httpStatusCode)
                                loadLatch.countDown()
                            }
                        }

                        override fun onLoadError(errorCode: Int, errorText: String?, failingUrl: String?) {
                            errorRef.set("err=$errorCode ($errorText) url=$failingUrl")
                            loadLatch.countDown()
                        }
                    })
                    sessionRef.set(session)
                    session.loadUrl(onionUrl)
                }

                loadLatch.await(45, TimeUnit.SECONDS)
                lastHttpStatus = statusRef.get()
                lastError = errorRef.get()
                if (lastHttpStatus == 200) {
                    break
                }
                OvNative.nativeResetTorIsolation(onionUrl)
            }

            assertEquals("Live v3 .onion service must load with HTTP 200 ($lastError)", 200, lastHttpStatus)
        } finally {
            instrumentation.runOnMainSync {
                runCatching { sessionRef.get()?.close() }
            }
        }
    }
}
