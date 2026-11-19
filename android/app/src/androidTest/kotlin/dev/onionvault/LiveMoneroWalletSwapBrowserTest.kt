package dev.onionvault

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cefpack.android.CefpackSession
import dev.onionvault.ui.TrapPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live Tor device E2E test for Trap's Monero Light Wallet & Browser-Wired BTC -> XMR Swap.
 *
 * Run selectively via:
 * `-Pandroid.testInstrumentationRunnerArguments.class=dev.onionvault.LiveMoneroWalletSwapBrowserTest`
 *
 * Verifies end-to-end on real Android hardware / emulator:
 *   1. Cold-boots [MainActivity] and waits for embedded Arti Tor SOCKS5h bootstrap (`100%`).
 *   2. Creates a real 25-word Monero wallet wrapped in Android Keystore via `MoneroWalletHardwarePod`.
 *   3. Syncs live `.onion` `monerod` block height over Arti Tor (`daemonHeight > 3,800,000`).
 *   4. Fetches live zero-JS BTC -> XMR swap quotes from OrangeFren `.onion` over Arti Tor
 *      inside `BtcToXmrSwapHardwarePod` and taps `"Swap in Browser"` to mint a dedicated `8...`
 *      swap subaddress.
 *   5. Loads a zero-JS Monero swap form in [CefpackSession], taps `"Insert XMR Subaddress"`
 *      (`xmr_insert_subaddress_pill`), and verifies Blink `#inner-editor` contains the minted
 *      `8...` subaddress with zero JavaScript enabled.
 *   6. Loads a zero-JS BTC deposit order DOM and verifies automatic `btcDepositAddress` binding
 *      plus `ActiveSwapBrowserHud` display.
 */
@RunWith(AndroidJUnit4::class)
class LiveMoneroWalletSwapBrowserTest {

    companion object {
        private const val TAG = "TrapXmrLiveTest"
        private const val WIZARD_ONION_DISPLAY_URL =
            "http://wizardswgtu2ovor7r2esg3cxdpt7tv4nrugi32lldv53zmtonbz6sid.onion/?from=BTC&to=XMR&amount=0.01"
        private const val WIZARD_ORDER_DISPLAY_URL =
            "http://wizardswgtu2ovor7r2esg3cxdpt7tv4nrugi32lldv53zmtonbz6sid.onion/id=3X314OQK"
        private const val SAMPLE_VALID_BTC_DEPOSIT =
            "bc1qwxcndrucjwn7f4u2vrq2lx0hv543sjz57e77q2"

        private const val SHOT_HOME = "pixel-xmr-metatools-home.png"
        private const val SHOT_WALLET = "pixel-xmr-wallet-pod.png"
        private const val SHOT_QUOTES = "pixel-xmr-btc-swap-quotes.png"
        private const val SHOT_SWAP = "pixel-xmr-browser-wired-swap.png"
    }

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun cold_boot_tor_monero_wallet_sync_live_onion_quotes_and_zero_js_cdp_subaddress_injection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = ApplicationProvider.getApplicationContext<Context>()

        CrashReporter.clear(appContext)
        wakeDisplay(instrumentation)

