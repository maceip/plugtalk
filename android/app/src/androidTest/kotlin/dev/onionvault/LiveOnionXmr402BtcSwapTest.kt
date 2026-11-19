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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Second mandatory live Tor v3 `.onion` E2E gate (`TrapXmr402Gate`):
 * Zero-JS `BTC -> XMR` Swap + `XMR402` (`HTTP 402 Payment Required`) `OutProofV2` Unlock.
 *
 * Run selectively via:
 * `-Pandroid.testInstrumentationRunnerArguments.class=dev.onionvault.LiveOnionXmr402BtcSwapTest`
 * (optional `-e xmr402OnionUrl http://<56-char>.onion/`).
 *
 * Verifies end-to-end with zero mocks and JavaScript completely disabled:
 *   1. Cold-boots [MainActivity] and waits for embedded Arti Tor SOCKS5h bootstrap.
 *   2. Creates a fresh 25-word Monero wallet (`0 XMR`) via `RemoteMode.MoneroWallet`.
 *   3. Navigates to `<xmr402OnionUrl>/vault` over Tor, verifies `Xmr402PaymentHardwarePod`
 *      (`xmr402_payment_sheet`) opens on real `HTTP 402` (`0.25 XMR` required, `0 XMR` balance),
 *      and adopts the hidden service's `.onion:18081` restricted `monerod` endpoint.
 *   4. Navigates to `<xmr402OnionUrl>/swap`, taps zero-JS CDP `"Insert XMR Subaddress"`
 *      (`xmr_insert_subaddress_pill`) to inject a site-isolated `8...` subaddress into
 *      `#payout_addr` (asserting Blink `#inner-editor` read-back), and submits `#create-swap-btn`.
 *   5. On `/swap/order/<id>`, asserts zero-JS CDP detects the real `bitcoind` `bcrt1q...`
 *      `btc_deposit_address` (`btc_copy_deposit_pill` + `ActiveSwapBrowserHud`) and submits
 *      `#fund-btc-btn` (real `bitcoind` tx + real Ring-16 `1.0 XMR` payout mined on `monerod`).
 *   6. Syncs the wallet over Tor (`<xmr402OnionHost>:18081`) and asserts unlocked balance
 *      equals `1_000_000_000_000` atomic (`1.0 XMR`).
 *   7. Navigates back to `<xmr402OnionUrl>/vault`, taps `"Pay 0.25 XMR & Unlock"`
 *      (`xmr402_pay_unlock_button`), and verifies single-shot
 *      `Authorization: XMR402 txid="...", proof="OutProofV2..."` header replay renders `HTTP 200`
 *      `PRIZE: OBSIDIAN-MONERO-CITADEL :: ZERO-JS XMR402 + BTC-SWAP CHAMPION [SEAL-402XMR]`.
 *   8. Captures composite screenshot to `files/xmr402-swap-won.png` +
 *      `/data/local/tmp/trap-xmr402-swap-won.png` and logs `XMR402_SWAP_WON_JSON=...`.
 */
@RunWith(AndroidJUnit4::class)
class LiveOnionXmr402BtcSwapTest {

    companion object {
        const val TAG = "TrapXmr402Gate"
        const val APP_XMR402_SCREENSHOT_FILENAME = "xmr402-swap-won.png"
        const val DEVICE_XMR402_SCREENSHOT_PATH = "/data/local/tmp/trap-xmr402-swap-won.png"
        const val EXPECTED_PRIZE_BANNER =
            "PRIZE: OBSIDIAN-MONERO-CITADEL :: ZERO-JS XMR402 + BTC-SWAP CHAMPION [SEAL-402XMR]"

        /**
         * Live DigitalOcean Tor v3 `.onion` XMR402 + BTC->XMR Swap hidden service URL
         * (overridable via `-e xmr402OnionUrl http://<56-char>.onion/`).
         */
        const val DEFAULT_XMR402_ONION_URL: String =
            "http://6e52wfxr64n2zxjhalrldumrjuqfzjh5gbnyarrkuxrvjrusf25g6yad.onion"
    }

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun cold_boot_tor_btc_xmr_swap_and_xmr402_outproofv2_unlocks_live_onion_vault() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val args = InstrumentationRegistry.getArguments()
        val rawOnionUrl = args.getString("xmr402OnionUrl")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_XMR402_ONION_URL
        val holdMs = args.getString("prizeHoldMs")
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(500L, 15_000L)
            ?: 2_000L

