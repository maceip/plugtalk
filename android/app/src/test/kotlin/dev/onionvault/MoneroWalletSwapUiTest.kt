package dev.onionvault

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import dev.onionvault.ui.BrowserScreen
import dev.onionvault.ui.BrowserUiState
import dev.onionvault.ui.MoneroUiState
import dev.onionvault.ui.MoneroWalletTab
import dev.onionvault.ui.NormalizedBounds
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.PgpOverlayState
import dev.onionvault.ui.TorBootstrapState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Automated Robolectric Compose UI & `TabSessionManager` verification suite for Trap's
 * pure-Rust Monero Light Wallet (`MoneroWalletHardwarePod`), Browser-Wired BTC -> XMR Swap
 * (`BtcToXmrSwapHardwarePod`, `ActiveSwapBrowserHud`), zero-JS DOM overlay pills
 * (`Insert XMR Subaddress`, `Copy BTC Deposit`, `Pay XMR`), and `monero:` / `about:wallet` /
 * `about:swap` navigation interception.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoneroWalletSwapUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val primaryAddress =
        "47WtH2aYaa6ceF14pr6FpHQjhLNz9wSA3Q6UHdJK4tAMAQgXbEYbo6x4FvJYZVCjuMBBxSMJDiGRU3tSjJt1HZTHNH3sAns"
    private val subaddressOne =
        "82ffYh7xRhFBvBHEHGV8TNRBoYucBFNoyYnwrJJjnMGpHKPWosZeB4BDr7pBG4DCux1WKepdAV6rRTimi1LqWMacF88hvQm"
    private val subaddressSwap =
        "88aBcDeFgHiJkLmNoPqRsTuVwXyZ123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz123456789"
    private val sampleMnemonic25 =
        "abbey abducts ability ablaze abnormal abort abrasive absorb abyss academy " +
            "aces aching acidic acoustic acquire across actress acumen adapt addicted " +
            "adept adhesive adjust adopt abbey"
    private val wizardOnionUrl =
        "http://wizardswgtu2ovor7r2esg3cxdpt7tv4nrugi32lldv53zmtonbz6sid.onion/?from=BTC&to=XMR&amount=0.01"

    @Test
    fun start_page_and_idle_metatools_pods_remain_100_percent_untouched_even_with_active_swap() {
        val trackedSwap = OvNative.TrackedBtcXmrSwap(
            swapId = "swap-home-check",
            provider = "WizardSwap",
            btcAmount = "0.01",
            quotedXmr = "1.23907791",
            launchUrl = wizardOnionUrl,
            subaddressIndex = 2,
            subaddress = subaddressSwap,
            status = "awaiting_form",
        )
        val state = BrowserUiState(
            url = "about:blank",
            tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
            xmr = MoneroUiState(
                status = OvNative.XmrStatusSnapshot(
                    hasWallet = true,
                    walletLabel = "Trap Light Wallet",
                    primaryAddress = primaryAddress,
                    activeSubaddress = subaddressOne,
                    activeSubaddressIndex = 1,
                    unlockedBalanceXmr = "1.23907791",
                    balanceXmr = "1.23907791",
                    swaps = listOf(trackedSwap),
                ),
                activeSwapTabMap = mapOf("tab-1" to trackedSwap.swapId),
            ),
        )

        compose.setContent {
            BrowserScreen(state = state)
        }
        compose.waitForIdle()

        // 1:1 StartPage & 5 floating hardware pods preserved; zero extra round balls or homepage HUD clutter.
        compose.onNodeWithTag("start_page").assertIsDisplayed()
        compose.onNodeWithTag("tor_orb_pod").assertIsDisplayed()
        compose.onNodeWithTag("kpt_back_pod").assertIsDisplayed()
        compose.onNodeWithTag("crt_address_pod").assertIsDisplayed()
        compose.onNodeWithTag("pgp_rocker_pod").assertIsDisplayed()
        compose.onNodeWithTag("side_remote_pod").assertIsDisplayed()

        compose.onAllNodesWithTag("tor_orb_pod").assertCountEquals(1)
        compose.onNodeWithTag("active_swap_hud").assertDoesNotExist()
        compose.onNodeWithTag("xmr_wallet_sheet").assertDoesNotExist()
        compose.onNodeWithTag("btc_xmr_swap_sheet").assertDoesNotExist()

        val backBounds = compose.onNodeWithTag("kpt_back_pod").getUnclippedBoundsInRoot()
        val orbBounds = compose.onNodeWithTag("tor_orb_pod").getUnclippedBoundsInRoot()
        assertTrue(backBounds.width > backBounds.height * 1.35f)
        assertTrue(orbBounds.width >= 100.dp && orbBounds.height >= 100.dp)
    }

    @Test
    fun side_remote_launches_monero_wallet_create_mint_subaddress_send_node_seed_and_delete() {
        var createdLabel = ""
        var mintedSubLabel = ""
        var syncedCount by mutableIntStateOf(0)
        var savedEndpoint = ""
        var savedMode = ""
        var sentDest = ""
        var sentAmount = ""
        var sentSweep = false
        var deletedCount = 0
        val copiedValues = mutableListOf<String>()

        var state by mutableStateOf(
            BrowserUiState(
                url = "about:blank",
                tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
            ),
        )

        compose.setContent {
            BrowserScreen(
                state = state,
                surface = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0E1013)),
                    )
                },
                onCopyText = { copiedValues += it },
                onOpenMoneroWallet = {
                    state = state.copy(
                        xmr = state.xmr.copy(walletSheetOpen = true, swapSheetOpen = false),
                    )
                },
                onDismissMoneroWallet = {
                    state = state.copy(
                        xmr = state.xmr.copy(walletSheetOpen = false, revealedMnemonic = null),
                    )
                },
                onOpenBtcXmrSwap = {
                    state = state.copy(
                        xmr = state.xmr.copy(swapSheetOpen = true, walletSheetOpen = false),
                    )
                },
                onDismissBtcXmrSwap = {
                    state = state.copy(xmr = state.xmr.copy(swapSheetOpen = false))
                },
                onCreateXmrWallet = { label ->
                    createdLabel = label
                    state = state.copy(
                        xmr = state.xmr.copy(
                            newlyCreatedMnemonic = sampleMnemonic25,
                            status = OvNative.XmrStatusSnapshot(
                                ok = true,
                                hasWallet = true,
                                walletLabel = label,
                                primaryAddress = primaryAddress,
                                activeSubaddress = subaddressOne,
                                activeSubaddressIndex = 1,
                                balanceXmr = "1.5000",
                                unlockedBalanceXmr = "1.2500",
                                syncedHeight = 3809609L,
                                daemonHeight = 3809609L,
                                syncMode = "monerod_onion",
                                subaddresses = listOf(
                                    OvNative.XmrSubaddressItem(
                                        index = 1,
                                        label = label,
                                        address = subaddressOne,
                                        receivedXmr = "1.5000",
                                    ),
                                ),
                            ),
                        ),
                    )
                },
                onSyncXmrWallet = {
                    syncedCount += 1
                    state = state.copy(
                        xmr = state.xmr.copy(
                            status = state.xmr.status.copy(
                                syncedHeight = 3809612L,
                                daemonHeight = 3809612L,
                            ),
                        ),
                    )
                },
                onNewXmrSubaddress = { label ->
                    mintedSubLabel = label
                    val nextItem = OvNative.XmrSubaddressItem(
                        index = 2,
                        label = label,
                        address = subaddressSwap,
                    )
                    state = state.copy(
                        xmr = state.xmr.copy(
                            status = state.xmr.status.copy(
                                activeSubaddress = subaddressSwap,
                                activeSubaddressIndex = 2,
                                subaddresses = state.xmr.status.subaddresses + nextItem,
                            ),
                        ),
                    )
                },
                onSendXmr = { dest, amt, sweep ->
                    sentDest = dest
                    sentAmount = amt
                    sentSweep = sweep
                    state = state.copy(
                        xmr = state.xmr.copy(
                            lastTxid = "99aabbccddeeff00112233445566778899aabbccddeeff001122334455667788",
                            lastFeeXmr = "0.000024",
                        ),
                    )
                },
                onSetXmrDaemonEndpoint = { url, mode ->
                    savedEndpoint = url
                    savedMode = mode
                },
                onRevealXmrSeed = {
                    state = state.copy(
                        xmr = state.xmr.copy(revealedMnemonic = sampleMnemonic25),
                    )
                },
                onHideXmrSeed = {
                    state = state.copy(xmr = state.xmr.copy(revealedMnemonic = null))
                },
                onDismissNewXmrSeedBackup = {
                    state = state.copy(xmr = state.xmr.copy(newlyCreatedMnemonic = null))
                },
                onDeleteXmrWallet = {
                    deletedCount += 1
                    state = state.copy(xmr = MoneroUiState())
                },
            )
        }
        compose.waitForIdle()

        // 1. Expand Side Remote -> "Monero Wallet" shows "NEW" when no wallet exists.
        compose.onNodeWithTag("side_remote_collapsed_face").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("remote_monero_wallet").assertIsDisplayed()
        compose.onNodeWithTag("remote_btc_xmr_swap").assertIsDisplayed()
        compose.onNodeWithText("NEW").assertIsDisplayed()

        compose.onNodeWithTag("remote_monero_wallet").performClick()
        compose.waitForIdle()

        // 2. Empty wallet state shows truthful "No Wallet Created" + Create / Restore controls.
        compose.onNodeWithTag("xmr_wallet_sheet").assertIsDisplayed()
        compose.onNodeWithTag("xmr_empty_wallet_title").assertTextEquals("No Wallet Created")
        compose.onNodeWithTag("xmr_create_label_input").performTextReplacement("Whistleblower Vault")
        compose.onNodeWithTag("xmr_create_wallet_button").performClick()
        compose.waitForIdle()

        assertEquals("Whistleblower Vault", createdLabel)
        compose.onNodeWithTag("xmr_new_seed_backup_box").assertIsDisplayed()
        compose.onNodeWithTag("xmr_copy_seed_button").performClick()
        assertEquals(sampleMnemonic25, copiedValues.last())
        compose.onNodeWithTag("xmr_dismiss_new_seed_button").performClick()
        compose.waitForIdle()

        // 3. Receive tab: sync Tor height, copy 8... subaddress, mint new subaddress.
        compose.onNodeWithTag("xmr_unlocked_balance_text").assertTextEquals("1.2500 XMR")
        compose.onNodeWithTag("xmr_sync_button").performClick()
        compose.waitForIdle()
        assertEquals(1, syncedCount)
        compose.onNodeWithTag("xmr_sync_height_text").assertTextEquals("Synced 3809612 / 3809612 (.onion)")

        compose.onNodeWithTag("xmr_subaddress_label_input").performTextInput("Drop #2")
        compose.onNodeWithTag("xmr_new_subaddress_button").performClick()
        compose.waitForIdle()
        assertEquals("Drop #2", mintedSubLabel)
        compose.onNodeWithTag("xmr_copy_subaddress_button").performClick()
        assertEquals(subaddressSwap, copiedValues.last())

        // 4. Send tab: paste monero: URI into destination field -> auto-parses address & amount -> broadcast.
        compose.onNodeWithTag("xmr_tab_send").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("xmr_send_address_input")
            .performTextInput("monero:$primaryAddress?tx_amount=0.42")
        compose.waitForIdle()
        compose.onNodeWithTag("xmr_send_submit_button").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(primaryAddress, sentDest)
        assertEquals("0.42", sentAmount)
        assertFalse(sentSweep)
        compose.onNodeWithTag("xmr_send_receipt_box").performScrollTo().assertIsDisplayed()

        // 5. Node / Seed tab: save custom LWS endpoint, reveal seed, and 2-tap Delete Wallet.
        compose.onNodeWithTag("xmr_tab_node_seed").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("xmr_mode_lws").performClick()
        compose.onNodeWithTag("xmr_daemon_endpoint_input")
            .performTextReplacement("http://selfhostedlws222222222222222222222222222222222222222222d.onion:8080")
        compose.onNodeWithTag("xmr_save_daemon_button").performClick()
        assertEquals("lws", savedMode)
        assertTrue(savedEndpoint.endsWith(".onion:8080"))

        compose.onNodeWithTag("xmr_reveal_seed_button").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("xmr_revealed_seed_text").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("xmr_delete_wallet_button").performScrollTo().performClick()
        assertEquals("First tap must only arm wallet deletion", 0, deletedCount)
        compose.onNodeWithTag("xmr_delete_wallet_button").performScrollTo().performClick()
        assertEquals(1, deletedCount)
    }

    @Test
    fun swap_quotes_browser_launch_in_page_subaddress_pill_btc_deposit_hud_and_monero_uri_flow() {
        var fetchedAmount = ""
        var navigatedUrl = ""
        var fieldActionNode: PgpFieldState? = null
        var hudSyncClicks = 0
        val copied = mutableListOf<String>()

        val sampleQuote = OvNative.BtcSwapQuoteItem(
            id = "wizardswap-1",
            provider = "WizardSwap",
            estimatedXmr = "1.23907791",
            rateXmrPerBtc = "123.907791",
            spreadTag = "-0.40%",
            neverKyc = true,
            isOnionUrl = true,
            launchUrl = wizardOnionUrl,
        )

        var state by mutableStateOf(
            BrowserUiState(
                url = "about:blank",
                activeTabId = "tab-1",
                tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
            ),
        )

        compose.setContent {
            BrowserScreen(
                state = state,
                surface = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF101215)),
                    )
                },
                onNavigate = { target ->
                    navigatedUrl = target
                    state = state.copy(url = target)
                },
                onCopyText = { copied += it },
                onOpenBtcXmrSwap = {
                    state = state.copy(xmr = state.xmr.copy(swapSheetOpen = true))
                },
                onDismissBtcXmrSwap = {
                    state = state.copy(xmr = state.xmr.copy(swapSheetOpen = false))
                },
                onFetchBtcSwapQuotes = { amt ->
                    fetchedAmount = amt
                    state = state.copy(
                        xmr = state.xmr.copy(
                            quotesBtcAmount = amt,
                            quotesHasFetched = true,
                            quotes = listOf(sampleQuote),
                        ),
                    )
                },
                onLaunchBtcSwapQuoteInBrowser = { quote, amt ->
                    val swap = OvNative.TrackedBtcXmrSwap(
                        swapId = "swap-live-1",
                        provider = quote.provider,
                        btcAmount = amt,
                        quotedXmr = quote.estimatedXmr,
                        launchUrl = quote.launchUrl,
                        subaddressIndex = 1,
                        subaddress = subaddressSwap,
                        status = "awaiting_form",
                    )
                    navigatedUrl = quote.launchUrl
                    state = state.copy(
                        url = quote.launchUrl,
                        pgp = PgpOverlayState(
                            fields = listOf(
                                PgpFieldState(
                                    nodeId = "xmr-dest-1",
                                    kind = "xmr_address_input",
                                    label = "Insert XMR Subaddress",
                                    bounds = NormalizedBounds(0.08f, 0.24f, 0.92f, 0.31f),
                                    cdpNodeId = 41,
                                ),
                            ),
                        ),
                        xmr = state.xmr.copy(
                            swapSheetOpen = false,
                            status = OvNative.XmrStatusSnapshot(
                                hasWallet = true,
                                primaryAddress = primaryAddress,
                                activeSubaddress = subaddressSwap,
                                activeSubaddressIndex = 1,
                                unlockedBalanceXmr = "0",
                                swaps = listOf(swap),
                            ),
                            activeSwapTabMap = mapOf("tab-1" to swap.swapId),
                        ),
                    )
                },
                onFieldAction = { field ->
                    fieldActionNode = field
                    when (field.kind) {
                        "btc_deposit_address" -> copied += (field.armored ?: field.preview)
                        "monero_uri" -> {
                            val req = OvNative.parseMoneroUri(field.armored ?: field.preview)
                            if (req != null) {
                                state = state.copy(
                                    xmr = state.xmr.copy(
                                        walletSheetOpen = true,
                                        walletInitialTab = MoneroWalletTab.Send,
                                        prefillSendAddress = req.address,
                                        prefillSendAmount = req.amountXmr,
                                    ),
                                )
                            }
                        }
                    }
                },
                onSyncXmrWallet = { hudSyncClicks += 1 },
                onDismissActiveSwapHud = { swapId ->
                    state = state.copy(
                        xmr = state.xmr.copy(
                            dismissedHudSwapIds = state.xmr.dismissedHudSwapIds + swapId,
                        ),
                    )
                },
            )
        }
        compose.waitForIdle()

        // 1. Open Side Remote -> "Swap BTC -> XMR" -> truthful empty prompt before quotes are fetched.
        compose.onNodeWithTag("side_remote_collapsed_face").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("remote_btc_xmr_swap").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("btc_xmr_swap_sheet").assertIsDisplayed()
        compose.onNodeWithTag("swap_quotes_empty_text")
            .assertTextEquals("Enter BTC amount & tap Fetch Tor Quotes")

        // 2. Fetch live quotes -> verify quote card & launch into browser tab!
        compose.onNodeWithTag("swap_btc_amount_input").performTextReplacement("0.02")
        compose.onNodeWithTag("swap_fetch_quotes_button").performClick()
        compose.waitForIdle()
        assertEquals("0.02", fetchedAmount)
        compose.onNodeWithTag("swap_quote_card_wizardswap-1").assertIsDisplayed()
        compose.onNodeWithText(".ONION·NO-KYC").assertIsDisplayed()

        compose.onNodeWithTag("swap_launch_wizardswap-1").performClick()
        compose.waitForIdle()

        // 3. Modal closes, browser navigates to swap .onion URL, and both "Insert XMR Subaddress" pill
        //    AND ActiveSwapBrowserHud are rendered on the swap page!
        assertEquals(wizardOnionUrl, navigatedUrl)
        compose.onNodeWithTag("btc_xmr_swap_sheet").assertDoesNotExist()
        compose.onNodeWithTag("xmr_insert_subaddress_pill").assertIsDisplayed().performClick()
        assertEquals("xmr-dest-1", fieldActionNode?.nodeId)

        compose.onNodeWithTag("active_swap_hud").assertIsDisplayed()
        compose.onNodeWithTag("active_swap_copy_subaddress").performClick()
        assertEquals(subaddressSwap, copied.last())

        // 4. Simulate order page detection (`btc_deposit_address` + `monero_uri`) -> HUD switches to BTC deposit mode!
        val depositBtcAddr = "bc1qwxcndrucjwn7f4u2vrq2lx0hv543sjz57e77q2"
        val updatedSwap = state.xmr.status.swaps.first().copy(
            btcDepositAddress = depositBtcAddr,
            btcDepositAmount = "0.02",
            orderUrl = "http://wizardswgtu2ovor7r2esg3cxdpt7tv4nrugi32lldv53zmtonbz6sid.onion/id=3X314OQK",
            status = "awaiting_btc_deposit",
        )
        state = state.copy(
            pgp = PgpOverlayState(
                fields = listOf(
                    PgpFieldState(
                        nodeId = "btc-dep-1",
                        kind = "btc_deposit_address",
                        label = "Copy BTC Deposit",
                        bounds = NormalizedBounds(0.08f, 0.22f, 0.92f, 0.30f),
                        preview = depositBtcAddr,
                        armored = depositBtcAddr,
                        fingerprintHint = "0.02",
                        cdpNodeId = 55,
                    ),
                    PgpFieldState(
                        nodeId = "xmr-uri-1",
                        kind = "monero_uri",
                        label = "Pay XMR",
                        bounds = NormalizedBounds(0.08f, 0.42f, 0.92f, 0.48f),
                        preview = "monero:$primaryAddress?tx_amount=0.77",
                        armored = "monero:$primaryAddress?tx_amount=0.77",
                        cdpNodeId = 56,
                    ),
                ),
            ),
            xmr = state.xmr.copy(
                status = state.xmr.status.copy(swaps = listOf(updatedSwap)),
            ),
        )
        compose.waitForIdle()

        compose.onNodeWithTag("btc_copy_deposit_pill").assertIsDisplayed().performClick()
        assertEquals(depositBtcAddr, copied.last())

        compose.onNodeWithTag("active_swap_copy_btc_addr").performClick()
        assertEquals(depositBtcAddr, copied.last())
        compose.onNodeWithTag("active_swap_copy_btc_amt").performClick()
        assertEquals("0.02", copied.last())
        compose.onNodeWithTag("active_swap_sync_button").performClick()
        assertEquals(1, hudSyncClicks)

        // Dismiss HUD cleanly
        compose.onNodeWithTag("active_swap_hud_dismiss").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("active_swap_hud").assertDoesNotExist()

        // 5. Clicking "Pay XMR" overlay pill opens MoneroWalletHardwarePod on Send tab pre-filled!
        compose.onNodeWithTag("xmr_pay_uri_pill").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("xmr_wallet_sheet").assertIsDisplayed()
        compose.onNodeWithTag("xmr_send_address_input").assertTextEquals(primaryAddress)
        compose.onNodeWithTag("xmr_send_amount_input").assertTextEquals("0.77")
    }

    @Test
    fun tab_session_manager_intercepts_monero_uris_and_internal_wallet_swap_routes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var interceptedPayment: OvNative.MoneroPaymentRequest? = null
        val interceptedRoutes = mutableListOf<String>()
        var blockedReason = ""

        val manager = TabSessionManager(
            context = context,
            cdpController = CdpDomPgpController(),
            backStackManager = OnionBackStackManager(),
            onUrlBlocked = { _, _, reason -> blockedReason = reason },
            onMoneroUriIntercepted = { req -> interceptedPayment = req },
            onInternalRouteIntercepted = { route ->
                interceptedRoutes += route
                true
            },
        )
        val tabId = manager.ensureInitialTab("about:blank")

        // 1. normalizeAddressOrSearch preserves monero: and about:wallet / about:swap without DDG search rewrite.
        val moneroUri = "monero:$subaddressOne?tx_amount=1.25&tx_description=Tip"
        assertEquals(moneroUri, TabSessionManager.normalizeAddressOrSearch(moneroUri))
        assertEquals("about:wallet", TabSessionManager.normalizeAddressOrSearch("about:wallet"))
        assertEquals("about:swap", TabSessionManager.normalizeAddressOrSearch("about:swap"))

        // 2. loadUrl intercepts valid monero: URI and invokes onMoneroUriIntercepted without navigating away.
        val decision = manager.loadUrl(moneroUri, tabId)
        assertTrue(decision.allowed)
        assertNotNull(interceptedPayment)
        assertEquals(subaddressOne, interceptedPayment?.address)
        assertEquals("1.25", interceptedPayment?.amountXmr)
        assertEquals("Tip", interceptedPayment?.txDescription)
        assertEquals("about:blank", manager.activeTab()?.url)

        // 3. Invalid monero: URI is rejected fail-closed.
        val badDecision = manager.loadUrl("monero:invalid_short_addr", tabId)
        assertFalse(badDecision.allowed)
        assertEquals("Invalid Monero payment URI", blockedReason)

        // 4. about:wallet and about:swap trigger internal modal route interception.
        assertTrue(manager.loadUrl("about:wallet", tabId).allowed)
        assertTrue(manager.loadUrl("about:swap", tabId).allowed)
        assertEquals(listOf("about:wallet", "about:swap"), interceptedRoutes)
    }
}
