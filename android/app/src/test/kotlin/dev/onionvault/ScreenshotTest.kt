package dev.onionvault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.onionvault.ui.BackPreviewState
import dev.onionvault.ui.BrowserScreen
import dev.onionvault.ui.BrowserUiState
import dev.onionvault.ui.ClearnetPromptState
import dev.onionvault.ui.DUCKDUCKGO_ONION_URL
import dev.onionvault.ui.EncryptRequestState
import dev.onionvault.ui.KeyInfo
import dev.onionvault.ui.NormalizedBounds
import dev.onionvault.ui.OvCrashScreen
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.PgpOverlayState
import dev.onionvault.ui.PgpPlaintextState
import dev.onionvault.ui.TabItemState
import dev.onionvault.ui.TorBootstrapState
import dev.onionvault.ui.TrapPreferences
import dev.onionvault.ui.TrapTheme
import dev.onionvault.ui.TrapThemeFamily
import dev.onionvault.ui.TrapThemeMode
import dev.onionvault.ui.TrustedOnionDirectory
import dev.onionvault.ui.previews.BrowserChromePreview
import dev.onionvault.ui.previews.BrowserMenuPreview
import dev.onionvault.ui.previews.ClearnetConsentPreview
import dev.onionvault.ui.previews.CrashScreenPreview
import dev.onionvault.ui.previews.LegalNoticesPreview
import dev.onionvault.ui.previews.NeonGraffitiKeyringPreview
import dev.onionvault.ui.previews.NeonGraffitiLightTabsPreview
import dev.onionvault.ui.previews.PgpActionChipsPreview
import dev.onionvault.ui.previews.PgpDecryptionModalPreview
import dev.onionvault.ui.previews.PredictiveBackPreview
import dev.onionvault.ui.previews.RecipientPickerPreview
import dev.onionvault.ui.previews.SecureDropLightSettingsPreview
import dev.onionvault.ui.previews.TorBootstrapPreview
import dev.onionvault.ui.previews.TorTaxiPreview
import dev.onionvault.ui.previews.torTaxiState
import java.io.File
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
 * Screenshot & interactive Compose UI verification suite for Trap's full-bleed
 * `MetaToolsBrowserShell` floating hardware pods (`tor_orb_pod`, `crt_address_pod`,
 * `pgp_rocker_pod`, `side_remote_pod`, `perimeter_steel_rails`), street-zine `StartPage`
 * with SHA3-verified `TrustedOnionDirectory`, `ClearnetConsentHardwarePod`, theme families
 * (`SecureDrop` & `NeonGraffiti` × `Dark` & `Light`), PGP flows, hit-test passthrough,
 * `BackHandler` dismissal, predictive-back snapshot recovery, and `TrapPreferences` persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // --- Visual Screenshot Captures (build/screenshots/*.png) ---

    @Test
    fun the_tor_bootstrap_is_rendered() {
        capture("1-tor-bootstrap") { TorBootstrapPreview() }
    }

    @Test
    fun the_pgp_chips_is_rendered() {
        capture("2-pgp-chips") { PgpActionChipsPreview() }
    }

    @Test
    fun the_predictive_back_is_rendered() {
        capture("3-predictive-back") { PredictiveBackPreview() }
    }

    @Test
    fun the_key_manager_is_rendered() {
        capture("4-key-manager") { PgpDecryptionModalPreview() }
    }

    @Test
    fun the_legal_notices_are_rendered() {
        capture("5-legal-notices") { LegalNoticesPreview() }
    }

    @Test
    fun the_browser_chrome_is_rendered() {
        capture("6-browser-chrome") { BrowserChromePreview() }
    }

    @Test
    fun tor_taxi_is_rendered() {
        val page = fetchedPage()
        if (page == null) {
            capture("7-tor-taxi") { TorTaxiPreview() }
        } else {
            capture("7-tor-taxi") {
                BrowserScreen(
                    state = torTaxiState(),
                    surface = {
                        Image(
                            bitmap = page,
                            contentDescription = "DuckDuckGo Onion",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            }
        }
    }

    @Test
    fun the_browser_menu_is_rendered() {
        capture("8-browser-menu") { BrowserMenuPreview() }
    }

    @Test
    fun the_crash_screen_is_rendered() {
        capture("9-crash-screen") { CrashScreenPreview() }
    }

    @Test
    fun neon_graffiti_keyring_is_rendered() {
        capture("10-neon-graffiti-keyring") { NeonGraffitiKeyringPreview() }
    }

    @Test
    fun securedrop_light_settings_is_rendered() {
        capture("11-securedrop-light-settings") { SecureDropLightSettingsPreview() }
    }

    @Test
    fun neon_graffiti_light_tabs_is_rendered() {
        capture("12-neon-graffiti-light-tabs") { NeonGraffitiLightTabsPreview() }
    }

    @Test
    fun recipient_picker_sheet_is_rendered() {
        capture("13-recipient-picker") { RecipientPickerPreview() }
    }

    @Test
    fun clearnet_consent_sheet_is_rendered() {
        capture("14-clearnet-consent") { ClearnetConsentPreview() }
    }

    // --- Full-Bleed MetaTools Shell, Street-Zine StartPage & Hit-Test Passthrough ---

    @Test
    fun metatools_pods_and_street_zine_start_page_enforce_zero_container_bars_and_full_wiring() {
        var navigatedUrl = ""
        var webpageTaps by mutableIntStateOf(0)
        var state by mutableStateOf(
            BrowserUiState(
                url = "about:blank",
                tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
            ),
        )

        compose.setContent {
            BrowserScreen(
                state = state,
                onNavigate = { target ->
                    navigatedUrl = target
                    state = state.copy(url = target)
                },
                onHome = {
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
                            .testTag("live_webpage_surface"),
                    )
                },
            )
        }
        compose.waitForIdle()

        // 1. Strictly zero rectangular BottomControlDock, zero top app bar, zero transport footer.
        compose.onNodeWithTag("bottom_control_dock").assertDoesNotExist()
        compose.onNodeWithTag("top_identity_header").assertDoesNotExist()
        compose.onNodeWithTag("transport_footer").assertDoesNotExist()
        compose.onNodeWithTag("top_tor_pill").assertDoesNotExist()

        // 2. MetaToolsBrowserShell + 1.5x floating KPT islands + TRAP street-zine StartPage + TrustedOnionDirectory.
        compose.onNodeWithTag("metatools_browser_shell").assertIsDisplayed()
        compose.onNodeWithTag("perimeter_steel_rails").assertIsDisplayed()
        compose.onNodeWithTag("webpage_viewport_host").assertIsDisplayed()
        assertEquals(0.dp, compose.onNodeWithTag("webpage_viewport_host").getUnclippedBoundsInRoot().top)
        assertEquals(0.dp, compose.onNodeWithTag("perimeter_steel_rails").getUnclippedBoundsInRoot().top)
        compose.onNodeWithTag("kpt_back_pod").assertIsDisplayed()
        compose.onNodeWithTag("tor_orb_pod").assertIsDisplayed()
        compose.onNodeWithTag("crt_address_pod").assertIsDisplayed()
        compose.onNodeWithTag("pgp_rocker_pod").assertIsDisplayed()
        compose.onNodeWithTag("side_remote_pod").assertIsDisplayed()

        compose.onNodeWithTag("start_page").assertIsDisplayed()
        compose.onNodeWithTag("start_zine_wallpaper", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("TRAP").assertIsDisplayed()
        compose.onNodeWithTag("start_tor_status").assertIsDisplayed()
        compose.onNodeWithText("Tor ready").assertIsDisplayed()
        compose.onNodeWithTag("start_search_box").assertIsDisplayed()
        compose.onNodeWithTag("trusted_onion_directory").assertIsDisplayed()

        // 3. Tapping start_tor_status expands PhotoSoapSideRemote in TorCircuit mode; BackHandler collapses it.
        compose.onNodeWithTag("start_tor_status").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("remote_tor_circuit").assertIsDisplayed()
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("remote_tor_circuit").assertDoesNotExist()

        // 4. Verify 1.5x pod bounds & open webpage gaps across [ Back (74dp) ] -> [ CRT (74dp tall) ] -> [ Tor Orb (86dp) ].
        val backBounds = compose.onNodeWithTag("kpt_back_pod").getUnclippedBoundsInRoot()
        val crtBounds = compose.onNodeWithTag("crt_address_pod").getUnclippedBoundsInRoot()
        val orbBounds = compose.onNodeWithTag("tor_orb_pod").getUnclippedBoundsInRoot()
        assertTrue(backBounds.width >= 72.dp && backBounds.height >= 72.dp)
        assertTrue(crtBounds.height >= 70.dp)
        assertTrue(orbBounds.width >= 84.dp && orbBounds.height >= 84.dp)
        assertTrue(crtBounds.left - backBounds.right >= 7.5.dp)
        assertTrue(orbBounds.left - crtBounds.right >= 7.5.dp)

        // 5. Submitting a search query on start_search_box routes directly to DuckDuckGo v3 .onion!
        compose.onNodeWithTag("start_search_box").performTextInput("securedrop")
        compose.onNodeWithTag("start_search_submit").performClick()
        compose.waitForIdle()
        assertEquals(
            TrustedOnionDirectory.resolveSearchOrAddress("securedrop"),
            navigatedUrl,
        )
        compose.onNodeWithTag("start_page").assertDoesNotExist()
        compose.onNodeWithText("duckduck…zczad.onion").assertIsDisplayed()

        // 6. Hit-test passthrough on live webpage through open gap between KptBackButtonPod and CRT pod!
        val liveBackBounds = compose.onNodeWithTag("kpt_back_pod").getUnclippedBoundsInRoot()
        val liveCrtBounds = compose.onNodeWithTag("crt_address_pod").getUnclippedBoundsInRoot()
        assertTrue(liveCrtBounds.left - liveBackBounds.right >= 7.5.dp)
        val gapX = ((liveBackBounds.right.value + liveCrtBounds.left.value) / 2f).dp
        val gapY = ((liveCrtBounds.top.value + liveCrtBounds.bottom.value) / 2f).dp
        compose.onNodeWithTag("metatools_browser_shell").performTouchInput {
            click(Offset(gapX.toPx(), gapY.toPx()))
        }
        compose.waitForIdle()
        assertEquals("Open gap between pods must pass touch straight to webpage", 1, webpageTaps)

        // 6b. First-hop KptBackButtonPod tap returns cleanly from directory/search page to StartPage!
        compose.onNodeWithTag("kpt_back_pod").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("start_page").assertIsDisplayed()

        // 7. Focusing crt_address_pod expands >= 95% width (~380dp+), hides Back/Orb/Rocker/Remote, Go submits!
        compose.onNodeWithTag("crt_address_pod").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("kpt_back_pod").assertDoesNotExist()
        compose.onNodeWithTag("tor_orb_pod").assertDoesNotExist()
        compose.onNodeWithTag("pgp_rocker_pod").assertDoesNotExist()
        compose.onNodeWithTag("side_remote_pod").assertDoesNotExist()

        val focusedCrtWidth = compose.onNodeWithTag("crt_address_pod").getUnclippedBoundsInRoot().width
        assertTrue("Expected focused CRT >= 380dp on 411dp screen, got $focusedCrtWidth", focusedCrtWidth >= 380.dp)

        compose.onNodeWithTag("crt_clear_button").performClick()
        compose.onNodeWithTag("crt_address_input").performTextInput(DUCKDUCKGO_ONION_URL)
        compose.onNodeWithTag("crt_address_input").performImeAction()
        compose.waitForIdle()
        assertEquals(DUCKDUCKGO_ONION_URL, navigatedUrl)
        compose.onNodeWithTag("kpt_back_pod").assertIsDisplayed()
        compose.onNodeWithTag("tor_orb_pod").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w600dp-h891dp-xhdpi")
    fun wide_viewport_renders_detached_metatools_pods_with_zero_bottom_bar() {
        compose.setContent {
            BrowserScreen(
                state = BrowserUiState(
                    url = DUCKDUCKGO_ONION_URL,
                    canGoBack = true,
                    canGoForward = false,
                    tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
                ),
            )
        }

        compose.onNodeWithTag("bottom_control_dock").assertDoesNotExist()
        compose.onNodeWithTag("kpt_back_pod").assertIsDisplayed()
        compose.onNodeWithTag("tor_orb_pod").assertIsDisplayed()
        compose.onNodeWithTag("crt_address_pod").assertIsDisplayed()
        compose.onNodeWithTag("pgp_rocker_pod").assertIsDisplayed()
        compose.onNodeWithTag("side_remote_pod").assertIsDisplayed()
    }

    // --- Clearnet Consent Hardware Pod E2E Verification ---

    @Test
    fun clearnet_consent_sheet_allows_for_3_days_supports_glob_editing_and_revokes_rules() {
        val rawPrefs = compose.activity.getSharedPreferences("test_clearnet_prefs", Context.MODE_PRIVATE)
        rawPrefs.edit().clear().commit()
        val prefs = TrapPreferences(rawPrefs)
        assertFalse("hiddenServiceOnly must default to false", prefs.hiddenServiceOnly)

        val recordedAllows = mutableListOf<Pair<String, Long>>()
        val recordedBlocks = mutableListOf<String>()
        var state by mutableStateOf(
            BrowserUiState(
                url = "about:blank",
                hiddenServiceOnly = prefs.hiddenServiceOnly,
                tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
            ),
        )

        fun matchesSimpleGlob(pattern: String, host: String): Boolean {
            val p = pattern.lowercase()
            val h = host.lowercase()
            if (p.startsWith("*.") && (h == p.removePrefix("*.") || h.endsWith(p.removePrefix("*")))) {
                return true
            }
            val regex = Regex(
                "^" + Regex.escape(p).replace("\\*", ".*").replace("\\?", ".") + "$",
            )
            return regex.matches(h)
        }

        fun navigateWithClearnetGate(rawInput: String) {
            val trimmed = rawInput.trim()
            val normalized = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val host = trimmed.removePrefix("https://").removePrefix("http://")
                .substringBefore('/').lowercase()
            val allowed = state.clearnetRules.any { matchesSimpleGlob(it.pattern, host) }
            if (allowed) {
                state = state.copy(
                    url = normalized,
                    title = host,
                    blocked = false,
                    status = "",
                    clearnetPrompt = null,
                )
            } else {
                state = state.copy(
                    blocked = false,
                    status = "",
                    clearnetPrompt = ClearnetPromptState(
                        host = host,
                        pendingUrl = normalized,
                        suggestedPattern = OvNative.suggestGlob(host),
                    ),
                )
            }
        }

        compose.setContent {
            BrowserScreen(
                state = state,
                onNavigate = { input -> navigateWithClearnetGate(input) },
                onAllowClearnet = { prompt, pattern, ttlSeconds ->
                    recordedAllows += pattern to ttlSeconds
                    val now = System.currentTimeMillis()
                    val newRule = OvNative.ClearnetRuleItem(
                        pattern = pattern,
                        createdAtMs = now,
                        expiresAtMs = if (ttlSeconds > 0L) now + ttlSeconds * 1_000L else 0L,
                        sessionOnly = ttlSeconds <= 0L,
                    )
                    val updated = state.clearnetRules.filterNot { it.pattern == pattern } + newRule
                    state = state.copy(clearnetPrompt = null, clearnetRules = updated)
                    if (prompt.pendingUrl != null) {
                        navigateWithClearnetGate(prompt.pendingUrl)
                    }
                },
                onBlockClearnet = { prompt ->
                    recordedBlocks += prompt.host
                    state = state.copy(clearnetPrompt = null)
                },
                onRevokeClearnetRule = { pattern ->
                    state = state.copy(
                        clearnetRules = state.clearnetRules.filterNot { it.pattern == pattern },
                    )
                },
                onClearClearnetRules = {
                    state = state.copy(clearnetRules = emptyList())
                },
            )
        }

        // 1. Navigating to bare clearnet `tor.taxi` via CRT pod opens ClearnetConsentHardwarePod.
        compose.onNodeWithTag("crt_address_pod").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("crt_address_input").performTextInput("tor.taxi")
        compose.onNodeWithTag("crt_address_input").performImeAction()
        compose.waitForIdle()

        compose.onNodeWithTag("status_notice_banner").assertDoesNotExist()
        compose.onNodeWithTag("clearnet_consent_sheet").assertIsDisplayed()
        compose.onNodeWithText("Allow clearnet site over Tor?").assertIsDisplayed()
        compose.onNodeWithTag("clearnet_prompt_host").assertTextEquals("tor.taxi")
        compose.onNodeWithTag("clearnet_pattern_input").assertTextEquals("*.tor.taxi")

        // 2. Scope pills toggle cleanly between Exact host and *.domain.
        compose.onNodeWithTag("clearnet_scope_exact").performClick()
        compose.onNodeWithTag("clearnet_pattern_input").assertTextEquals("tor.taxi")
        compose.onNodeWithTag("clearnet_scope_domain").performClick()
        compose.onNodeWithTag("clearnet_pattern_input").assertTextEquals("*.tor.taxi")

        // 3. One click on "Allow for 3 days" persists 259,200s rule, dismisses pod, and navigates to https://tor.taxi!
        compose.onNodeWithTag("clearnet_allow_3d_button").performClick()
        compose.waitForIdle()

        assertEquals(listOf("*.tor.taxi" to 259_200L), recordedAllows)
        compose.onNodeWithTag("clearnet_consent_sheet").assertDoesNotExist()
        assertEquals("https://tor.taxi", state.url)
        compose.onNodeWithText("tor.taxi").assertIsDisplayed()

        // 4. Custom glob editing + "Allow once" (`ttlSeconds = 0L`) on SOCKS5 subresource prompt (`pendingUrl = null`).
        state = state.copy(
            clearnetPrompt = ClearnetPromptState(
                host = "cdn.assets.net",
                pendingUrl = null,
                suggestedPattern = "*.assets.net",
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("clearnet_consent_sheet").assertIsDisplayed()
        compose.onNodeWithTag("clearnet_pattern_input").performTextReplacement("*cdn.assets.net")
        compose.onNodeWithTag("clearnet_allow_once_button").performClick()
        compose.waitForIdle()

        assertEquals(
            listOf("*.tor.taxi" to 259_200L, "*cdn.assets.net" to 0L),
            recordedAllows,
        )

        // 5. "Block" records session denial without navigating.
        state = state.copy(
            clearnetPrompt = ClearnetPromptState(
                host = "telemetry.example.com",
                pendingUrl = "https://telemetry.example.com",
                suggestedPattern = "*.example.com",
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("clearnet_block_button").performClick()
        compose.waitForIdle()
        assertEquals(listOf("telemetry.example.com"), recordedBlocks)
        assertEquals("https://tor.taxi", state.url)

        // 6. TorKnurledOrbPod -> PhotoSoapSideRemote TorCircuit mode displays active rules, Revoke & Clear All!
        compose.onNodeWithTag("tor_orb_pod").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("remote_tor_circuit").assertIsDisplayed()
        compose.onNodeWithText("*.tor.taxi").assertIsDisplayed()
        compose.onNodeWithText("3d left").assertIsDisplayed()
        compose.onNodeWithText("*cdn.assets.net").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Session").assertIsDisplayed()

        compose.onNodeWithTag("clearnet_revoke_*.tor.taxi").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("*cdn.assets.net"), state.clearnetRules.map { it.pattern })
        compose.onNodeWithText("*.tor.taxi").assertDoesNotExist()

        compose.onNodeWithTag("clearnet_clear_all_button").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(state.clearnetRules.isEmpty())
    }

    @Test
    fun ov_native_clearnet_json_parsers_and_glob_suggestions_match_contract() {
        assertEquals("*.tor.taxi", OvNative.suggestGlob("tor.taxi"))
        assertEquals("*.tor.taxi", OvNative.suggestGlob("www.tor.taxi"))
        assertEquals("*.tor.taxi", OvNative.suggestGlob("sub.tor.taxi"))
        assertEquals("1.1.1.1", OvNative.suggestGlob("1.1.1.1"))

        val consentPolicy = OvNative.parseUrlPolicy(
            """
            {
              "allowed": false,
              "normalizedUrl": "https://tor.taxi",
              "isOnion": false,
              "requiresClearnetConsent": true,
              "host": "tor.taxi",
              "suggestedPattern": "*.tor.taxi",
              "reason": "Clearnet site requires user consent"
            }
            """.trimIndent(),
        )
        assertFalse(consentPolicy.allowed)
        assertTrue(consentPolicy.requiresClearnetConsent)
        assertEquals("https://tor.taxi", consentPolicy.normalizedUrl)
        assertEquals("tor.taxi", consentPolicy.host)
        assertEquals("*.tor.taxi", consentPolicy.suggestedPattern)

        val torStatus = OvNative.parseTorStatus(
            """
            {
              "bootstrapped": true,
              "bootstrapPercent": 100,
              "bootstrapSummary": "ready",
              "socksAddr": "127.0.0.1:41952",
              "hiddenServiceOnly": false,
              "pendingClearnetHost": "cdn.tor.taxi"
            }
            """.trimIndent(),
        )
        assertTrue(torStatus.bootstrapped)
        assertFalse(torStatus.hiddenServiceOnly)
        assertEquals("cdn.tor.taxi", torStatus.pendingClearnetHost)

        val rules = OvNative.parseClearnetRules(
            """
            [
              {"pattern":"*.tor.taxi","createdAtMs":1700000000000,"expiresAtMs":1700259200000,"sessionOnly":false},
              {"pattern":"exact.example.org","createdAtMs":1700000000000,"expiresAtMs":0,"sessionOnly":true}
            ]
            """.trimIndent(),
        )
        assertEquals(2, rules.size)
        assertEquals("*.tor.taxi", rules[0].pattern)
        assertFalse(rules[0].sessionOnly)
        assertTrue(rules[1].sessionOnly)
    }

    // --- Interactive Hardware Pods & Persisted State Verification ---

    @Test
    fun settings_pod_persists_theme_family_mode_and_zoom_to_shared_preferences() {
        val rawPrefs = compose.activity.getSharedPreferences("test_trap_prefs", Context.MODE_PRIVATE)
        rawPrefs.edit().clear().commit()
        val prefs = TrapPreferences(rawPrefs)

        var state by mutableStateOf(
            BrowserUiState(
                themeFamily = prefs.themeFamily,
                themeMode = prefs.themeMode,
                defaultZoomPercent = prefs.defaultZoomPercent,
                hiddenServiceOnly = prefs.hiddenServiceOnly,
                requireBiometric = prefs.requireBiometric,
            ),
        )

        compose.setContent {
            BrowserScreen(
                state = state,
                showSettings = true,
                onSelectThemeFamily = { family ->
                    prefs.themeFamily = family
                    state = state.copy(themeFamily = family)
                },
                onSelectThemeMode = { mode ->
                    prefs.themeMode = mode
                    state = state.copy(themeMode = mode)
                },
                onSelectDefaultZoom = { zoom ->
                    prefs.defaultZoomPercent = zoom
                    state = state.copy(defaultZoomPercent = zoom, zoomPercent = zoom)
                },
            )
        }

        compose.onNodeWithTag("settings_sheet").assertIsDisplayed()
        compose.onNodeWithText("Neon Graffiti").performClick()
        assertEquals(TrapThemeFamily.NeonGraffiti, prefs.themeFamily)
        assertEquals(TrapThemeFamily.NeonGraffiti, state.themeFamily)

        compose.onNodeWithText("Light").performClick()
        assertEquals(TrapThemeMode.Light, prefs.themeMode)
        assertEquals(TrapThemeMode.Light, state.themeMode)

        compose.onNodeWithText("125%").performScrollTo().performClick()
        assertEquals(125, prefs.defaultZoomPercent)
        assertEquals(125, state.defaultZoomPercent)
    }

    @Test
    fun tor_orb_and_side_remote_wire_circuit_telemetry_navigation_zoom_and_identity_reset() {
        var hiddenOnly = true
        var resetCount = 0
        var reloadCount = 0
        var forwardCount = 0
        var zoomInCount = 0
        var uiState by mutableStateOf(
            BrowserUiState(
                url = DUCKDUCKGO_ONION_URL,
                title = "DuckDuckGo",
                canGoForward = true,
                hiddenServiceOnly = true,
                tor = TorBootstrapState(
                    percent = 100,
                    stage = "ready",
                    ready = true,
                    socksAddr = "127.0.0.1:41520",
                    isolationToken = "iso-duckduckgogg42xj",
                    isolatedGroupCount = 1,
                ),
            ),
        )

        compose.setContent {
            BrowserScreen(
                state = uiState,
                onReload = { reloadCount += 1 },
                onForward = { forwardCount += 1 },
                onZoomIn = { zoomInCount += 1 },
                onToggleRouting = { enabled ->
                    hiddenOnly = enabled
                    uiState = uiState.copy(hiddenServiceOnly = enabled)
                },
                onResetTorIsolation = {
                    resetCount += 1
                },
            )
        }

        // 1. Tap TorKnurledOrbPod -> expands PhotoSoapSideRemote in TorCircuit mode with real isolation token.
        compose.onNodeWithTag("tor_orb_pod").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("remote_tor_circuit").assertIsDisplayed()
        compose.onNode(
            hasText("iso-duckduckgogg42xj") and hasAnyAncestor(hasTestTag("remote_tor_circuit")),
        ).assertIsDisplayed()

        compose.onNodeWithTag("remote_onion_only_toggle").performScrollTo().performClick()
        assertFalse(hiddenOnly)

        // 2-tap confirm on New Tor Identity
        compose.onNodeWithTag("remote_new_identity").performScrollTo().performClick()
        assertEquals(0, resetCount)
        compose.onNodeWithTag("remote_new_identity").performScrollTo().performClick()
        assertEquals(1, resetCount)

        // Switch to Menu mode on PhotoSoapSideRemote -> Zoom +, Reload, Forward
        compose.onNodeWithTag("remote_switch_to_menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("remote_zoom_in").performScrollTo().performClick()
        assertEquals(1, zoomInCount)
        compose.onNodeWithTag("remote_reload").performScrollTo().performClick()
        assertEquals(1, reloadCount)

        compose.onNodeWithTag("side_remote_collapsed_face").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("remote_forward").performClick()
        assertEquals(1, forwardCount)
    }

    @Test
    fun crt_swipe_and_spatial_tab_switcher_pod_create_switch_and_close_tabs() {
        var createdCount = 0
        var selectedId = "tab-1"
        var closedId = ""

        compose.setContent {
            BrowserScreen(
                state = BrowserUiState(
                    url = DUCKDUCKGO_ONION_URL,
                    tabs = listOf(
                        TabItemState(id = "tab-1", title = "Trap", url = "about:blank", isActive = true),
                        TabItemState(id = "tab-2", title = "DuckDuckGo", url = DUCKDUCKGO_ONION_URL, isActive = false, isOnion = true),
                    ),
                    activeTabId = "tab-1",
                ),
                onNewTab = { createdCount += 1 },
                onSelectTab = { selectedId = it },
                onCloseTab = { closedId = it },
            )
        }

        // Horizontal swipe left on CRT capsule switches to adjacent tab ("tab-2")
        compose.onNodeWithTag("crt_address_pod").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals("tab-2", selectedId)

        // Pull upward on CRT capsule opens SpatialTabSwitcherPod ("tabs_sheet")
        compose.onNodeWithTag("crt_address_pod").performTouchInput {
            down(center)
            moveBy(Offset(0f, -40.dp.toPx()))
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tabs_sheet").assertIsDisplayed()

        compose.onNodeWithTag("tab_close_tab-2").performClick()
        assertEquals("tab-2", closedId)

        selectedId = "tab-1"
        compose.onNodeWithTag("tab_row_tab-2").performClick()
        assertEquals("tab-2", selectedId)

        // Re-open via Side Remote -> Tabs (2) -> New Tab
        compose.onNodeWithTag("side_remote_collapsed_face").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("remote_tabs").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tabs_new_tab").performClick()
        assertEquals(1, createdCount)
    }

    @Test
    fun pgp_overlay_rocker_latch_encrypt_picker_plaintext_and_keyring_pods_execute() {
        var generatedUserId = ""
        var generatedPassphrase = ""
        var encryptedRecipient = ""
        var encryptedSigner = ""
        var encryptedText = ""
        var copiedPlaintext = ""
        var insertedPlaintext = ""

        val ownKey = KeyInfo(
            fingerprint = "A3D9071F5C2E8B40D16F7394C8E5A2B6F04D71C9",
            userId = "Source <source@onion>",
            hasSecret = true,
            canSign = true,
            canEncrypt = true,
            isOwnKey = true,
        )
        val deskKey = KeyInfo(
            fingerprint = "6F1A2C939B4E77D10E585A623F8B91C47D0A11E2",
            userId = "Newsroom Desk <desk@securedrop.onion>",
            hasSecret = false,
            canSign = false,
            canEncrypt = true,
            isOwnKey = false,
        )

        var state by mutableStateOf(
            BrowserUiState(
                url = "http://sdshqo3t6ilcq6cyudujqi3l5n6dqsmx6x3rv3moymqm3vhch6ad.onion",
                title = "SecureDrop",
                tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
                keys = listOf(deskKey, ownKey),
                defaultKeyFingerprint = ownKey.fingerprint,
                pgp = PgpOverlayState(
                    fields = listOf(
                        PgpFieldState(
                            nodeId = "cdp-9",
                            kind = "pgp_textarea",
                            label = "Encrypt Field",
                            bounds = NormalizedBounds(0.06f, 0.2f, 0.94f, 0.4f),
                            preview = "Confidential tip body",
                            armored = "Confidential tip body",
                            cdpNodeId = 9,
                        ),
                    ),
                ),
            ),
        )

        compose.setContent {
            BrowserScreen(
                state = state,
                onFieldAction = { field ->
                    state = state.copy(
                        pgp = state.pgp.copy(
                            encryptRequest = EncryptRequestState(
                                nodeId = field.nodeId,
                                cdpNodeId = field.cdpNodeId,
                                initialText = field.armored ?: field.preview,
                                writeBackToField = true,
                            ),
                        ),
                    )
                },
                onConfirmEncrypt = { _, plaintext, recipientFp, signerFp, _ ->
                    encryptedText = plaintext
                    encryptedRecipient = recipientFp
                    encryptedSigner = signerFp
                    state = state.copy(
                        pgp = state.pgp.copy(
                            encryptRequest = null,
                            plaintext = PgpPlaintextState(
                                nodeId = "cdp-9",
                                title = "Decrypted Message",
                                body = "Verified reply from newsroom",
                                signatureVerified = true,
                                signatureStatus = "good",
                                signerFingerprint = deskKey.fingerprint,
                                signerUserId = deskKey.userId,
                                cdpNodeId = 9,
                                targetEditable = true,
                            ),
                        ),
                    )
                },
                onCopyPlaintext = { copiedPlaintext = it },
                onInsertPlaintext = { insertedPlaintext = it.body },
                onDismissPlaintext = {
                    state = state.copy(pgp = state.pgp.copy(plaintext = null))
                },
                onGenerateKey = { uid, pass ->
                    generatedUserId = uid
                    generatedPassphrase = pass
                },
            )
        }

        // 1. Dragging OpenPgpRockerPod latch upward (`dy < -18dp`) opens EncryptRecipientHardwarePod
        //    (auto-selecting external `deskKey`, never `[Own]`!).
        compose.onNodeWithTag("pgp_rocker_pod").performTouchInput {
            down(center)
            moveBy(Offset(0f, -26.dp.toPx()))
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("encrypt_picker_sheet").assertIsDisplayed()
        compose.onNodeWithTag("encrypt_confirm_button").assertIsEnabled().performClick()
        assertEquals("Confidential tip body", encryptedText)
        assertEquals(deskKey.fingerprint, encryptedRecipient)
        assertEquals(ownKey.fingerprint, encryptedSigner)

        // 2. DecryptedPlaintextHardwarePod renders signature attribution, Copy, Insert, and Close
        compose.onNodeWithTag("plaintext_sheet").assertIsDisplayed()
        compose.onNodeWithTag("plaintext_copy").performClick()
        assertEquals("Verified reply from newsroom", copiedPlaintext)
        compose.onNodeWithTag("plaintext_insert").performClick()
        assertEquals("Verified reply from newsroom", insertedPlaintext)
        compose.onNodeWithTag("plaintext_close").performClick()
        compose.waitForIdle()

        // 3. KeyringHardwarePod accessed cleanly via Side Remote -> Keyring
        compose.onNodeWithTag("side_remote_collapsed_face").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("remote_keyring").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("keyring_sheet").assertIsDisplayed()
        compose.onNodeWithText("Generate").performClick()
        compose.onNodeWithTag("keygen_userid_input").performTextInput("Journalist <j@onion>")
        compose.onNodeWithTag("keygen_passphrase_input").performTextInput("correct-horse-battery")
        compose.onNodeWithTag("keygen_submit_button").performClick()
        assertEquals("Journalist <j@onion>", generatedUserId)
        assertEquals("correct-horse-battery", generatedPassphrase)
    }

    @Test
    fun snapshot_banner_triggers_live_reload_and_crash_screen_copies_and_retries() {
        var liveReloadClicked = false
        compose.setContent {
            BrowserScreen(
                state = BrowserUiState(
                    url = DUCKDUCKGO_ONION_URL,
                    title = "DuckDuckGo",
                    viewingSnapshot = true,
                    backPreview = BackPreviewState(
                        url = DUCKDUCKGO_ONION_URL,
                        title = "DuckDuckGo",
                    ),
                ),
                onReloadLive = { liveReloadClicked = true },
            )
        }

        compose.onNodeWithTag("snapshot_reload_banner").assertIsDisplayed()
        compose.onNodeWithTag("reload_live_button").performClick()
        assertTrue(liveReloadClicked)
    }

    @Test
    fun crash_screen_executes_copy_and_dismiss_callbacks() {
        var copied = false
        var retried = false
        compose.setContent {
            TrapTheme(
                family = TrapThemeFamily.SecureDrop,
                mode = TrapThemeMode.Dark,
            ) {
                OvCrashScreen(
                    report = "Trap stopped unexpectedly: sample trace line",
                    onCopy = { copied = true },
                    onRetry = { retried = true },
                )
            }
        }

        compose.onNode(hasTestTag("crash_copy") and hasText("Copy Trace")).performClick()
        assertTrue(copied)

        compose.onNodeWithTag("crash_retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun editable_pubkey_field_renders_insert_my_pubkey_and_routes_correctly() {
        var insertedFieldId = ""
        var importedFieldId = ""

        var state by mutableStateOf(
            BrowserUiState(
                url = "http://sdshqo3t6ilcq6cyudujqi3l5n6dqsmx6x3rv3moymqm3vhch6ad.onion/register",
                title = "Register Public Key",
                tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
                pgp = PgpOverlayState(
                    fields = listOf(
                        PgpFieldState(
                            nodeId = "cdp-pub-edit",
                            kind = "pgp_public_key",
                            label = "Insert My PubKey",
                            bounds = NormalizedBounds(0.06f, 0.25f, 0.94f, 0.30f),
                            cdpNodeId = 21,
                        ),
                    ),
                ),
            ),
        )

        compose.setContent {
            BrowserScreen(
                state = state,
                onFieldAction = { field -> importedFieldId = field.nodeId },
                onInsertMyPubKey = { field -> insertedFieldId = field.nodeId },
            )
        }

        // Editable pubkey upload field must render "Insert My PubKey" and never "Import Key".
        compose.onNodeWithText("Insert My PubKey").assertIsDisplayed().performClick()
        compose.onNodeWithText("Import Key").assertDoesNotExist()
        assertEquals("cdp-pub-edit", insertedFieldId)
        assertEquals("", importedFieldId)

        // Read-only armored pubkey block renders "Import Key" and routes to onFieldAction.
        state = state.copy(
            pgp = PgpOverlayState(
                fields = listOf(
                    PgpFieldState(
                        nodeId = "cdp-pub-block",
                        kind = "pgp_public_key",
                        label = "Import Key",
                        bounds = NormalizedBounds(0.06f, 0.40f, 0.94f, 0.65f),
                        armored = "-----BEGIN PGP PUBLIC KEY BLOCK-----\n...\n-----END PGP PUBLIC KEY BLOCK-----",
                        cdpNodeId = 22,
                    ),
                ),
            ),
        )
        compose.onNodeWithText("Import Key").assertIsDisplayed().performClick()
        compose.onNodeWithText("Insert My PubKey").assertDoesNotExist()
        assertEquals("cdp-pub-block", importedFieldId)
    }

    private fun fetchedPage(): ImageBitmap? {
        val path = System.getenv("TRAP_PAGE_IMAGE") ?: return null
        val file = File(path)
        if (!file.exists()) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return bitmap.asImageBitmap()
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
        compose.waitForIdle()

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

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        if (dialog != null && dialog.isShowing) {
            val decor = dialog.window?.decorView
            if (decor != null) {
                if (decor.width <= 0 || decor.height <= 0) {
                    val w = view.width.coerceAtLeast(1)
                    val h = view.height.coerceAtLeast(1)
                    decor.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.AT_MOST),
                        android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.AT_MOST),
                    )
                    decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                }
                if (decor.width > 0 && decor.height > 0) {
                    val overlay = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    decor.draw(Canvas(overlay))
                    val attributes = dialog.window?.attributes
                    canvas.drawBitmap(
                        overlay,
                        (attributes?.x ?: 0).toFloat(),
                        (attributes?.y ?: 0).toFloat(),
                        null,
                    )
                }
            }
        }

        val directory = File("build/screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}