        val baseOnionUrl = rawOnionUrl.trim().trimEnd('/')
        val vaultUrl = "$baseOnionUrl/vault"
        val swapUrl = "$baseOnionUrl/swap"
        val onionHost = baseOnionUrl
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
            .lowercase()
        val defaultDaemonUrl = "http://$onionHost:18081"

        CrashReporter.clear(appContext)
        runCatching { File(appContext.filesDir, APP_XMR402_SCREENSHOT_FILENAME).delete() }
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

                val policy = OvNative.parseUrlPolicy(OvNative.nativeCheckUrlPolicy(vaultUrl, true))
                assertTrue(
                    "xmr402OnionUrl must pass strict .onion-only policy ($vaultUrl): ${policy.reason}",
                    policy.allowed && policy.isOnion,
                )

                // 1. Wait for MetaToolsBrowserShell & real Arti Tor SOCKS5h bootstrap.
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

                // 2. Create fresh 25-word Monero wallet (0 XMR) via RemoteMode.MoneroWallet quick blade.
                composeRule.onNodeWithTag("side_remote_collapsed_face").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("remote_monero_wallet").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("remote_monero_wallet").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("remote_xmr_create_wallet").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("remote_xmr_create_wallet").performClick()

                composeRule.waitUntil(timeoutMillis = 45_000L) {
                    !activity.currentUiStateForTest().xmr.creatingOrRestoring &&
                        activity.currentUiStateForTest().xmr.status.hasWallet
                }
                val initialWallet = OvNative.parseXmrStatus(OvNative.nativeXmrStatusJson())
                assertTrue(initialWallet.hasWallet)
                assertEquals("0", initialWallet.unlockedBalanceAtomic)
                assertEquals(
                    25,
                    OvNative.parseXmrSeedResult(OvNative.nativeXmrExportSeedJson())
                        .mnemonic.trim().split(Regex("\\s+")).size,
                )

                // Collapse side remote so CRT address pod & webpage are completely clear.
                if (composeRule.onAllNodesWithTag("remote_close_button").fetchSemanticsNodes().isNotEmpty()) {
                    composeRule.onNodeWithTag("remote_close_button").performClick()
                }
                composeRule.waitForIdle()

                // 3. Navigate to <xmr402OnionUrl>/vault over Tor -> verify HTTP 402 opens Xmr402PaymentHardwarePod.
                navigateViaCrtAddressPod(vaultUrl)
                awaitXmr402SheetOpen(activity, browser, vaultUrl, timeoutMs = 120_000L)

                composeRule.onNodeWithTag("xmr402_payment_sheet").assertIsDisplayed()
                composeRule.onNodeWithTag("xmr402_shortfall_text").assertIsDisplayed()
                assertTrue(
                    "Unfunded wallet must not expose Pay & Unlock button",
                    composeRule.onAllNodesWithTag("xmr402_pay_unlock_button").fetchSemanticsNodes().isEmpty(),
                )

                val activeTabId = activity.activeTabIdForTest().orEmpty()
                val challenge402 = activity.currentUiStateForTest().xmr.challengeForTab(activeTabId)
                assertNotNull("Parsed XMR402 challenge must be present", challenge402)
                assertEquals("250000000000", challenge402!!.amountAtomic)
                assertEquals("0.25", challenge402.amountXmr)