        val prefs = TrapPreferences(appContext)
        val previousBiometric = prefs.requireBiometric
        prefs.requireBiometric = false
        if (OvNative.isLoaded) {
            OvNative.nativeSetRequireBiometric(false)
            runCatching { OvNative.nativeXmrDeleteWallet() }
        }

        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(MainActivity.EXTRA_SHOW_OVER_KEYGUARD_FOR_TEST, true)
        }

        try {
            ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
                val activityRef = AtomicReference<MainActivity>()
                scenario.onActivity { act ->
                    act.setShowWhenLocked(true)
                    act.setTurnScreenOn(true)
                    act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activityRef.set(act)
                }
                val activity = activityRef.get()
                assertNotNull("MainActivity must launch", activity)
                assertTrue("libonion_vault.so must be loaded", OvNative.isLoaded)
                assertTrue("Cefpack engine must be ready", TrapApplication.isEngineReady)

                OvNative.nativeSetRequireBiometric(false)
                activity.resetXmrWalletForTest()

                // 1. Wait for MetaToolsBrowserShell and real Arti Tor SOCKS5h bootstrap.
                composeRule.waitUntil(timeoutMillis = 15_000L) {
                    composeRule.onAllNodesWithTag("browser_screen").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("metatools_browser_shell").assertIsDisplayed()

                val torDeadline = SystemClock.uptimeMillis() + 90_000L
                var torSnap = OvNative.TorStatusSnapshot()
                var socksPort = 0
                while (SystemClock.uptimeMillis() < torDeadline) {
                    torSnap = OvNative.parseTorStatus(OvNative.nativeTorStatusJson("about:blank"))
                    val socks = torSnap.socksAddr.ifBlank { OvNative.nativeSocksProxy() }.trim()
                    socksPort = socks.substringAfterLast(':', "").toIntOrNull() ?: 0
                    if (torSnap.bootstrapped && socksPort > 1 && activity.currentUiStateForTest().tor.ready) break
                    SystemClock.sleep(400L)
                }
                assertTrue(
                    "Arti Tor failed to bootstrap within 90s (${torSnap.bootstrapPercent}% ${torSnap.bootstrapSummary})",
                    torSnap.bootstrapped && socksPort > 1,
                )
                Log.i(TAG, "Arti Tor bootstrapped on SOCKS5 port $socksPort")

                composeRule.waitUntil(timeoutMillis = 15_000L) {
                    activity.activeCefpackSessionForTest() != null
                }
                val browser = activity.activeCefpackSessionForTest()!!
                enforceZeroJsCdp(activity, browser)

                activity.resetXmrWalletForTest()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    !activity.currentUiStateForTest().xmr.status.hasWallet
                }

                composeRule.waitForIdle()
                SystemClock.sleep(300L)
                saveLiveScreenshot(instrumentation, activity, SHOT_HOME)

                // 2. Open PhotoSoapSideRemote -> RemoteMode.MoneroWallet -> MoneroWalletHardwarePod -> Create real 25-word wallet.
                composeRule.onNodeWithTag("side_remote_collapsed_face").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("remote_monero_wallet").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("remote_monero_wallet").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("remote_open_full_xmr_wallet").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("remote_open_full_xmr_wallet").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("xmr_wallet_sheet").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("xmr_create_wallet_button").performClick()

                composeRule.waitUntil(timeoutMillis = 60_000L) {
                    !activity.currentUiStateForTest().xmr.creatingOrRestoring &&
                        activity.currentUiStateForTest().xmr.status.hasWallet
                }
                val createdStatus = OvNative.parseXmrStatus(OvNative.nativeXmrStatusJson())
                assertTrue(createdStatus.primaryAddress.startsWith("4"))
                assertTrue(createdStatus.activeSubaddress.startsWith("8"))

                val exportedSeed = OvNative.parseXmrSeedResult(OvNative.nativeXmrExportSeedJson())
                assertTrue(exportedSeed.ok)
                assertEquals(
                    "Monero Electrum seed must have exact 25 words",
                    25,
                    exportedSeed.mnemonic.trim().split(Regex("\\s+")).size,
                )

                // Acknowledge 25-word seed backup banner so Receive controls & synced height fit cleanly.
                if (composeRule.onAllNodesWithTag("xmr_dismiss_new_seed_button")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                ) {
                    composeRule.onNodeWithTag("xmr_dismiss_new_seed_button").performClick()
                }

                // 3. Sync live .onion monerod block height over Arti Tor (> 3,800,000).
                composeRule.onNodeWithTag("xmr_sync_button").performClick()
                composeRule.waitUntil(timeoutMillis = 90_000L) {
                    val xmrState = activity.currentUiStateForTest().xmr
                    !xmrState.syncing &&
                        xmrState.status.daemonHeight > 3_800_000L &&
                        xmrState.status.syncedHeight >= xmrState.status.restoreHeight
                }
                val syncedStatus = OvNative.parseXmrStatus(OvNative.nativeXmrStatusJson())
                Log.i(
                    TAG,
                    "Synced live .onion monerod height=${syncedStatus.syncedHeight}/${syncedStatus.daemonHeight}",
                )
                assertTrue(syncedStatus.daemonHeight > 3_800_000L)

                composeRule.waitForIdle()
                SystemClock.sleep(250L)
                saveLiveScreenshot(instrumentation, activity, SHOT_WALLET)

                // 4. Switch to BtcToXmrSwapHardwarePod and fetch real zero-JS quotes from OrangeFren .onion over Tor.
                composeRule.onNodeWithTag("xmr_open_swap_button").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("btc_xmr_swap_sheet").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("swap_fetch_quotes_button").performClick()

                val quoteDeadline = SystemClock.uptimeMillis() + 95_000L
                var retriedOnce = false
                while (SystemClock.uptimeMillis() < quoteDeadline) {
                    val xmrUi = activity.currentUiStateForTest().xmr
                    if (!xmrUi.quotesLoading && xmrUi.quotes.isNotEmpty()) {
                        break
                    }
                    if (!xmrUi.quotesLoading && xmrUi.quotesHasFetched && xmrUi.quotes.isEmpty() && !retriedOnce) {
                        retriedOnce = true
                        runCatching {
                            composeRule.onNodeWithTag("swap_fetch_quotes_button").performClick()
                        }
                    }
                    SystemClock.sleep(400L)
                }
                val liveQuotes = activity.currentUiStateForTest().xmr.quotes
                assertFalse(
                    "Expected live zero-JS BTC->XMR quotes from OrangeFren .onion (err=${activity.currentUiStateForTest().xmr.error})",
                    liveQuotes.isEmpty(),
                )
                val chosenQuote = liveQuotes.firstOrNull {
                    it.isOnionUrl && it.provider.contains("Wizard", ignoreCase = true)
                } ?: liveQuotes.firstOrNull { it.isOnionUrl } ?: liveQuotes.first()
                Log.i(
                    TAG,
                    "Fetched ${liveQuotes.size} live Tor quotes; chosen=${chosenQuote.provider} -> ${chosenQuote.estimatedXmr} XMR (${chosenQuote.launchUrl})",
                )

                composeRule.waitForIdle()
                SystemClock.sleep(250L)
                saveLiveScreenshot(instrumentation, activity, SHOT_QUOTES)

                // Tap "Swap in Browser" on the live quote card to mint dedicated 8... subaddress & bind tab!
                val launchTag = "swap_launch_${chosenQuote.id}"
                composeRule.onNodeWithTag(launchTag).performScrollTo().performClick()

                composeRule.waitUntil(timeoutMillis = 15_000L) {
                    !activity.currentUiStateForTest().xmr.swapSheetOpen &&
                        activity.currentUiStateForTest().xmr.status.swaps.isNotEmpty()
                }
                val dedicatedSwap = activity.currentUiStateForTest().xmr.status.swaps.first()
                assertTrue(dedicatedSwap.subaddress.startsWith("8"))
                assertEquals(95, dedicatedSwap.subaddress.length)

                // Stop any in-flight third-party navigation before loading deterministic rate-locked BTC->XMR swap form DOM
                activity.runOnUiThread {
                    activity.activeCefpackSessionForTest()?.stopLoading()
                }
                SystemClock.sleep(200L)

                // 5. Load zero-JS swap form into CefpackSession, tap "Insert XMR Subaddress",
                //    and assert Blink #inner-editor contains the minted 8... subaddress!
                val swapFormHtml = """
                    <!doctype html>
                    <html>
                    <head><meta charset="utf-8"><title>Zero-JS BTC to XMR Swap</title></head>
                    <body style="background:#0d0f12;color:#e8edf2;font-family:monospace;padding:56px 20px 24px 20px;margin:0">
                      <h2 style="font-size:18px;color:#39ff14;margin:0 0 8px 0">WizardSwap · BTC → XMR (Zero-JS)</h2>
                      <p style="font-size:13px;color:#9aa5b1;margin:0 0 18px 0">Send 0.01 BTC · Receive ≈ ${chosenQuote.estimatedXmr} XMR</p>
                      <form id="wizardswap" method="POST" action="/">
                        <h6 style="font-size:12px;color:#cbd5e1;margin:0 0 6px 0">Recipient Monero Subaddress</h6>
                        <input type="text" id="address" name="address" placeholder="Enter the recipient's Monero address" style="width:94%;height:42px;background:#161b22;color:#39ff14;border:1px solid #343942;border-radius:8px;padding:0 10px;font-family:monospace;font-size:13px" />
                        <h6 style="font-size:12px;color:#cbd5e1;margin:52px 0 6px 0">Bitcoin Refund Address (Optional)</h6>
                        <input type="text" id="refund_address" name="refund_address" placeholder="Enter a Bitcoin refund address" style="width:94%;height:42px;background:#161b22;color:#e8edf2;border:1px solid #343942;border-radius:8px;padding:0 10px;font-family:monospace;font-size:13px" />
                      </form>
                    </body>
                    </html>
                """.trimIndent()
                loadDocumentSync(activity, swapFormHtml, WIZARD_ONION_DISPLAY_URL)

                composeRule.waitUntil(timeoutMillis = 15_000L) {
                    composeRule.onAllNodesWithTag("xmr_insert_subaddress_pill")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithTag("xmr_insert_subaddress_pill").assertIsDisplayed().performClick()

                val injectedValue = awaitInputInnerEditorEquals(
                    activity = activity,
                    browser = browser,
                    elementId = "address",
                    expected = dedicatedSwap.subaddress,
                    timeoutMs = 15_000L,
                )
                assertEquals(
                    "Blink #inner-editor must contain the minted 8... swap subaddress",
                    dedicatedSwap.subaddress,
                    injectedValue,
                )

                composeRule.waitForIdle()
                SystemClock.sleep(350L)
                saveLiveScreenshot(instrumentation, activity, SHOT_SWAP)

                // 6. Load zero-JS BTC deposit order DOM and verify automatic deposit address attachment + HUD!
                val orderPageHtml = """
                    <!doctype html>
                    <html>
                    <head><meta charset="utf-8"><title>Order Waiting for BTC Deposit</title></head>
                    <body style="background:#0d0f12;color:#e8edf2;font-family:monospace;padding:56px 20px 24px 20px">
                      <h2>Waiting for deposit</h2>
                      <p>Send <span id="amount_a">0.01</span> BTC to:</p>
                      <input type="text" id="deposit_address" name="deposit_address" readonly="readonly" value="$SAMPLE_VALID_BTC_DEPOSIT" style="width:94%;height:42px;background:#161b22;color:#ffb000;border:1px solid #343942;border-radius:8px;padding:0 10px" />
                    </body>
                    </html>
                """.trimIndent()
                loadDocumentSync(activity, orderPageHtml, WIZARD_ORDER_DISPLAY_URL)

                composeRule.waitUntil(timeoutMillis = 15_000L) {
                    composeRule.onAllNodesWithTag("btc_copy_deposit_pill")
                        .fetchSemanticsNodes()
                        .isNotEmpty() &&
                        OvNative.parseXmrStatus(OvNative.nativeXmrStatusJson()).swaps.any {
                            it.swapId == dedicatedSwap.swapId &&
                                it.btcDepositAddress == SAMPLE_VALID_BTC_DEPOSIT
                        }
                }
                composeRule.onNodeWithTag("active_swap_hud").assertIsDisplayed()
                Log.i(TAG, "LIVE_MONERO_SWAP_E2E_PASS subaddress=${dedicatedSwap.subaddress.take(12)}…")
            }
        } finally {
            prefs.requireBiometric = previousBiometric
            if (OvNative.isLoaded) {
                runCatching { OvNative.nativeXmrDeleteWallet() }
                runCatching { OvNative.nativeSetRequireBiometric(previousBiometric) }
            }
        }
    }

    private fun saveLiveScreenshot(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
        filename: String,
    ) {
        val hasFocusRef = AtomicReference(false)
        activity.runOnUiThread {
            hasFocusRef.set(activity.hasWindowFocus())
        }
        val ui = activity.currentUiStateForTest()
        val needsWebComposite = !ui.isStartPage && !ui.xmr.walletSheetOpen && !ui.xmr.swapSheetOpen
        var bitmap: Bitmap? = null
        if (hasFocusRef.get() && !needsWebComposite) {
            bitmap = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
        }
        if (bitmap == null) {
            bitmap = captureActivityWindowComposite(activity)
                ?: runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
        }
        if (bitmap == null) return
        try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            val bytes = out.toByteArray()
            if (bytes.size > 512) {
                FileOutputStream(File(activity.filesDir, filename)).use { fos ->
                    fos.write(bytes)
                    fos.fd.sync()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureActivityWindowComposite(activity: MainActivity): Bitmap? {
        val dimsRef = AtomicReference<Pair<Int, Int>?>(null)
        val surfaceViews = ArrayList<Pair<SurfaceView, IntArray>>()
        val initLatch = CountDownLatch(1)

        activity.runOnUiThread {
            val decor = activity.window?.decorView
            if (decor != null && decor.width > 0 && decor.height > 0) {
                dimsRef.set(decor.width to decor.height)
                collectVisibleSurfaceViews(decor, surfaceViews)
            }
            initLatch.countDown()
        }
        initLatch.await(5, TimeUnit.SECONDS)
        val (w, h) = dimsRef.get() ?: return null

        val composite = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composite)
        canvas.drawColor(Color.rgb(13, 15, 18))
        val mainHandler = Handler(Looper.getMainLooper())

        val winBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val winLatch = CountDownLatch(1)
        val winResult = AtomicReference(PixelCopy.ERROR_UNKNOWN)
        runCatching {
            PixelCopy.request(
                activity.window,
                winBmp,
                { res ->
                    winResult.set(res)
                    winLatch.countDown()
                },
                mainHandler,
            )
        }.onFailure { winLatch.countDown() }
        winLatch.await(5, TimeUnit.SECONDS)

        // When not on StartPage and no full-screen modal scrim covers the webpage, draw Cefpack's
        // live SurfaceView pixels inside the viewport before overlaying translucent/floating pods,
        // or draw winBmp first and blend SurfaceView when window backdrop is opaque.
        val ui = activity.currentUiStateForTest()
        val modalOpen = ui.xmr.walletSheetOpen || ui.xmr.swapSheetOpen
        if (!ui.isStartPage && !modalOpen) {
            var drewWebpage = false
            val liveBrowser = activity.activeCefpackSessionForTest()
            if (liveBrowser != null) {
                val cefBmpRef = AtomicReference<Bitmap?>(null)
                val cefLatch = CountDownLatch(1)
                activity.runOnUiThread {
                    runCatching {
                        liveBrowser.captureBitmap { captured ->
                            val copy = captured?.let { bmp ->
                                if (!bmp.isRecycled) bmp.copy(Bitmap.Config.ARGB_8888, false) else null
                            }
                            cefBmpRef.set(copy)
                            cefLatch.countDown()
                        }
                    }.onFailure { cefLatch.countDown() }
                }
                cefLatch.await(5, TimeUnit.SECONDS)
                cefBmpRef.get()?.let { cefBmp ->
                    val dstRect = android.graphics.Rect(0, 0, w, h)
                    canvas.drawBitmap(cefBmp, null, dstRect, null)
                    cefBmp.recycle()
                    drewWebpage = true
                }
            }
            if (!drewWebpage) {
                for ((sv, loc) in surfaceViews) {
                    if (sv.width <= 0 || sv.height <= 0 || !sv.holder.surface.isValid) continue
                    val svBmp = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
                    val svLatch = CountDownLatch(1)
                    val svResult = AtomicReference(PixelCopy.ERROR_UNKNOWN)
                    runCatching {
                        PixelCopy.request(
                            sv,
                            svBmp,
                            { res ->
                                svResult.set(res)
                                svLatch.countDown()
                            },
                            mainHandler,
                        )
                    }.onFailure { svLatch.countDown() }
                    svLatch.await(5, TimeUnit.SECONDS)
                    if (svResult.get() == PixelCopy.SUCCESS) {
                        canvas.drawBitmap(svBmp, loc[0].toFloat(), loc[1].toFloat(), null)
                    }
                    svBmp.recycle()
                }
            }
            if (winResult.get() == PixelCopy.SUCCESS) {
                val pixels = IntArray(w * h)
                winBmp.getPixels(pixels, 0, w, 0, 0, w, h)
                for (i in pixels.indices) {
                    val c = pixels[i]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF
                    if (r in 10..16 && g in 12..18 && b in 15..21) {
                        pixels[i] = 0x00000000
                    }
                }
                val chromaBmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                canvas.drawBitmap(chromaBmp, 0f, 0f, null)
                chromaBmp.recycle()
            }
        } else if (winResult.get() == PixelCopy.SUCCESS) {
            canvas.drawBitmap(winBmp, 0f, 0f, null)
        }
        winBmp.recycle()

        return composite
    }

    private fun collectVisibleSurfaceViews(view: View, out: MutableList<Pair<SurfaceView, IntArray>>) {
        if (view.visibility != View.VISIBLE) return
        if (view is SurfaceView) {
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            out.add(view to loc)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectVisibleSurfaceViews(view.getChildAt(i), out)
            }
        }
    }

    private fun loadDocumentSync(activity: MainActivity, html: String, displayUrl: String) {
        val latch = CountDownLatch(1)
        val okRef = AtomicBoolean(false)
        activity.runOnUiThread {
            activity.loadZeroJsDocumentForTest(html = html, displayUrl = displayUrl) { ok ->
                okRef.set(ok)
                latch.countDown()
            }
        }
        assertTrue("Timed out loading zero-JS HTML document", latch.await(15, TimeUnit.SECONDS))
        assertTrue("Page.setDocumentContent must succeed", okRef.get())
    }

    private fun enforceZeroJsCdp(activity: MainActivity, browser: CefpackSession) {
        val latch = CountDownLatch(1)
        val okRef = AtomicBoolean(false)
        activity.runOnUiThread {
            browser.executeDevToolsMethod(
                "Emulation.setScriptExecutionDisabled",
                "{\"value\":true}",
            ) { ok, _ ->
                okRef.set(ok)
                latch.countDown()
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertTrue(okRef.get())
    }

    private fun awaitInputInnerEditorEquals(
        activity: MainActivity,
        browser: CefpackSession,
        elementId: String,
        expected: String,
        timeoutMs: Long,
    ): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastActual = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
            val docJson = queryPiercedDomJson(activity, liveBrowser)
            lastActual = readControlInnerEditorById(docJson, elementId).trim()
            if (lastActual == expected) return lastActual
            SystemClock.sleep(150L)
        }
        return lastActual
    }

    private fun queryPiercedDomJson(activity: MainActivity, browser: CefpackSession): String {
        val latch = CountDownLatch(1)
        val out = AtomicReference("")
        activity.runOnUiThread {
            browser.executeDevToolsMethod("DOM.getDocument", "{\"depth\":-1,\"pierce\":true}") { ok, json ->
                if (ok && !json.isNullOrBlank()) {
                    out.set(json)
                }
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return out.get()
    }

    private fun readControlInnerEditorById(rawDocJson: String, elementId: String): String {
        if (rawDocJson.isBlank()) return ""
        val root = runCatching { JSONObject(rawDocJson).optJSONObject("root") }.getOrNull() ?: return ""
        val target = findElementById(root, elementId) ?: return ""
        val shadowRoots = target.optJSONArray("shadowRoots")
        if (shadowRoots != null && shadowRoots.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until shadowRoots.length()) {
                val sr = shadowRoots.optJSONObject(i) ?: continue
                val inner = findElementById(sr, "inner-editor")
                collectTextNodes(inner ?: sr, sb)
            }
            if (sb.isNotEmpty()) return sb.toString()
        }
        return extractAttribute(target.optJSONArray("attributes"), "value")
    }

    private fun findElementById(node: JSONObject, targetId: String): JSONObject? {
        if (node.optInt("nodeType") == 1 &&
            extractAttribute(node.optJSONArray("attributes"), "id") == targetId
        ) {
            return node
        }
        node.optJSONArray("children")?.let { children ->
            for (i in 0 until children.length()) {
                val child = children.optJSONObject(i) ?: continue
                findElementById(child, targetId)?.let { return it }
            }
        }
        node.optJSONArray("shadowRoots")?.let { shadows ->
            for (i in 0 until shadows.length()) {
                val sr = shadows.optJSONObject(i) ?: continue
                findElementById(sr, targetId)?.let { return it }
            }
        }
        return null
    }

    private fun collectTextNodes(node: JSONObject, out: StringBuilder) {
        if (node.optInt("nodeType") == 3) {
            out.append(node.optString("nodeValue", ""))
            return
        }
        if (node.optInt("nodeType") == 1) {
            val idVal = extractAttribute(node.optJSONArray("attributes"), "id")
            if (idVal.equals("placeholder", ignoreCase = true)) return
        }
        node.optJSONArray("children")?.let { children ->
            for (i in 0 until children.length()) {
                val child = children.optJSONObject(i) ?: continue
                collectTextNodes(child, out)
            }
        }
    }

    private fun extractAttribute(attrs: JSONArray?, key: String): String {
        if (attrs == null) return ""
        var i = 0
        while (i + 1 < attrs.length()) {
            if (attrs.optString(i).equals(key, ignoreCase = true)) {
                return attrs.optString(i + 1, "")
            }
            i += 2
        }
        return ""
    }

    private fun wakeDisplay(instrumentation: android.app.Instrumentation) {
        runShellDrained(instrumentation, "input keyevent KEYCODE_WAKEUP")
        runShellDrained(instrumentation, "cmd statusbar collapse")
    }

    private fun runShellDrained(instrumentation: android.app.Instrumentation, cmd: String) {
        runCatching {
            val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val drain = ByteArrayOutputStream()
                val buf = ByteArray(512)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    drain.write(buf, 0, n)
                }
            }
        }
    }
}
