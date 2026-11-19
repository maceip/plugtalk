package dev.onionvault

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cefpack.android.CefpackSession
import dev.onionvault.ui.TrapPreferences
import java.io.ByteArrayOutputStream
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
 *   1. Cold-boots [MainActivity] and waits for embedded Arti Tor SOCKS5h bootstrap.
 *   2. Creates a real 25-word Monero wallet wrapped in Android Keystore via `MoneroWalletHardwarePod`.
 *   3. Syncs live `.onion` `monerod` block height over Arti Tor (`daemonHeight > 3,500,000`).
 *   4. Fetches live zero-JS BTC -> XMR swap quotes from OrangeFren `.onion` over Arti Tor
 *      and mints a dedicated `8...` swap subaddress.
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
                OvNative.nativeXmrDeleteWallet()

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
                    if (torSnap.bootstrapped && socksPort > 1) break
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

                // 2. Open PhotoSoapSideRemote -> MoneroWalletHardwarePod -> Create real 25-word wallet.
                composeRule.onNodeWithTag("side_remote_collapsed_face").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("remote_monero_wallet").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("remote_monero_wallet").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("xmr_wallet_sheet").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("xmr_create_wallet_button").performClick()

                composeRule.waitUntil(timeoutMillis = 60_000L) {
                    OvNative.parseXmrStatus(OvNative.nativeXmrStatusJson()).hasWallet
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

                // 3. Sync live .onion monerod block height over Arti Tor.
                composeRule.onNodeWithTag("xmr_sync_button").performClick()
                composeRule.waitUntil(timeoutMillis = 90_000L) {
                    val snap = OvNative.parseXmrStatus(OvNative.nativeXmrStatusJson())
                    snap.daemonHeight > 3_500_000L && snap.syncedHeight >= snap.restoreHeight
                }
                val syncedStatus = OvNative.parseXmrStatus(OvNative.nativeXmrStatusJson())
                Log.i(
                    TAG,
                    "Synced live .onion monerod height=${syncedStatus.syncedHeight}/${syncedStatus.daemonHeight}",
                )
                assertTrue(syncedStatus.daemonHeight > 3_500_000L)

                // 4. Switch to BtcToXmrSwapHardwarePod and fetch real zero-JS quotes from OrangeFren .onion over Tor.
                composeRule.onNodeWithTag("xmr_open_swap_button").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("btc_xmr_swap_sheet").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("swap_fetch_quotes_button").performClick()

                val quoteDeadline = SystemClock.uptimeMillis() + 90_000L
                var liveQuotes = emptyList<OvNative.BtcSwapQuoteItem>()
                while (SystemClock.uptimeMillis() < quoteDeadline) {
                    val cards = runCatching {
                        composeRule.onAllNodesWithTag("btc_xmr_swap_sheet").fetchSemanticsNodes()
                    }.getOrDefault(emptyList())
                    if (cards.isNotEmpty()) {
                        // Poll directly if UI finished or retry once if transient .onion circuit timeout occurred.
                        val directRes = OvNative.parseBtcSwapQuotes(
                            OvNative.nativeXmrFetchBtcSwapQuotesJson("0.01"),
                        )
                        if (directRes.ok && directRes.quotes.isNotEmpty()) {
                            liveQuotes = directRes.quotes
                            break
                        }
                    }
                    SystemClock.sleep(1_000L)
                }
                assertFalse("Expected live zero-JS BTC->XMR quotes from OrangeFren .onion", liveQuotes.isEmpty())
                val chosenQuote = liveQuotes.firstOrNull { it.isOnionUrl } ?: liveQuotes.first()
                Log.i(
                    TAG,
                    "Fetched ${liveQuotes.size} live Tor quotes; top=${chosenQuote.provider} -> ${chosenQuote.estimatedXmr} XMR",
                )

                // Mint dedicated 8... subaddress for this swap via nativeXmrSwapsActionJson.
                val createSwapRes = OvNative.parseXmrSwapsAction(
                    OvNative.nativeXmrSwapsActionJson(
                        OvNative.buildSwapCreateActionJson(
                            provider = chosenQuote.provider,
                            btcAmount = "0.01",
                            quotedXmr = chosenQuote.estimatedXmr,
                            launchUrl = chosenQuote.launchUrl,
                        ),
                    ),
                )
                assertTrue("Swap subaddress creation must succeed: ${createSwapRes.error}", createSwapRes.ok)
                val dedicatedSwap = createSwapRes.swap
                assertNotNull("Created swap record must be non-null", dedicatedSwap)
                assertTrue(dedicatedSwap!!.subaddress.startsWith("8"))
                assertEquals(95, dedicatedSwap.subaddress.length)

                composeRule.onNodeWithTag("sheet_close_button").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("btc_xmr_swap_sheet").fetchSemanticsNodes().isEmpty()
                }

                // 5. Load zero-JS swap form into CefpackSession, tap "Insert XMR Subaddress",
                //    and assert Blink #inner-editor contains the minted 8... subaddress!
                val swapFormHtml = """
                    <!doctype html>
                    <html>
                    <head><meta charset="utf-8"><title>Zero-JS BTC to XMR Swap</title></head>
                    <body style="background:#0d0f12;color:#e8edf2;font-family:monospace;padding:24px">
                      <h2>BTC to Monero (XMR) Exchange</h2>
                      <form id="wizardswap" method="POST" action="/">
                        <h6>Recipient Monero Address</h6>
                        <input type="text" id="address" name="address" placeholder="Enter the recipient's Monero address" style="width:92%;height:42px" />
                        <h6>Bitcoin Refund Address</h6>
                        <input type="text" id="refund_address" name="refund_address" placeholder="Enter a Bitcoin refund address" style="width:92%;height:42px" />
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

                // 6. Load zero-JS BTC deposit order DOM and verify automatic deposit address attachment + HUD!
                val orderPageHtml = """
                    <!doctype html>
                    <html>
                    <head><meta charset="utf-8"><title>Order Waiting for BTC Deposit</title></head>
                    <body style="background:#0d0f12;color:#e8edf2;font-family:monospace;padding:24px">
                      <h2>Waiting for deposit</h2>
                      <p>Send <span id="amount_a">0.01</span> BTC to:</p>
                      <input type="text" id="deposit_address" name="deposit_address" readonly="readonly" value="$SAMPLE_VALID_BTC_DEPOSIT" style="width:92%;height:42px" />
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
        runCatching {
            val pfd = instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP")
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
