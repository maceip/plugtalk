package dev.onionvault.ui.pods

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import dev.onionvault.OvNative
import dev.onionvault.ui.BrowserUiState
import dev.onionvault.ui.ClearnetPromptState
import dev.onionvault.ui.EncryptRequestState
import dev.onionvault.ui.KeyInfo
import dev.onionvault.ui.NormalizedBounds
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.PgpOverlayState
import dev.onionvault.ui.StartPage
import dev.onionvault.ui.TabItemState
import dev.onionvault.ui.TorBootstrapState
import dev.onionvault.ui.TrapTheme
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Native-Skia Visual Screenshot + Hit-Test Passthrough + Gesture + BackHandler
 * Verification Suite for Trap's `1.5x` Scaled 1997 MetaTools / Kai's Power Tools Hardware Pods
 * (`KptBackButtonPod`, `RecessedCrtAddressPod`, `TorKnurledOrbPod`, `OpenPgpRockerPod`,
 * `PhotoSoapSideRemote`, `PerimeterSteelRails`) and `TRAP` Street-Zine Homepage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MetaToolsPodsRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val sampleOwnKey = KeyInfo(
        fingerprint = "A3D9071F5C2E8B40D16F7394C8E5A2B6F04D71C9",
        userId = "Source <source@onion>",
        hasSecret = true,
        canSign = true,
        canEncrypt = true,
        isOwnKey = true,
    )

    private val sampleDeskKey = KeyInfo(
        fingerprint = "6F1A2C939B4E77D10E585A623F8B91C47D0A11E2",
        userId = "Newsroom Desk <desk@securedrop.onion>",
        hasSecret = false,
        canSign = false,
        canEncrypt = true,
        isOwnKey = false,
    )

    private val ddgOnionUrl =
        "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/"

    // ========================================================================
    // 1. Visual Screenshot Captures (build/screenshots/metatools-*.png)
    // ========================================================================

    @Test
    fun render_0_trap_street_zine_homepage_with_15x_kpt_pods() {
        val state = BrowserUiState(
            url = "about:blank",
            title = "Trap",
            canGoBack = false,
            tor = TorBootstrapState(
                percent = 100,
                stage = "ready",
                ready = true,
                socksAddr = "127.0.0.1:41520",
            ),
            keys = listOf(sampleOwnKey, sampleDeskKey),
            defaultKeyFingerprint = sampleOwnKey.fingerprint,
        )

        capture("metatools-0-trap-street-zine-homepage") {
            TrapTheme {
                MetaToolsBrowserShell(
                    state = state,
                    surface = {
                        StartPage(
                            state = state,
                            onOpenSecurity = {},
                        )
                    },
                )
            }
        }
    }

    @Test
    fun render_1_resting_tripod_and_collapsed_side_remote() {
        val state = BrowserUiState(
            url = "http://dread7x.onion/",
            title = "Dread",
            canGoBack = true,
            canGoForward = true,
            tor = TorBootstrapState(
                percent = 100,
                stage = "ready",
                ready = true,
                socksAddr = "127.0.0.1:41520",
                isolationToken = "iso-dread7x",
                isolatedGroupCount = 2,
            ),
            keys = listOf(sampleOwnKey, sampleDeskKey),
            defaultKeyFingerprint = sampleOwnKey.fingerprint,
            pgp = PgpOverlayState(
                fields = listOf(
                    PgpFieldState(
                        nodeId = "pgp-1",
                        kind = "pgp_message",
                        label = "Decrypt Block",
                        bounds = NormalizedBounds(0.08f, 0.35f, 0.92f, 0.60f),
                        armored = "-----BEGIN PGP MESSAGE-----\n...\n-----END PGP MESSAGE-----",
                    ),
                ),
            ),
        )

        capture("metatools-1-resting-tripod") {
            TrapTheme {
                MetaToolsBrowserShell(
                    state = state,
                    surface = { SampleStreetZineWebpageSurface() },
                )
            }
        }
    }

    @Test
    fun render_2a_expanded_side_remote_menu() {
        val state = BrowserUiState(
            url = ddgOnionUrl,
            title = "DuckDuckGo",
            canGoBack = true,
            canGoForward = false,
            tor = TorBootstrapState(
                percent = 100,
                stage = "ready",
                ready = true,
                socksAddr = "127.0.0.1:41950",
                isolationToken = "iso-duckduckgogg42xj",
                isolatedGroupCount = 1,
            ),
            keys = listOf(sampleOwnKey),
            defaultKeyFingerprint = sampleOwnKey.fingerprint,
        )

        capture("metatools-2a-expanded-remote-menu") {
            TrapTheme {
                MetaToolsBrowserShell(
                    state = state,
                    initialRemoteExpanded = true,
                    initialRemoteMode = RemoteMode.Menu,
                    surface = { SampleStreetZineWebpageSurface() },
                )
            }
        }
    }

    @Test
    fun render_2b_expanded_side_remote_tor_circuit() {
        val state = BrowserUiState(
            url = ddgOnionUrl,
            title = "DuckDuckGo",
            canGoBack = true,
            tor = TorBootstrapState(
                percent = 100,
                stage = "ready",
                ready = true,
                socksAddr = "127.0.0.1:41950",
                isolationToken = "iso-duckduckgogg42xj",
                isolatedGroupCount = 1,
            ),
            keys = listOf(sampleOwnKey),
            defaultKeyFingerprint = sampleOwnKey.fingerprint,
            clearnetRules = listOf(
                OvNative.ClearnetRuleItem(
                    pattern = "*.tor.taxi",
                    createdAtMs = System.currentTimeMillis(),
                    expiresAtMs = System.currentTimeMillis() + 259_200_000L,
                    sessionOnly = false,
                ),
            ),
        )

        capture("metatools-2b-expanded-remote-tor-circuit") {
            TrapTheme {
                MetaToolsBrowserShell(
                    state = state,
                    initialRemoteExpanded = true,
                    initialRemoteMode = RemoteMode.TorCircuit,
                    surface = { SampleStreetZineWebpageSurface() },
                )
            }
        }
    }

    @Test
    fun render_3_focused_crt_capsule_docked_above_ime() {
        val state = BrowserUiState(
            url = ddgOnionUrl,
            tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
        )

        compose.setContent {
            TrapTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    MetaToolsBrowserShell(
                        state = state,
                        surface = { SampleStreetZineWebpageSurface() },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("crt_address_pod").performClick()
        compose.waitForIdle()
        saveDecorScreenshot("metatools-3-focused-crt")
    }

    @Test
    fun render_4_retracted_scroll_mode() {
        val state = BrowserUiState(
            url = ddgOnionUrl,
            isLoading = true,
            loadProgress = 64,
            canGoBack = true,
            tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
            keys = listOf(sampleOwnKey),
            defaultKeyFingerprint = sampleOwnKey.fingerprint,
        )

        capture("metatools-4-retracted-scroll") {
            TrapTheme {
                MetaToolsBrowserShell(
                    state = state,
                    podsRetracted = true,
                    surface = { SampleStreetZineWebpageSurface() },
                )
            }
        }
    }

    @Test
    fun render_5_clearnet_consent_hardware_pod() {
        val state = BrowserUiState(
            url = "about:blank",
            tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
            clearnetPrompt = ClearnetPromptState(
                host = "tor.taxi",
                pendingUrl = "https://tor.taxi",
                suggestedPattern = "*.tor.taxi",
            ),
        )

        capture("metatools-5-clearnet-consent") {
            TrapTheme {
                MetaToolsBrowserShell(
                    state = state,
                    surface = { SampleStreetZineWebpageSurface() },
                )
            }
        }
    }

    // ========================================================================
    // 2. 1.5x Bounds, KPT Back Pod, Hit-Test Passthrough, Gesture & Dock Assertions
    // ========================================================================

    @Test
    fun hit_test_passthrough_15x_bounds_kpt_back_pod_and_right_bezel_dock_work() {
        var webpageTaps by mutableIntStateOf(0)
        var backCalls by mutableIntStateOf(0)
        var forwardCalls by mutableIntStateOf(0)
        var homeCalls by mutableIntStateOf(0)
        var state by mutableStateOf(
            BrowserUiState(
                url = "about:blank",
                canGoBack = false,
                canGoForward = false,
                tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
            ),
        )

        compose.setContent {
            TrapTheme {
                MetaToolsBrowserShell(
                    state = state,
                    onBack = { backCalls += 1 },
                    onForward = { forwardCalls += 1 },
                    onHome = {
                        homeCalls += 1
                        state = state.copy(url = "about:blank", canGoBack = false)
                    },
                    surface = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF0E1013))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { webpageTaps += 1 },
                                )
                                .testTag("webpage_hit_surface"),
                        )
                    },
                )
            }
        }
        compose.waitForIdle()

        // 1. Verify 1.5x Hardware Pod Sizes & Browser-First Ergonomic Arrangement on StartPage:
        //    Bottom Row: [ KptBackButtonPod (74dp) ] (8dp gap) [ RecessedCrtAddressPod (74dp tall) ] (8dp gap) [ TorKnurledOrbPod (86dp) ]
        //    Right Dock: PhotoSoapSideRemote (70x154dp) above OpenPgpRockerPod (80x92dp) above TorKnurledOrbPod (86dp)
        val shellBounds = compose.onNodeWithTag("metatools_browser_shell").getUnclippedBoundsInRoot()
        val backBounds = compose.onNodeWithTag("kpt_back_pod").getUnclippedBoundsInRoot()
        val crtBounds = compose.onNodeWithTag("crt_address_pod").getUnclippedBoundsInRoot()
        val orbBounds = compose.onNodeWithTag("tor_orb_pod").getUnclippedBoundsInRoot()
        val pgpBounds = compose.onNodeWithTag("pgp_rocker_pod").getUnclippedBoundsInRoot()
        val remoteBounds = compose.onNodeWithTag("side_remote_pod").getUnclippedBoundsInRoot()

        assertTrue("Expected KptBackButtonPod >= 72.dp, got ${backBounds.width} x ${backBounds.height}", backBounds.width >= 72.dp && backBounds.height >= 72.dp)
        assertTrue("Expected RecessedCrtAddressPod height >= 70.dp, got ${crtBounds.height}", crtBounds.height >= 70.dp)
        assertTrue("Expected TorKnurledOrbPod >= 84.dp, got ${orbBounds.width} x ${orbBounds.height}", orbBounds.width >= 84.dp && orbBounds.height >= 84.dp)
        assertTrue("Expected OpenPgpRockerPod >= 78x90.dp, got ${pgpBounds.width} x ${pgpBounds.height}", pgpBounds.width >= 78.dp && pgpBounds.height >= 90.dp)
        assertTrue("Expected PhotoSoapSideRemote >= 68x150.dp, got ${remoteBounds.width} x ${remoteBounds.height}", remoteBounds.width >= 68.dp && remoteBounds.height >= 150.dp)

        // Confirm explicit open webpage gaps (>= 7.5dp) between Back -> CRT -> Tor Orb
        val leftGapWidth = crtBounds.left - backBounds.right
        val rightGapWidth = orbBounds.left - crtBounds.right
        assertTrue("Expected >= 7.5dp open gap between Back Pod & CRT, got $leftGapWidth", leftGapWidth >= 7.5.dp)
        assertTrue("Expected >= 7.5dp open gap between CRT & Tor Orb, got $rightGapWidth", rightGapWidth >= 7.5.dp)

        // Confirm zero vertical collision along right-bezel dock on StartPage
        assertTrue("Expected OpenPgpRockerPod above TorKnurledOrbPod, pgp.bottom=${pgpBounds.bottom} orb.top=${orbBounds.top}", orbBounds.top - pgpBounds.bottom >= 10.dp)
        assertTrue("Expected PhotoSoapSideRemote above OpenPgpRockerPod, remote.bottom=${remoteBounds.bottom} pgp.top=${pgpBounds.top}", pgpBounds.top - remoteBounds.bottom >= 10.dp)
        assertTrue("Expected right-dock pods 100% out on StartPage", remoteBounds.right <= shellBounds.right)

        // 2. On StartPage (canGoBack == false && isStartPage == true), tapping kpt_back_pod is a no-op
        compose.onNodeWithTag("kpt_back_pod").performClick()
        compose.waitForIdle()
        assertEquals(0, backCalls)
        assertEquals(0, homeCalls)

        // 3. Navigate to a first-hop directory page without PGP (canGoBack == false, isStartPage == false):
        //    - Right-bezel pods auto-tuck ~72% into the right bezel!
        //    - Tapping kpt_back_pod cleanly returns to StartPage via onHome!
        state = state.copy(url = "http://dread7x.onion/", canGoBack = false, canGoForward = true)
        compose.waitForIdle()

        val tuckedRemoteBounds = compose.onNodeWithTag("side_remote_pod").getUnclippedBoundsInRoot()
        val tuckedPgpBounds = compose.onNodeWithTag("pgp_rocker_pod").getUnclippedBoundsInRoot()
        assertTrue(
            "Expected Side Remote to auto-tuck >= 45dp into right bezel on live webpage, delta=${tuckedRemoteBounds.left - remoteBounds.left}",
            tuckedRemoteBounds.left - remoteBounds.left >= 45.dp,
        )
        assertTrue(
            "Expected PGP Rocker to auto-tuck >= 50dp into right bezel when no PGP on webpage, delta=${tuckedPgpBounds.left - pgpBounds.left}",
            tuckedPgpBounds.left - pgpBounds.left >= 50.dp,
        )

        // When PGP content appears on webpage, OpenPgpRockerPod automatically slides out to 100%!
        state = state.copy(
            pgp = PgpOverlayState(
                fields = listOf(
                    PgpFieldState(
                        nodeId = "pgp-live",
                        kind = "pgp_message",
                        label = "Decrypt Block",
                        bounds = NormalizedBounds(0.1f, 0.3f, 0.9f, 0.5f),
                        armored = "-----BEGIN PGP MESSAGE-----\n...\n-----END PGP MESSAGE-----",
                    ),
                ),
            ),
        )
        compose.waitForIdle()
        val activePgpBounds = compose.onNodeWithTag("pgp_rocker_pod").getUnclippedBoundsInRoot()
        assertTrue(
            "Expected OpenPgpRockerPod to slide out to 100% when webpage has PGP content",
            abs((activePgpBounds.left - pgpBounds.left).value) <= 2f,
        )

        // Long-press (400ms) on kpt_back_pod triggers Forward!
        compose.onNodeWithTag("kpt_back_pod").performTouchInput {
            down(center)
            advanceEventTime(420L)
        }
        compose.mainClock.advanceTimeBy(450L)
        compose.onNodeWithTag("kpt_back_pod").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, forwardCalls)

        // Tap on kpt_back_pod when canGoBack == true calls onBack()
        state = state.copy(canGoBack = true)
        compose.waitForIdle()
        compose.onNodeWithTag("kpt_back_pod").performClick()
        compose.waitForIdle()
        assertEquals(1, backCalls)

        // Tap on kpt_back_pod on first-hop page (canGoBack == false, !isStartPage) returns to StartPage!
        state = state.copy(canGoBack = false)
        compose.waitForIdle()
        compose.onNodeWithTag("kpt_back_pod").performClick()
        compose.waitForIdle()
        assertEquals(1, homeCalls)
        assertEquals("about:blank", state.url)

        // 4. Hit-Test Passthrough: rails, open gaps, and cast shadows pass straight through to webpage!
        // Tap directly on Left 4.5dp PerimeterSteelRail + spray splatter (x = 2.5dp, y = 26% height)
        compose.onNodeWithTag("metatools_browser_shell").performTouchInput {
            click(Offset(2.5.dp.toPx(), visibleSize.height * 0.26f))
        }
        compose.waitForIdle()
        assertEquals("Left steel rail & splatter must pass touch through to webpage", 1, webpageTaps)

        // Tap directly in open gap between KptBackButtonPod and RecessedCrtAddressPod
        val gap1X = ((backBounds.right.value + crtBounds.left.value) / 2f).dp
        val podMidY = ((crtBounds.top.value + crtBounds.bottom.value) / 2f).dp
        compose.onNodeWithTag("metatools_browser_shell").performTouchInput {
            click(Offset(gap1X.toPx(), podMidY.toPx()))
        }
        compose.waitForIdle()
        assertEquals("Open gap between Back Pod and CRT must pass touch to webpage", 2, webpageTaps)

        // Tap directly in open gap between RecessedCrtAddressPod and TorKnurledOrbPod
        val gap2X = ((crtBounds.right.value + orbBounds.left.value) / 2f).dp
        compose.onNodeWithTag("metatools_browser_shell").performTouchInput {
            click(Offset(gap2X.toPx(), podMidY.toPx()))
        }
        compose.waitForIdle()
        assertEquals("Open gap between CRT and Tor Orb must pass touch to webpage", 3, webpageTaps)

        // Tap directly on +10dp/+16dp cast drop shadow beneath CRT monitor
        val shadowX = ((crtBounds.left.value + crtBounds.right.value) / 2f + 10f).dp
        val shadowY = (crtBounds.bottom.value + 5f).coerceAtMost(shellBounds.bottom.value - 2f).dp
        compose.onNodeWithTag("metatools_browser_shell").performTouchInput {
            click(Offset(shadowX.toPx(), shadowY.toPx()))
        }
        compose.waitForIdle()
        assertEquals("Cast drop shadow outside pod body must pass touch to webpage", 4, webpageTaps)

        // Tapping physical TorKnurledOrbPod opens TorCircuit remote (268dp wide) without leaking to webpage
        compose.onNodeWithTag("tor_orb_pod").performClick()
        compose.waitForIdle()
        assertEquals("Tap on physical Tor Orb body must not leak to webpage", 4, webpageTaps)
        compose.onNodeWithTag("remote_tor_circuit").assertIsDisplayed()
        val expandedRemoteBounds = compose.onNodeWithTag("side_remote_pod").getUnclippedBoundsInRoot()
        assertTrue("Expected expanded Side Remote >= 260.dp wide, got ${expandedRemoteBounds.width}", expandedRemoteBounds.width >= 260.dp)
    }

    @Test
    fun crt_capsule_supports_focus_expansion_paste_clear_ime_collapse_swipe_tabs_and_pull_up_switcher() {
        val switchedDeltas = mutableListOf<Int>()
        var selectedTab = "tab-1"
        var navigatedUrl = ""
        var imeOpen by mutableStateOf(false)

        val state by mutableStateOf(
            BrowserUiState(
                url = ddgOnionUrl,
                tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
                tabs = listOf(
                    TabItemState(id = "tab-1", title = "DuckDuckGo", url = ddgOnionUrl, isActive = true, isOnion = true),
                    TabItemState(id = "tab-2", title = "ProPublica", url = "https://p53lf57qovyuvwsc6xnrppyply3vtqm7l6pcobkmyqsiofyeznfu5uqd.onion/", isActive = false, isOnion = true),
                ),
                activeTabId = "tab-1",
            ),
        )

        compose.setContent {
            val clipboard = LocalClipboardManager.current
            clipboard.setText(AnnotatedString("http://dread7x.onion/"))
            TrapTheme {
                MetaToolsBrowserShell(
                    state = state,
                    imeVisibleOverride = imeOpen,
                    onNavigate = { navigatedUrl = it },
                    onSwitchAdjacentTab = { switchedDeltas += it },
                    onSelectTab = { selectedTab = it },
                )
            }
        }
        compose.waitForIdle()

        assertEquals("duckduck…zczad.onion", truncateOnionPreservingTail("duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"))
        compose.onNodeWithText("duckduck…zczad.onion").assertIsDisplayed()

        // 1. Horizontal swipe left -> switches to next tab (+1 -> tab-2)
        compose.onNodeWithTag("crt_address_pod").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf(+1), switchedDeltas)
        assertEquals("tab-2", selectedTab)

        // 2. Horizontal swipe right -> switches to previous tab (-1)
        compose.onNodeWithTag("crt_address_pod").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(listOf(+1, -1), switchedDeltas)

        // 3. Pull upward (dy < -24dp) -> opens Spatial Tab Switcher Pod, BackHandler dismisses it!
        compose.onNodeWithTag("crt_address_pod").performTouchInput {
            down(center)
            moveBy(Offset(0f, -40.dp.toPx()))
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tabs_sheet").assertIsDisplayed()

        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tabs_sheet").assertDoesNotExist()

        // 4. Tap CRT -> expands to 95% width (~390dp on 411dp screen, 68dp tall), hides Back, Orb, PGP & Remote!
        compose.onNodeWithTag("crt_address_pod").performClick()
        imeOpen = true
        compose.waitForIdle()

        compose.onNodeWithTag("kpt_back_pod").assertDoesNotExist()
        compose.onNodeWithTag("tor_orb_pod").assertDoesNotExist()
        compose.onNodeWithTag("pgp_rocker_pod").assertDoesNotExist()
        compose.onNodeWithTag("side_remote_pod").assertDoesNotExist()

        val focusedBounds = compose.onNodeWithTag("crt_address_pod").getUnclippedBoundsInRoot()
        assertTrue(
            "Expected focused CRT capsule >= 380dp (95% of 411dp), got ${focusedBounds.width}",
            focusedBounds.width >= 380.dp,
        )
        assertTrue(
            "Expected focused CRT capsule height >= 66dp, got ${focusedBounds.height}",
            focusedBounds.height >= 66.dp,
        )

        // [Clear] & [Paste] physical bezel keys + ImeAction.Go submission
        compose.onNodeWithTag("crt_clear_button").performClick()
        compose.onNodeWithTag("crt_paste_button").performClick()
        compose.onNodeWithTag("crt_address_input").performImeAction()
        imeOpen = false
        compose.waitForIdle()

        assertEquals("http://dread7x.onion/", navigatedUrl)
        compose.onNodeWithTag("kpt_back_pod").assertIsDisplayed()
        compose.onNodeWithTag("tor_orb_pod").assertIsDisplayed()
        compose.onNodeWithTag("pgp_rocker_pod").assertIsDisplayed()
        compose.onNodeWithTag("side_remote_pod").assertIsDisplayed()

        // 5. Auto-collapse when IME hides during editing
        compose.onNodeWithTag("crt_address_pod").performClick()
        imeOpen = true
        compose.waitForIdle()
        compose.onNodeWithTag("crt_address_input").assertIsDisplayed()

        imeOpen = false
        compose.waitForIdle()
        compose.onNodeWithTag("crt_address_input").assertDoesNotExist()
        compose.onNodeWithTag("tor_orb_pod").assertIsDisplayed()
    }

    @Test
    fun tor_orb_long_press_2_tap_identity_confirm_pgp_latch_drag_and_scroll_retraction_work() {
        var identityResets = 0
        var quickDecryptTriggered = 0
        var retractedState by mutableStateOf(false)

        compose.setContent {
            TrapTheme {
                MetaToolsBrowserShell(
                    state = BrowserUiState(
                        url = ddgOnionUrl,
                        canGoBack = true,
                        tor = TorBootstrapState(
                            percent = 100,
                            stage = "ready",
                            ready = true,
                            socksAddr = "127.0.0.1:41520",
                            isolationToken = "iso-ddg",
                        ),
                    ),
                    podsRetracted = retractedState,
                    onSetPodsRetracted = { retractedState = it },
                    clipboardTextOverride = "-----BEGIN PGP MESSAGE-----\nabc\n-----END PGP MESSAGE-----",
                    onResetTorIsolation = { identityResets += 1 },
                    onQuickVerifyOrDecrypt = { quickDecryptTriggered += 1 },
                )
            }
        }
        compose.waitForIdle()

        val restingCrtBounds = compose.onNodeWithTag("crt_address_pod").getUnclippedBoundsInRoot()
        val restingPgpBounds = compose.onNodeWithTag("pgp_rocker_pod").getUnclippedBoundsInRoot()

        // 1. Hold >= 400ms on TorKnurledOrbPod rotates Tor identity
        compose.onNodeWithTag("tor_orb_pod").performTouchInput {
            down(center)
            advanceEventTime(420L)
        }
        compose.mainClock.advanceTimeBy(450L)
        compose.onNodeWithTag("tor_orb_pod").performTouchInput {
            up()
        }
        compose.waitForIdle()
        assertEquals(1, identityResets)

        // 2. Tap Tor Orb -> opens TorCircuit remote; New Tor Identity requires 2-tap confirm!
        compose.onNodeWithTag("tor_orb_pod").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("remote_new_identity").performClick()
        assertEquals("First tap must only arm confirmation", 1, identityResets)
        compose.onNodeWithText("Confirm New ID?").assertIsDisplayed()
        compose.onNodeWithTag("remote_new_identity").performClick()
        assertEquals("Second tap must execute identity reset", 2, identityResets)

        // BackHandler collapses expanded Side Remote
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("remote_tor_circuit").assertDoesNotExist()

        // 3. Dragging OpenPGP Rocker aluminum latch upward (dy < -18dp) triggers quick verify/decrypt
        compose.onNodeWithTag("pgp_rocker_pod").performTouchInput {
            down(center)
            moveBy(Offset(0f, -26.dp.toPx()))
            up()
        }
        compose.waitForIdle()
        assertEquals(1, quickDecryptTriggered)

        // 4. Scroll retraction translates CRT +96dp Y and tucks PGP Rocker +58dp into right bezel;
        //    tapping a retracted pod immediately snaps all pods back!
        retractedState = true
        compose.waitForIdle()

        val retractedCrtBounds = compose.onNodeWithTag("crt_address_pod").getUnclippedBoundsInRoot()
        val retractedPgpBounds = compose.onNodeWithTag("pgp_rocker_pod").getUnclippedBoundsInRoot()
        assertTrue(
            "Expected CRT to translate +96dp Y when retracted, delta=${retractedCrtBounds.top - restingCrtBounds.top}",
            abs((retractedCrtBounds.top - restingCrtBounds.top).value - 96f) <= 2f,
        )
        assertTrue(
            "Expected PGP Rocker to tuck +58dp X off right edge when retracted, delta=${retractedPgpBounds.left - restingPgpBounds.left}",
            abs((retractedPgpBounds.left - restingPgpBounds.left).value - 58f) <= 2f,
        )

        compose.onNodeWithTag("tor_orb_pod").performClick()
        compose.waitForIdle()
        assertFalse("Tapping retracted pod must snap podsRetracted back to false", retractedState)
    }

    @Test
    fun encrypt_recipient_pod_never_auto_selects_own_key_and_supports_inline_key_import() {
        var keys by mutableStateOf(listOf(sampleOwnKey))
        var confirmedRecipient = ""

        compose.setContent {
            TrapTheme {
                MetaToolsBrowserShell(
                    state = BrowserUiState(
                        url = "http://sdolvtfhatvsysc6l34d65ymdwxcujausv7k5jk4cy5ttzhjoi6fzvyd.onion/",
                        keys = keys,
                        defaultKeyFingerprint = sampleOwnKey.fingerprint,
                        pgp = PgpOverlayState(
                            encryptRequest = EncryptRequestState(
                                nodeId = "cdp-5",
                                initialText = "Top secret whistleblower tip",
                                writeBackToField = true,
                            ),
                        ),
                    ),
                    onImportKey = { _, _ ->
                        keys = keys + sampleDeskKey
                    },
                    onConfirmEncrypt = { _, _, recipientFp, _, _ ->
                        confirmedRecipient = recipientFp
                    },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("encrypt_picker_sheet").assertIsDisplayed()
        compose.onNodeWithTag("encrypt_confirm_button").assertIsNotEnabled()

        compose.onNodeWithTag("encrypt_inline_import_input")
            .performTextInput("-----BEGIN PGP PUBLIC KEY BLOCK-----\ndesk\n-----END PGP PUBLIC KEY BLOCK-----")
        compose.onNodeWithTag("encrypt_inline_import_submit").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("encrypt_confirm_button").assertIsEnabled().performClick()
        assertEquals(sampleDeskKey.fingerprint, confirmedRecipient)
    }

    @Test
    fun clearnet_consent_pod_preserves_3_day_ttl_tags_and_back_handler_blocks() {
        val allows = mutableListOf<Pair<String, Long>>()
        val blocks = mutableListOf<String>()
        var prompt by mutableStateOf<ClearnetPromptState?>(
            ClearnetPromptState(
                host = "tor.taxi",
                pendingUrl = "https://tor.taxi",
                suggestedPattern = "*.tor.taxi",
            ),
        )

        compose.setContent {
            TrapTheme {
                MetaToolsBrowserShell(
                    state = BrowserUiState(clearnetPrompt = prompt),
                    onAllowClearnet = { _, pattern, ttl ->
                        allows += pattern to ttl
                        prompt = null
                    },
                    onBlockClearnet = { p ->
                        blocks += p.host
                        prompt = null
                    },
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("clearnet_consent_sheet").assertIsDisplayed()
        compose.onNodeWithTag("clearnet_allow_3d_button").performClick()
        compose.waitForIdle()
        assertEquals(listOf("*.tor.taxi" to 259_200L), allows)

        prompt = ClearnetPromptState(
            host = "tracker.example.net",
            pendingUrl = "https://tracker.example.net",
            suggestedPattern = "*.example.net",
        )
        compose.waitForIdle()
        compose.onNodeWithTag("clearnet_consent_sheet").assertIsDisplayed()
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertEquals(listOf("tracker.example.net"), blocks)
        compose.onNodeWithTag("clearnet_consent_sheet").assertDoesNotExist()
    }

    @Test
    fun bootstrapping_orb_context_remote_keyring_passphrase_plaintext_and_settings_pods_execute() {
        var generatedUid = ""
        var deletedFp = ""
        var unlockedPassphrase = ""
        var copiedPlaintext = ""
        var clearedSessionCount = 0
        var openedContextNewTab = ""

        var state by mutableStateOf(
            BrowserUiState(
                url = "https://tor.taxi/directory",
                isLoading = true,
                loadProgress = 42,
                tor = TorBootstrapState(percent = 45, stage = "loading_descriptors", ready = false),
                keys = listOf(sampleOwnKey),
                defaultKeyFingerprint = sampleOwnKey.fingerprint,
                contextMenu = dev.onionvault.ui.ContextMenuUiState(
                    tabId = "tab-1",
                    linkUrl = ddgOnionUrl,
                ),
            ),
        )

        compose.setContent {
            TrapTheme {
                MetaToolsBrowserShell(
                    state = state,
                    onGenerateKey = { uid, _ -> generatedUid = uid },
                    onDeleteKey = { fp -> deletedFp = fp },
                    onSubmitDecryptPassphrase = { _, pass -> unlockedPassphrase = pass },
                    onCopyPlaintext = { copiedPlaintext = it },
                    onClearSession = { clearedSessionCount += 1 },
                    onContextMenuOpenNewTab = { url ->
                        openedContextNewTab = url
                        state = state.copy(contextMenu = null)
                    },
                    onDismissContextMenu = { state = state.copy(contextMenu = null) },
                    onDismissPlaintext = { state = state.copy(pgp = state.pgp.copy(plaintext = null)) },
                )
            }
        }
        compose.waitForIdle()

        // 1. ContextMenu automatically expands Side Remote into RemoteMode.ContextMenu
        compose.onNodeWithTag("remote_context_menu").assertIsDisplayed()
        compose.onNodeWithTag("remote_ctx_open_new_tab").performClick()
        compose.waitForIdle()
        assertEquals(ddgOnionUrl, openedContextNewTab)

        // 2. Open PGP Keyring via Rocker tap -> Generate + 2-tap Delete confirm
        compose.onNodeWithTag("pgp_rocker_pod").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("keyring_sheet").assertIsDisplayed()
        compose.onNodeWithText("Generate").performClick()
        compose.onNodeWithTag("keygen_userid_input").performTextInput("Anon <anon@onion>")
        compose.onNodeWithTag("keygen_submit_button").performClick()
        compose.waitForIdle()
        assertEquals("Anon <anon@onion>", generatedUid)

        compose.onNodeWithTag("key_delete_${sampleOwnKey.fingerprint}").performClick()
        assertEquals("First tap on Delete must only arm confirmation", "", deletedFp)
        compose.onNodeWithTag("key_delete_${sampleOwnKey.fingerprint}").performClick()
        assertEquals(sampleOwnKey.fingerprint, deletedFp)
        compose.onNodeWithTag("sheet_close_button").performClick()
        compose.waitForIdle()

        // 3. PassphraseUnlockHardwarePod & DecryptedPlaintextHardwarePod
        state = state.copy(
            pgp = PgpOverlayState(
                decryptPassphraseRequest = dev.onionvault.ui.DecryptPassphraseRequestState(
                    nodeId = "cdp-msg",
                    armoredCiphertext = "-----BEGIN PGP MESSAGE-----",
                ),
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("decrypt_passphrase_sheet").assertIsDisplayed()
        compose.onNodeWithTag("decrypt_passphrase_input").performTextInput("hunter2-pass")
        compose.onNodeWithTag("decrypt_passphrase_submit").performClick()
        assertEquals("hunter2-pass", unlockedPassphrase)

        state = state.copy(
            pgp = PgpOverlayState(
                plaintext = dev.onionvault.ui.PgpPlaintextState(
                    nodeId = "cdp-msg",
                    title = "Verified Drop",
                    body = "Meet at relay 7",
                    signatureVerified = true,
                    signatureStatus = "good",
                    signerFingerprint = sampleDeskKey.fingerprint,
                    signerUserId = sampleDeskKey.userId,
                ),
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("plaintext_sheet").assertIsDisplayed()
        compose.onNodeWithTag("plaintext_copy").performClick()
        assertEquals("Meet at relay 7", copiedPlaintext)
        compose.onNodeWithTag("plaintext_close").performClick()
        compose.waitForIdle()

        // 4. Expand Side Remote -> Settings -> 2-tap Clear Session confirm
        compose.onNodeWithTag("side_remote_collapsed_face").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("remote_settings").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings_sheet").assertIsDisplayed()
        compose.onNodeWithTag("settings_clear_session").performScrollTo().performClick()
        assertEquals(0, clearedSessionCount)
        compose.onNodeWithTag("settings_clear_session").performScrollTo().performClick()
        assertEquals(1, clearedSessionCount)
    }

    // ========================================================================
    // Sample Webpage Canvas & Robolectric Screenshot Helpers
    // ========================================================================

    @Composable
    private fun SampleStreetZineWebpageSurface() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101215))
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1D22))
                    .border(1.dp, Color(0xFFD8FF00))
                    .padding(14.dp),
            ) {
                Column {
                    Text(
                        text = "TRAP // HIDDEN SERVICE DISPATCH",
                        color = Color(0xFFD8FF00),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Zero-JS Tor v3 Transport · Sequoia OpenPGP Ready",
                        color = Color(0xFFB8C0C8),
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            repeat(12) { idx ->
                Text(
                    text = "[0${idx + 1}] Hetract prai (Z13) encrypted drop relay active over isolated Arti circuit — verify Ed25519 fingerprint before transmitting.",
                    color = Color(0xFF9EA7B2),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
        compose.waitForIdle()
        saveDecorScreenshot(name)
    }

    private fun saveDecorScreenshot(name: String) {
        val view = compose.activity.window.decorView
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(view.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(view.height, android.view.View.MeasureSpec.EXACTLY),
        )
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        view.draw(canvas)

        val directory = File("build/screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}
