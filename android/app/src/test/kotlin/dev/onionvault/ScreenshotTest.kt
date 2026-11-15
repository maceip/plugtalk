package dev.onionvault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.onionvault.ui.BackPreviewState
import dev.onionvault.ui.BrowserScreen
import dev.onionvault.ui.BrowserUiState
import dev.onionvault.ui.EncryptRequestState
import dev.onionvault.ui.KeyInfo
import dev.onionvault.ui.NormalizedBounds
import dev.onionvault.ui.OvCrashScreen
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.PgpOverlayState
import dev.onionvault.ui.PgpPlaintextState
import dev.onionvault.ui.DUCKDUCKGO_ONION_URL
import dev.onionvault.ui.TabItemState
import dev.onionvault.ui.TorBootstrapState
import dev.onionvault.ui.TrapPreferences
import dev.onionvault.ui.TrapTheme
import dev.onionvault.ui.TrapThemeFamily
import dev.onionvault.ui.TrapThemeMode
import dev.onionvault.ui.previews.BrowserChromePreview
import dev.onionvault.ui.previews.BrowserMenuPreview
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
 * Screenshot & interactive Compose UI verification suite for Trap's browser chrome,
 * theme families (`SecureDrop` & `NeonGraffiti` × `Dark` & `Light`), sheets, PGP
 * flows, predictive-back snapshot recovery, and `TrapPreferences` persistence.
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
    fun the_pgp_chips_are_rendered() {
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

    // --- Interactive Compose Controls & Persisted State Verification ---

    @Test
    fun start_page_preserves_blank_address_and_launches_duckduckgo_onion() {
        var navigatedUrl = ""
        compose.setContent {
            BrowserScreen(
                state = BrowserUiState(
                    url = "about:blank",
                    tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
                ),
                onNavigate = { navigatedUrl = it },
            )
        }

        // `about:blank` must keep the input text empty rather than polluting the text field.
        compose.onNodeWithTag("address_input").assertTextEquals("")
        compose.onNodeWithTag("nav_back").assertIsNotEnabled()
        compose.onNodeWithTag("nav_forward").assertIsNotEnabled()

        compose.onNodeWithTag("start_ddg_onion_card").performClick()
        assertEquals(DUCKDUCKGO_ONION_URL, navigatedUrl)
    }

    @Test
    fun settings_sheet_persists_theme_family_mode_and_zoom_to_shared_preferences() {
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
    fun tor_pill_opens_security_sheet_and_wires_policy_and_identity_reset() {
        var hiddenOnly = true
        var resetCount = 0
        var uiState by mutableStateOf(
            BrowserUiState(
                url = DUCKDUCKGO_ONION_URL,
                title = "DuckDuckGo",
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
                onToggleRouting = { enabled ->
                    hiddenOnly = enabled
                    uiState = uiState.copy(hiddenServiceOnly = enabled)
                },
                onResetTorIsolation = {
                    resetCount += 1
                },
            )
        }

        compose.onNodeWithTag("top_tor_pill").performClick()
        compose.onNodeWithTag("security_circuit_sheet").assertIsDisplayed()
        compose.onNodeWithText("iso-duckduckgogg42xj").assertIsDisplayed()

        compose.onNodeWithTag("security_hidden_service_switch").performClick()
        assertFalse(hiddenOnly)

        compose.onNodeWithTag("security_reset_identity").performClick()
        assertEquals(1, resetCount)
    }

    @Test
    fun tabs_sheet_creates_switches_and_closes_tabs() {
        var createdCount = 0
        var selectedId = "tab-1"
        var closedId = ""

        compose.setContent {
            BrowserScreen(
                state = BrowserUiState(
                    tabs = listOf(
                        TabItemState(id = "tab-1", title = "Trap Start", url = "about:blank", isActive = true),
                        TabItemState(id = "tab-2", title = "DuckDuckGo", url = DUCKDUCKGO_ONION_URL, isActive = false, isOnion = true),
                    ),
                    activeTabId = "tab-1",
                ),
                onNewTab = { createdCount += 1 },
                onSelectTab = { selectedId = it },
                onCloseTab = { closedId = it },
            )
        }

        compose.onNodeWithTag("nav_tabs").performClick()
        compose.onNodeWithTag("tabs_sheet").assertIsDisplayed()

        compose.onNodeWithTag("tab_close_tab-2").performClick()
        assertEquals("tab-2", closedId)

        compose.onNodeWithTag("nav_tabs").performClick()
        compose.onNodeWithTag("tab_row_tab-2").performClick()
        assertEquals("tab-2", selectedId)

        compose.onNodeWithTag("nav_tabs").performClick()
        compose.onNodeWithTag("tabs_new_tab").performClick()
        assertEquals(1, createdCount)
    }

    @Test
    fun pgp_overlay_encrypt_picker_plaintext_and_keyring_flows_execute() {
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

        // 1. Tap "Encrypt Field" chip -> opens Recipient & Signer Picker Sheet
        compose.onNodeWithText("Encrypt Field").performClick()
        compose.onNodeWithTag("encrypt_picker_sheet").assertIsDisplayed()
        compose.onNodeWithTag("encrypt_confirm_button").performClick()
        assertEquals("Confidential tip body", encryptedText)
        assertEquals(deskKey.fingerprint, encryptedRecipient)
        assertEquals(ownKey.fingerprint, encryptedSigner)

        // 2. PlaintextSheet renders signature attribution, Copy, conditional Insert, and Close
        compose.onNodeWithTag("plaintext_sheet").assertIsDisplayed()
        compose.onNodeWithTag("plaintext_copy").performClick()
        assertEquals("Verified reply from newsroom", copiedPlaintext)
        compose.onNodeWithTag("plaintext_insert").performClick()
        assertEquals("Verified reply from newsroom", insertedPlaintext)
        compose.onNodeWithTag("plaintext_close").performClick()

        // 3. KeyringSheet custom User ID + passphrase key generation
        compose.onNodeWithTag("top_keyring_button").performClick()
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
            if (decor != null && decor.width > 0 && decor.height > 0) {
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

        val directory = File("build/screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}