                // Adopt the page's .onion:18081 monerod endpoint via 1-tap hardware button.
                if (composeRule.onAllNodesWithTag("xmr402_use_page_daemon_button")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                ) {
                    composeRule.onNodeWithTag("xmr402_use_page_daemon_button")
                        .performScrollTo()
                        .performClick()
                    composeRule.waitUntil(timeoutMillis = 60_000L) {
                        val xmrUi = activity.currentUiStateForTest().xmr
                        !xmrUi.syncing && xmrUi.status.syncEndpoint.contains(":18081")
                    }
                } else {
                    val targetDaemon = challenge402.daemonUrl.ifBlank { defaultDaemonUrl }
                    OvNative.nativeXmrSetDaemonEndpointJson(targetDaemon, "monerod_onion")
                }

                // Dismiss 402 modal to view the 402 page & verify compact reopen banner is displayed.
                composeRule.onNodeWithTag("xmr402_view_page_button").performScrollTo().performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("xmr402_payment_sheet").fetchSemanticsNodes().isEmpty()
                }
                composeRule.onNodeWithTag("xmr402_reopen_banner").assertIsDisplayed()

                // 4. Navigate to <xmr402OnionUrl>/swap -> inject site-isolated 8... subaddress via zero-JS CDP -> create order.
                val clickedSwapLink = tryClickElementByIdZeroJsCdp(activity, browser, "goto-swap-desk")
                if (!clickedSwapLink) {
                    navigateViaCrtAddressPod(swapUrl)
                }

                awaitSwapFormAndSubaddressPill(activity, browser, swapUrl, timeoutMs = 90_000L)
                composeRule.onNodeWithTag("xmr_insert_subaddress_pill").assertIsDisplayed().performClick()

                val swapXmrSubaddress = awaitInputNonEmpty8Subaddress(
                    activity = activity,
                    browser = browser,
                    elementId = "payout_addr",
                    timeoutMs = 20_000L,
                )
                assertTrue(
                    "Expected 95-char 8... subaddress in Blink #inner-editor of #payout_addr, got '$swapXmrSubaddress'",
                    swapXmrSubaddress.startsWith("8") && swapXmrSubaddress.length == 95,
                )
                val statusAfterSiteSub = OvNative.parseXmrStatus(OvNative.nativeXmrStatusJson())
                assertTrue(
                    "Expected per-origin 'site:<host>' subaddress label in wallet store",
                    statusAfterSiteSub.subaddresses.any {
                        it.address == swapXmrSubaddress && it.label.startsWith("site:")
                    },
                )

                clickElementByIdZeroJsCdp(activity, browser, "create-swap-btn")

                // 5. On /swap/order/<id>, verify zero-JS CDP detects real bitcoind bcrt1q... deposit address + HUD, then fund BTC.
                val swapBtcAddress = awaitOrderBtcDepositAndHud(
                    activity = activity,
                    browser = browser,
                    timeoutMs = 90_000L,
                )
                assertTrue(
                    "Expected real Bech32 bitcoind regtest deposit address (bcrt1...), got '$swapBtcAddress'",
                    swapBtcAddress.startsWith("bcrt1", ignoreCase = true) ||
                        swapBtcAddress.startsWith("bc1", ignoreCase = true),
                )
                composeRule.onNodeWithTag("active_swap_hud").assertIsDisplayed()

                if (composeRule.onAllNodesWithTag("active_swap_hud_dismiss").fetchSemanticsNodes().isNotEmpty()) {
                    composeRule.onNodeWithTag("active_swap_hud_dismiss").performClick()
                    composeRule.waitForIdle()
                }

                clickElementByIdZeroJsCdp(activity, browser, "fund-btc-btn")
                awaitSwapOrderCompletedDom(activity, browser, timeoutMs = 90_000L)

                // 6. Sync wallet over Tor (<xmr402OnionHost>:18081) and assert unlocked balance == 1.0 XMR (1_000_000_000_000 atomic).
                composeRule.onNodeWithTag("side_remote_collapsed_face").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("remote_monero_wallet").fetchSemanticsNodes().isNotEmpty() ||
                        composeRule.onAllNodesWithTag("remote_xmr_sync").fetchSemanticsNodes().isNotEmpty()
                }
                if (composeRule.onAllNodesWithTag("remote_monero_wallet").fetchSemanticsNodes().isNotEmpty()) {
                    composeRule.onNodeWithTag("remote_monero_wallet").performClick()
                }
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("remote_xmr_sync").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("remote_xmr_sync").performClick()

                val syncDeadline = SystemClock.uptimeMillis() + 90_000L
                var retriedSync = false
                var fundedStatus = OvNative.XmrStatusSnapshot()
                while (SystemClock.uptimeMillis() < syncDeadline) {
                    fundedStatus = OvNative.parseXmrStatus(OvNative.nativeXmrStatusJson())
                    val uiSyncing = activity.currentUiStateForTest().xmr.syncing
                    if (!uiSyncing && fundedStatus.unlockedBalanceAtomic == "1000000000000") {
                        break
                    }
                    if (!uiSyncing && !retriedSync && fundedStatus.syncedHeight > 0L && fundedStatus.unlockedBalanceAtomic == "0") {
                        retriedSync = true
                        runCatching { composeRule.onNodeWithTag("remote_xmr_sync").performClick() }
                    }
                    SystemClock.sleep(400L)
                }
                assertEquals(
                    "Unlocked Monero balance after swap payout must equal 1_000_000_000_000 atomic (1.0 XMR)",
                    "1000000000000",
                    fundedStatus.unlockedBalanceAtomic,
                )
                Log.i(
                    TAG,
                    "Synced 1.0 XMR swap payout over Tor (height=${fundedStatus.syncedHeight}/${fundedStatus.daemonHeight})",
                )

                if (composeRule.onAllNodesWithTag("remote_close_button").fetchSemanticsNodes().isNotEmpty()) {
                    composeRule.onNodeWithTag("remote_close_button").performClick()
                }
                composeRule.waitForIdle()

                // 7. Navigate back to <xmr402OnionUrl>/vault -> tap Pay 0.25 XMR & Unlock -> verify HTTP 200 prize!
                val clickedReturn = tryClickElementByIdZeroJsCdp(activity, browser, "return-to-vault")
                if (!clickedReturn) {
                    navigateViaCrtAddressPod(vaultUrl)
                }

                awaitXmr402SheetOpen(activity, browser, vaultUrl, timeoutMs = 90_000L)
                composeRule.waitUntil(timeoutMillis = 15_000L) {
                    composeRule.onAllNodesWithTag("xmr402_pay_unlock_button")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithTag("xmr402_pay_unlock_button")
                    .performScrollTo()
                    .assertIsEnabled()
                    .performClick()

                val unlockedDomJson = awaitVaultPrizeUnlockedDom(
                    activity = activity,
                    browser = browser,
                    timeoutMs = 120_000L,
                )
                val prizeBanner = readElementVisibleTextById(unlockedDomJson, "xmr402-prize-banner").trim()
                assertEquals(EXPECTED_PRIZE_BANNER, prizeBanner)

                composeRule.waitUntil(timeoutMillis = 10_000L) {
                    composeRule.onAllNodesWithTag("xmr402_payment_sheet").fetchSemanticsNodes().isEmpty()
                }

                val prizeJsonRaw = readElementVisibleTextById(unlockedDomJson, "xmr402-prize-json").trim()
                val prizeJsonObj = runCatching { JSONObject(prizeJsonRaw) }.getOrNull()
                val postPayStatus = OvNative.parseXmrStatus(OvNative.nativeXmrStatusJson())
                val xmr402Txid = prizeJsonObj?.optString("txid", "")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: postPayStatus.recentTxs.firstOrNull { it.direction == "out" }?.txid.orEmpty()
                val proofPrefix = prizeJsonObj?.optString("proofPrefix", "")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "OutProofV2"

                assertEquals("XMR402 payment txid must be 64-hex", 64, xmr402Txid.length)
                assertTrue(
                    "Proof prefix must start with canonical OutProofV2, got '$proofPrefix'",
                    proofPrefix.startsWith("OutProofV2"),
                )

                // 8. Capture composite screenshot & emit fail-closed XMR402_SWAP_WON_JSON gate line.
                composeRule.waitForIdle()
                SystemClock.sleep(600L)
                captureWonScreenshot(instrumentation, activity)

                val wonPayload = JSONObject()
                    .put("onionUrl", vaultUrl)
                    .put("swapBtcAddress", swapBtcAddress)
                    .put("swapXmrSubaddress", swapXmrSubaddress)
                    .put("xmr402Txid", xmr402Txid)
                    .put("proofPrefix", proofPrefix)
                    .put("prizeBanner", prizeBanner)
                Log.i(TAG, "XMR402_SWAP_WON_JSON=$wonPayload")

                SystemClock.sleep(holdMs)
            }
        } finally {
            prefs.requireBiometric = previousBiometric
            if (OvNative.isLoaded) {
                runCatching { OvNative.nativeXmrDeleteWallet() }
                runCatching { OvNative.nativeSetRequireBiometric(previousBiometric) }
            }
        }
    }

    private fun navigateViaCrtAddressPod(targetUrl: String) {
        composeRule.onNodeWithTag("crt_address_pod").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("crt_address_input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("crt_address_input").performTextReplacement(targetUrl)
        composeRule.onNodeWithTag("crt_address_input").performImeAction()
    }

    private fun awaitXmr402SheetOpen(
        activity: MainActivity,
        browser: CefpackSession,
        expectedUrl: String,
        timeoutMs: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastScanKickMs = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            val sheetOpen = runCatching {
                composeRule.onAllNodesWithTag("xmr402_payment_sheet")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
            if (sheetOpen) return

            val reopenVisible = runCatching {
                composeRule.onAllNodesWithTag("xmr402_reopen_banner")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
            if (reopenVisible) {
                runCatching { composeRule.onNodeWithTag("xmr402_reopen_banner").performClick() }
            }

            val now = SystemClock.uptimeMillis()
            if (now - lastScanKickMs >= 1_500L) {
                lastScanKickMs = now
                val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
                val tabId = activity.activeTabIdForTest()
                if (tabId != null) {
                    activity.cdpControllerForTest()?.attachAndScan(tabId, liveBrowser, expectedUrl)
                }
            }
            SystemClock.sleep(300L)
        }
        fail("Timed out after ${timeoutMs}ms waiting for Xmr402PaymentHardwarePod on $expectedUrl")
    }

    private fun awaitSwapFormAndSubaddressPill(
        activity: MainActivity,
        browser: CefpackSession,
        swapUrl: String,
        timeoutMs: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastScanKickMs = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
            val domJson = queryPiercedDomJson(activity, liveBrowser)
            val root = parseDomRoot(domJson)
            if (root != null &&
                findElementById(root, "payout_addr") != null &&
                findElementById(root, "create-swap-btn") != null
            ) {
                val now = SystemClock.uptimeMillis()
                if (now - lastScanKickMs >= 1_200L) {
                    lastScanKickMs = now
                    val tabId = activity.activeTabIdForTest()
                    if (tabId != null) {
                        activity.cdpControllerForTest()?.attachAndScan(tabId, liveBrowser, swapUrl)
                    }
                }
                val hasPill = runCatching {
                    composeRule.onAllNodesWithTag("xmr_insert_subaddress_pill")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }.getOrDefault(false)
                if (hasPill) return
            }
            SystemClock.sleep(300L)
        }
        fail("Timed out waiting for /swap form (#payout_addr) and 'xmr_insert_subaddress_pill'")
    }

    private fun awaitInputNonEmpty8Subaddress(
        activity: MainActivity,
        browser: CefpackSession,
        elementId: String,
        timeoutMs: Long,
    ): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastActual = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
            val docJson = queryPiercedDomJson(activity, liveBrowser)
            lastActual = readControlInnerEditorById(docJson, elementId).trim()
            if (lastActual.startsWith("8") && lastActual.length == 95) {
                return lastActual
            }
            SystemClock.sleep(150L)
        }
        return lastActual
    }

    private fun awaitOrderBtcDepositAndHud(
        activity: MainActivity,
        browser: CefpackSession,
        timeoutMs: Long,
    ): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastScanKickMs = 0L
        var lastAddr = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
            val domJson = queryPiercedDomJson(activity, liveBrowser)
            val root = parseDomRoot(domJson)
            if (root != null &&
                findElementById(root, "deposit_address") != null &&
                findElementById(root, "fund-btc-btn") != null
            ) {
                lastAddr = readControlInnerEditorById(domJson, "deposit_address").trim()
                val now = SystemClock.uptimeMillis()
                if (now - lastScanKickMs >= 1_200L) {
                    lastScanKickMs = now
                    val tabId = activity.activeTabIdForTest()
                    if (tabId != null) {
                        activity.cdpControllerForTest()?.attachAndScan(
                            tabId = tabId,
                            browser = liveBrowser,
                            currentUrl = liveBrowser.url.orEmpty(),
                        )
                    }
                }
                val hasPillOrHud = runCatching {
                    composeRule.onAllNodesWithTag("btc_copy_deposit_pill")
                        .fetchSemanticsNodes()
                        .isNotEmpty() &&
                        composeRule.onAllNodesWithTag("active_swap_hud")
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                }.getOrDefault(false)
                if (lastAddr.isNotEmpty() && hasPillOrHud) {
                    return lastAddr
                }
            }
            SystemClock.sleep(300L)
        }
        fail("Timed out waiting for /swap/order BTC deposit address & ActiveSwapBrowserHud (lastAddr='$lastAddr')")
        return lastAddr
    }

    private fun awaitSwapOrderCompletedDom(
        activity: MainActivity,
        browser: CefpackSession,
        timeoutMs: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastStatus = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
            val domJson = queryPiercedDomJson(activity, liveBrowser)
            val root = parseDomRoot(domJson)
            if (root != null) {
                val statusNode = findElementById(root, "swap-status")
                lastStatus = extractAttribute(statusNode?.optJSONArray("attributes"), "data-status")
                if (lastStatus.equals("completed", ignoreCase = true) ||
                    findElementById(root, "return-to-vault") != null
                ) {
                    return
                }
            }
            SystemClock.sleep(350L)
        }
        fail("Timed out waiting for /swap/order/:id completion after BTC funding (lastStatus='$lastStatus')")
    }

    private fun awaitVaultPrizeUnlockedDom(
        activity: MainActivity,
        browser: CefpackSession,
        timeoutMs: Long,
    ): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastBanner = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val xmrErr = activity.currentUiStateForTest().xmr.error
            if (!xmrErr.isNullOrBlank()) {
                fail("XMR402 payment reported error: $xmrErr")
            }
            val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
            val domJson = queryPiercedDomJson(activity, liveBrowser)
            val root = parseDomRoot(domJson)
            if (root != null && findElementById(root, "xmr402-prize-banner") != null) {
                lastBanner = readElementVisibleTextById(domJson, "xmr402-prize-banner").trim()
                if (lastBanner == EXPECTED_PRIZE_BANNER) {
                    return domJson
                }
            }
            SystemClock.sleep(350L)
        }
        fail("Timed out waiting for unlocked HTTP 200 #xmr402-prize-banner (lastBanner='$lastBanner')")
        return ""
    }

    private fun tryClickElementByIdZeroJsCdp(
        activity: MainActivity,
        browser: CefpackSession,
        elementId: String,
    ): Boolean {
        val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
        val domJson = queryPiercedDomJson(activity, liveBrowser)
        val root = parseDomRoot(domJson) ?: return false
        if (findElementById(root, elementId) == null) return false
        return runCatching {
            clickElementByIdZeroJsCdp(activity, liveBrowser, elementId)
            true
        }.getOrDefault(false)
    }

    private fun clickElementByIdZeroJsCdp(
        activity: MainActivity,
        browser: CefpackSession,
        elementId: String,
    ) {
        val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
        val domJson = queryPiercedDomJson(activity, liveBrowser)
        val root = parseDomRoot(domJson)
        assertNotNull("DOM root must exist when clicking #$elementId", root)
        val target = findElementById(root!!, elementId)
        assertNotNull("Element #$elementId must exist in DOM", target)

        val nodeId = target!!.optInt("nodeId", 0)
        val backendNodeId = target.optInt("backendNodeId", 0)
        val locatorJson = JSONObject().apply {
            if (backendNodeId > 0) {
                put("backendNodeId", backendNodeId)
            } else {
                put("nodeId", nodeId)
            }
        }.toString()

        executeCdpSync(activity, liveBrowser, "DOM.scrollIntoViewIfNeeded", locatorJson)

        val (_, layoutJson) = executeCdpSync(activity, liveBrowser, "Page.getLayoutMetrics", "{}")
        var pageX = 0.0
        var pageY = 0.0
        if (layoutJson.isNotBlank()) {
            runCatching {
                val rootMetrics = JSONObject(layoutJson)
                val vp = rootMetrics.optJSONObject("cssVisualViewport")
                    ?: rootMetrics.optJSONObject("cssLayoutViewport")
                if (vp != null) {
                    pageX = vp.optDouble("pageX", 0.0)
                    pageY = vp.optDouble("pageY", 0.0)
                }
            }
        }

        val (boxOk, boxJson) = executeCdpSync(activity, liveBrowser, "DOM.getBoxModel", locatorJson)
        assertTrue("DOM.getBoxModel failed for #$elementId: $boxJson", boxOk && boxJson.isNotBlank())

        val model = JSONObject(boxJson).getJSONObject("model")
        val quad = model.optJSONArray("border") ?: model.getJSONArray("content")
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until 8 step 2) {
            val x = quad.getDouble(i)
            val y = quad.getDouble(i + 1)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val centerX = (minX + maxX) / 2.0 - pageX
        val centerY = (minY + maxY) / 2.0 - pageY

        val moveParams = JSONObject()
            .put("type", "mouseMoved")
            .put("x", centerX)
            .put("y", centerY)
            .put("button", "none")
            .put("buttons", 0)
            .toString()
        val pressParams = JSONObject()
            .put("type", "mousePressed")
            .put("x", centerX)
            .put("y", centerY)
            .put("button", "left")
            .put("buttons", 1)
            .put("clickCount", 1)
            .toString()
        val releaseParams = JSONObject()
            .put("type", "mouseReleased")
            .put("x", centerX)
            .put("y", centerY)
            .put("button", "left")
            .put("buttons", 0)
            .put("clickCount", 1)
            .toString()

        executeCdpSync(activity, liveBrowser, "Input.dispatchMouseEvent", moveParams)
        val (pressOk, _) = executeCdpSync(activity, liveBrowser, "Input.dispatchMouseEvent", pressParams)
        val (relOk, _) = executeCdpSync(activity, liveBrowser, "Input.dispatchMouseEvent", releaseParams)
        assertTrue("Zero-JS CDP click on #$elementId must succeed", pressOk && relOk)
    }

    private fun enforceZeroJsCdp(activity: MainActivity, browser: CefpackSession) {
        val (ok, _) = executeCdpSync(
            activity = activity,
            browser = browser,
            method = "Emulation.setScriptExecutionDisabled",
            paramsJson = "{\"value\":true}",
        )
        assertTrue("Emulation.setScriptExecutionDisabled(true) must succeed", ok)
    }

    private fun executeCdpSync(
        activity: MainActivity,
        browser: CefpackSession,
        method: String,
        paramsJson: String,
    ): Pair<Boolean, String> {
        val latch = CountDownLatch(1)
        val okRef = AtomicReference(false)
        val jsonRef = AtomicReference("")
        activity.runOnUiThread {
            browser.executeDevToolsMethod(method, paramsJson) { ok, json ->
                okRef.set(ok)
                jsonRef.set(json.orEmpty())
                latch.countDown()
            }
        }
        assertTrue("CDP $method timed out", latch.await(15, TimeUnit.SECONDS))
        return okRef.get() to jsonRef.get()
    }

    private fun queryPiercedDomJson(activity: MainActivity, browser: CefpackSession): String {
        val latch = CountDownLatch(1)
        val out = AtomicReference("")
        activity.runOnUiThread {
            runCatching {
                browser.executeDevToolsMethod("DOM.getDocument", "{\"depth\":-1,\"pierce\":true}") { ok, json ->
                    if (ok && !json.isNullOrBlank()) {
                        out.set(json)
                    }
                    latch.countDown()
                }
            }.onFailure { latch.countDown() }
        }
        latch.await(5, TimeUnit.SECONDS)
        return out.get()
    }

    private fun parseDomRoot(rawDocJson: String): JSONObject? {
        if (rawDocJson.isBlank()) return null
        return runCatching { JSONObject(rawDocJson).optJSONObject("root") }.getOrNull()
    }

    private fun readElementVisibleTextById(rawDocJson: String, elementId: String): String {
        val root = parseDomRoot(rawDocJson) ?: return ""
        val target = findElementById(root, elementId) ?: return ""
        val sb = StringBuilder()
        collectTextNodes(target, sb)
        return sb.toString()
    }

    private fun readControlInnerEditorById(rawDocJson: String, elementId: String): String {
        val root = parseDomRoot(rawDocJson) ?: return ""
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
            val tag = node.optString("localName", node.optString("nodeName", "")).lowercase()
            if (tag == "script" || tag == "style") return
            if (tag == "br") {
                out.append('\n')
                return
            }
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

    private fun captureWonScreenshot(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
    ) {
        var bitmap = captureActivityWindowComposite(activity)
        if (bitmap == null) {
            bitmap = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
        }
        assertNotNull("Failed to capture unlocked XMR402 prize screenshot", bitmap)
        try {
            val out = ByteArrayOutputStream()
            bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, out)
            val pngBytes = out.toByteArray()
            assertTrue("Encoded XMR402 prize PNG must be non-empty", pngBytes.size > 512)

            FileOutputStream(File(activity.filesDir, APP_XMR402_SCREENSHOT_FILENAME)).use { fos ->
                fos.write(pngBytes)
                fos.fd.sync()
            }
            runCatching {
                val pfds = instrumentation.uiAutomation.executeShellCommandRw(
                    "dd of=$DEVICE_XMR402_SCREENSHOT_PATH bs=65536",
                )
                ParcelFileDescriptor.AutoCloseOutputStream(pfds[1]).use { stdin ->
                    stdin.write(pngBytes)
                    stdin.flush()
                }
                ParcelFileDescriptor.AutoCloseInputStream(pfds[0]).use { stdout ->
                    val buf = ByteArray(1024)
                    while (stdout.read(buf) > 0) {
                        // Drain dd summary
                    }
                }
            }
        } finally {
            bitmap?.recycle()
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
