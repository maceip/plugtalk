package dev.onionvault

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cefpack.android.CefpackSession
import dev.onionvault.ui.TrapPreferences
import dev.onionvault.ui.TrapThemeFamily
import dev.onionvault.ui.TrapThemeMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * End-to-end instrumentation suite running on a real Android device / Limrun instance.
 *
 * Exercises live [MainActivity], full-bleed `MetaToolsBrowserShell` floating hardware pods
 * (`tor_orb_pod`, `crt_address_pod`, `pgp_rocker_pod`, `side_remote_pod`, `perimeter_steel_rails`),
 * street-zine `StartPage` + `TrustedOnionDirectory`, `ClearnetConsentHardwarePod` (3-day TTL
 * persistence, custom `*` globbing, and rule revocation), [TrapPreferences], [TabSessionManager],
 * and [OvNative] (Arti Tor + Sequoia OpenPGP).
 */
@RunWith(AndroidJUnit4::class)
class BrowserChromeInstrumentedTest {

    @get:Rule
    val composeRule = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<MainActivity>(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand("input keyevent KEYCODE_WAKEUP")
                    .close()
                putExtra(MainActivity.EXTRA_SHOW_OVER_KEYGUARD_FOR_TEST, true)
            },
        ),
        activityProvider = { rule ->
            var activity: MainActivity? = null
            rule.scenario.onActivity { activity = it }
            checkNotNull(activity) { "MainActivity was not created" }
        },
    )

    @Test
    fun chrome_settings_security_tabs_clearnet_consent_and_native_keyring_e2e() {
        val activity = composeRule.activity
        val prefs = TrapPreferences(activity)

        if (OvNative.isLoaded) {
            OvNative.nativeClearClearnetRules()
        }

        // 1. Edge-to-edge full-bleed MetaToolsBrowserShell + street-zine StartPage (zero BottomControlDock)
        composeRule.onNodeWithTag("browser_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("metatools_browser_shell").assertIsDisplayed()
        composeRule.onNodeWithTag("perimeter_steel_rails").assertIsDisplayed()
        composeRule.onNodeWithTag("webpage_viewport_host").assertIsDisplayed()
        composeRule.onNodeWithTag("kpt_back_pod").assertIsDisplayed()
        composeRule.onNodeWithTag("tor_orb_pod").assertIsDisplayed()
        composeRule.onNodeWithTag("crt_address_pod").assertIsDisplayed()
        composeRule.onNodeWithTag("pgp_rocker_pod").assertIsDisplayed()
        composeRule.onNodeWithTag("side_remote_pod").assertIsDisplayed()
        composeRule.onNodeWithTag("start_page").assertIsDisplayed()
        composeRule.onNodeWithTag("start_tor_status").assertDoesNotExist()
        composeRule.onNodeWithTag("start_search_box").assertDoesNotExist()
        composeRule.onNodeWithTag("trusted_onion_directory").assertDoesNotExist()
        composeRule.onNodeWithTag("bottom_control_dock").assertDoesNotExist()
        composeRule.onNodeWithTag("top_identity_header").assertDoesNotExist()
        composeRule.onNodeWithTag("transport_footer").assertDoesNotExist()

        val viewportBounds = composeRule.onNodeWithTag("webpage_viewport_host").getUnclippedBoundsInRoot()
        val railsBounds = composeRule.onNodeWithTag("perimeter_steel_rails").getUnclippedBoundsInRoot()
        assertEquals(0.dp, viewportBounds.top)
        assertEquals(0.dp, railsBounds.top)

        val backBounds = composeRule.onNodeWithTag("kpt_back_pod").getUnclippedBoundsInRoot()
        val crtBounds = composeRule.onNodeWithTag("crt_address_pod").getUnclippedBoundsInRoot()
        val orbBounds = composeRule.onNodeWithTag("tor_orb_pod").getUnclippedBoundsInRoot()
        val pgpBounds = composeRule.onNodeWithTag("pgp_rocker_pod").getUnclippedBoundsInRoot()
        val remoteBounds = composeRule.onNodeWithTag("side_remote_pod").getUnclippedBoundsInRoot()
        assertTrue(backBounds.width > backBounds.height * 1.3f)
        assertTrue(crtBounds.height >= 125.dp)
        assertTrue(orbBounds.right < crtBounds.left)
        assertTrue(backBounds.bottom < orbBounds.top)
        assertTrue(pgpBounds.bottom < remoteBounds.top)

        // 2. Side Remote -> Onion Sites drawer + Settings Hardware Pod persists Theme Family & Appearance Mode
        composeRule.onNodeWithTag("side_remote_collapsed_face").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("remote_onion_sites").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("remote_onion_directory").assertIsDisplayed()
        composeRule.onNodeWithTag("remote_switch_to_menu").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("remote_settings").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_sheet").assertIsDisplayed()

        composeRule.onNodeWithText("Neon Graffiti").performClick()
        composeRule.waitForIdle()
        assertEquals(TrapThemeFamily.NeonGraffiti, prefs.themeFamily)

        composeRule.onNodeWithText("Light").performClick()
        composeRule.waitForIdle()
        assertEquals(TrapThemeMode.Light, prefs.themeMode)

        composeRule.onNodeWithText("SecureDrop").performClick()
        composeRule.onNodeWithText("Dark").performClick()
        composeRule.waitForIdle()
        assertEquals(TrapThemeFamily.SecureDrop, prefs.themeFamily)
        assertEquals(TrapThemeMode.Dark, prefs.themeMode)

        composeRule.onNodeWithTag("sheet_close_button").performClick()
        composeRule.waitForIdle()

        // 3. TorKnurledOrbPod -> PhotoSoapSideRemote TorCircuit mode -> verify .onion-Only Lock toggle
        composeRule.onNodeWithTag("tor_orb_pod").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("remote_tor_circuit").assertIsDisplayed()

        val initialHiddenOnly = prefs.hiddenServiceOnly
        composeRule.onNodeWithTag("remote_onion_only_toggle").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(!initialHiddenOnly, prefs.hiddenServiceOnly)
        if (OvNative.isLoaded) {
            val nativeStatus = OvNative.parseTorStatus(OvNative.nativeTorStatusJson("about:blank"))
            assertEquals(!initialHiddenOnly, nativeStatus.hiddenServiceOnly)
        }

        // Ensure Hidden Service Only = false (default clearnet consent mode)
        if (prefs.hiddenServiceOnly) {
            composeRule.onNodeWithTag("remote_onion_only_toggle").performScrollTo().performClick()
            composeRule.waitForIdle()
        }
        assertFalse(prefs.hiddenServiceOnly)
        composeRule.onNodeWithTag("remote_close_button").performClick()
        composeRule.waitForIdle()

        // 4. Upward pull on RecessedCrtAddressPod -> SpatialTabSwitcherPod -> New Tab
        composeRule.onNodeWithTag("crt_address_pod").performTouchInput {
            down(center)
            moveBy(Offset(0f, -40.dp.toPx()))
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tabs_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("tabs_new_tab").performClick()
        composeRule.waitForIdle()

        // 5. OpenPgpRockerPod: 1 click opens a new tab; 2 clicks open KeyringHardwarePod
        composeRule.onNodeWithTag("pgp_rocker_pod").performClick()
        android.os.SystemClock.sleep(320L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("keyring_sheet").assertDoesNotExist()

        if (OvNative.isLoaded) {
            val beforeCount = OvNative.parseKeys(OvNative.nativeListKeysJson()).size
            composeRule.onNodeWithTag("pgp_rocker_pod").performTouchInput { doubleClick() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("keyring_sheet").assertIsDisplayed()

            composeRule.onNodeWithText("Generate").performClick()
            composeRule.onNodeWithTag("keygen_userid_input").performTextInput("E2E Test <e2e@onion>")
            Espresso.closeSoftKeyboard()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("keygen_submit_button").performClick()

            composeRule.waitUntil(timeoutMillis = 15_000L) {
                OvNative.parseKeys(OvNative.nativeListKeysJson()).size > beforeCount
            }
            val afterKeys = OvNative.parseKeys(OvNative.nativeListKeysJson())
            val created = afterKeys.first { it.userId.contains("e2e@onion") }
            assertFalse(created.fingerprint.isBlank())

            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Copy PubKey").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithText("Copy PubKey")[0].performClick()
            composeRule.waitForIdle()

            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            assertTrue(clipText.contains("BEGIN PGP PUBLIC KEY BLOCK"))

            OvNative.nativeDeleteKey(created.fingerprint)
            composeRule.onNodeWithTag("sheet_close_button").performClick()
            composeRule.waitForIdle()
        }

        // 6. ClearnetConsentHardwarePod E2E: `tor.taxi` -> `*.tor.taxi` -> Allow for 3 days -> Revoke
        if (OvNative.isLoaded) {
            try {
                OvNative.nativeClearClearnetRules()
                composeRule.onNodeWithTag("crt_address_pod").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("crt_address_input").performTextInput("tor.taxi")
                composeRule.onNodeWithTag("crt_address_input").performImeAction()

                composeRule.waitUntil(timeoutMillis = 10_000L) {
                    composeRule.onAllNodesWithTag("clearnet_consent_sheet")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithTag("status_notice_banner").assertDoesNotExist()
                composeRule.onNodeWithTag("clearnet_consent_sheet").assertIsDisplayed()
                composeRule.onNodeWithTag("clearnet_pattern_input").assertTextEquals("*.tor.taxi")

                // One tap allows *.tor.taxi for 3 days and navigates to https://tor.taxi
                composeRule.onNodeWithTag("clearnet_allow_3d_button").performClick()
                composeRule.waitUntil(timeoutMillis = 10_000L) {
                    OvNative.parseClearnetRules(OvNative.nativeListClearnetRulesJson())
                        .any { it.pattern == "*.tor.taxi" && !it.sessionOnly } &&
                        composeRule.onAllNodesWithTag("clearnet_consent_sheet")
                            .fetchSemanticsNodes()
                            .isEmpty()
                }
                composeRule.onNodeWithText("tor.taxi").assertIsDisplayed()

                // Verify KptBackButtonPod (`kpt_back_pod`) + TabSessionManager.goBack() returns cleanly to StartPage!
                composeRule.onNodeWithTag("kpt_back_pod").performClick()
                composeRule.waitUntil(timeoutMillis = 10_000L) {
                    composeRule.onAllNodesWithTag("start_page")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithTag("start_page").assertIsDisplayed()

                // Custom glob editing + Revoke via TorKnurledOrbPod -> PhotoSoapSideRemote TorCircuit
                composeRule.onNodeWithTag("side_remote_collapsed_face").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("remote_new_tab").performScrollTo().performClick()
                composeRule.waitForIdle()

                composeRule.onNodeWithTag("crt_address_pod").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("crt_address_input").performTextInput("sub.example.org")
                composeRule.onNodeWithTag("crt_address_input").performImeAction()
                composeRule.waitUntil(timeoutMillis = 10_000L) {
                    composeRule.onAllNodesWithTag("clearnet_consent_sheet")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithTag("clearnet_pattern_input")
                    .performTextReplacement("*example.org")
                Espresso.closeSoftKeyboard()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("clearnet_allow_3d_button").performClick()
                composeRule.waitUntil(timeoutMillis = 10_000L) {
                    OvNative.parseClearnetRules(OvNative.nativeListClearnetRulesJson())
                        .any { it.pattern == "*example.org" } &&
                        composeRule.onAllNodesWithTag("clearnet_consent_sheet")
                            .fetchSemanticsNodes()
                            .isEmpty()
                }
                composeRule.waitForIdle()

                composeRule.onNodeWithTag("tor_orb_pod").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("remote_tor_circuit")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithTag("remote_tor_circuit").assertIsDisplayed()
                composeRule.onNodeWithTag("clearnet_revoke_*example.org")
                    .performScrollTo()
                    .performClick()
                composeRule.waitUntil(timeoutMillis = 10_000L) {
                    OvNative.parseClearnetRules(OvNative.nativeListClearnetRulesJson())
                        .none { it.pattern == "*example.org" }
                }
                composeRule.onNodeWithTag("remote_close_button").performClick()
                composeRule.waitForIdle()
            } finally {
                OvNative.nativeClearClearnetRules()
            }
        }
    }

    @Test
    fun zero_js_cdp_dom_pgp_insert_encrypt_and_decrypt_on_live_cefpack_session() {
        val activity = composeRule.activity
        assertTrue("Native libonion_vault.so must be loaded", OvNative.isLoaded)
        assertTrue("Cefpack 0.2.0 engine must be ready", TrapApplication.isEngineReady)

        // Ensure biometric prompt is off for unattended instrumentation execution.
        OvNative.nativeSetRequireBiometric(false)
        val prefs = TrapPreferences(activity)
        prefs.requireBiometric = false

        // Generate a dedicated Sequoia OpenPGP own keypair via the real KeyringHardwarePod UI
        // (opened via OpenPgpRockerPod double-click) so MainActivity refreshes uiState.keys end-to-end.
        val beforeKeys = OvNative.parseKeys(OvNative.nativeListKeysJson())
        val beforeFingerprints = beforeKeys.map { it.fingerprint }.toSet()

        composeRule.onNodeWithTag("pgp_rocker_pod").performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("keyring_sheet").assertIsDisplayed()
        composeRule.onNodeWithText("Generate").performClick()
        composeRule.onNodeWithTag("keygen_userid_input").performTextInput("ZeroJS Signer <zerojs@onion>")
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("keygen_submit_button").performClick()

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            OvNative.parseKeys(OvNative.nativeListKeysJson()).any {
                it.userId.contains("zerojs@onion") && it.fingerprint !in beforeFingerprints
            } && composeRule.onAllNodesWithText("ZeroJS Signer <zerojs@onion>")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val createdKey = OvNative.parseKeys(OvNative.nativeListKeysJson()).first {
            it.userId.contains("zerojs@onion") && it.fingerprint !in beforeFingerprints
        }
        assertFalse(createdKey.fingerprint.isBlank())

        try {
            val setDefaultNodes = composeRule.onAllNodesWithText("Set Default")
            if (setDefaultNodes.fetchSemanticsNodes().isNotEmpty()) {
                setDefaultNodes[0].performClick()
                composeRule.waitForIdle()
            }
            composeRule.onNodeWithTag("sheet_close_button").performClick()
            composeRule.waitForIdle()

            // Wait for live CefpackSession to attach on the active tab.
            composeRule.waitUntil(timeoutMillis = 15_000L) {
                activity.activeCefpackSessionForTest() != null
            }
            val browser = activity.activeCefpackSessionForTest()
            assertNotNull(browser)

            val testOnionUrl =
                "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/drop"
            val zeroJsHtml = """
                <!doctype html>
                <html>
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <style>
                    body { margin: 64px 16px 24px 16px; background: #14100C; color: #E8DDD0; font-family: sans-serif; }
                    textarea { display: block; width: 92%; min-height: 112px; margin-bottom: 44px; font-size: 14px; }
                  </style>
                  <script>
                    document.documentElement.setAttribute('data-js-ran', '1');
                  </script>
                </head>
                <body>
                  <form method="POST" action="/submit">
                    <textarea id="pub" name="pgp_public_key" rows="4" placeholder="Paste PGP public key"></textarea>
                    <textarea id="msg" name="message" rows="5" placeholder="Write secret message">Plaintext secret drop</textarea>
                  </form>
                  <script>
                    document.getElementById('msg').value = 'JS_MUTATED';
                  </script>
                </body>
                </html>
            """.trimIndent()

            val loadLatch = CountDownLatch(1)
            val loadOk = AtomicReference(false)
            activity.loadZeroJsDocumentForTest(zeroJsHtml, testOnionUrl) { ok ->
                loadOk.set(ok)
                loadLatch.countDown()
            }
            assertTrue("Page.setDocumentContent timed out", loadLatch.await(15, TimeUnit.SECONDS))
            assertTrue("Page.setDocumentContent failed", loadOk.get())

            // 1. Verify CdpDomPgpController detected both fields and rendered Compose PGP pills.
            composeRule.waitUntil(timeoutMillis = 15_000L) {
                composeRule.onAllNodesWithText("Encrypt Field").fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithText("Insert My PubKey").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("pgp_field_overlay").assertIsDisplayed()

            // 2. Verify 100% zero JavaScript execution inside live CefpackSession via CDP DOM.getDocument.
            val initialDom = queryPiercedDomJson(activity, browser!!)
            assertFalse(
                "Page <script> must NEVER execute",
                initialDom.contains("data-js-ran") && initialDom.contains("\"1\""),
            )
            assertEquals("Plaintext secret drop", readControlInnerEditorById(initialDom, "msg").trim())

            // 3. Tap "Insert My PubKey" on #pub (first pill) -> verify zero top banner & Blink #inner-editor read-back.
            composeRule.onAllNodesWithText("Insert My PubKey")[0].performClick()
            val pubFieldValue = awaitControlInnerEditorContains(
                activity = activity,
                browser = browser,
                elementId = "pub",
                expectedSubstring = "BEGIN PGP PUBLIC KEY BLOCK",
                timeoutMs = 15_000L,
            )
            composeRule.onNodeWithTag("status_notice_banner").assertDoesNotExist()
            val debugDomAfterPub = queryPiercedDomJson(activity, browser)
            val debugMsgAfterPub = readControlInnerEditorById(debugDomAfterPub, "msg")
            assertTrue(
                "Expected PGP public key in #pub, got pub=[${pubFieldValue.take(90)}] msg=[${debugMsgAfterPub.take(90)}] domLen=${debugDomAfterPub.length}",
                pubFieldValue.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----"),
            )
            assertTrue(pubFieldValue.contains("-----END PGP PUBLIC KEY BLOCK-----"))
            assertFalse(
                "Blink placeholder pseudo-element must not pollute #inner-editor value",
                pubFieldValue.contains("Paste PGP public key"),
            )

            // 4. Tap "Encrypt Field" on #msg -> EncryptRecipientHardwarePod -> explicitly select key -> Sign & Encrypt.
            composeRule.onNodeWithText("Encrypt Field").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("encrypt_picker_sheet").assertIsDisplayed()
            composeRule.onNodeWithTag("encrypt_recipient_${createdKey.fingerprint}")
                .performScrollTo()
                .performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("encrypt_confirm_button").performClick()

            // Wait for CdpDomPgpController to write ciphertext, verify Blink #inner-editor read-back,
            // and re-classify #msg from "Encrypt Field" into "Decrypt Block".
            composeRule.waitUntil(timeoutMillis = 20_000L) {
                composeRule.onAllNodesWithText("Decrypt Block").fetchSemanticsNodes().isNotEmpty()
            }
            val encryptedFieldValue =
                readControlInnerEditorById(queryPiercedDomJson(activity, browser), "msg")
            assertTrue(encryptedFieldValue.contains("-----BEGIN PGP MESSAGE-----"))
            assertTrue(encryptedFieldValue.contains("-----END PGP MESSAGE-----"))
            assertFalse(encryptedFieldValue.contains("Plaintext secret drop"))

            // 5. Drag OpenPgpRockerPod latch upward (`dy < -18dp`) -> quick-decrypts primaryArmoredBlock
            //    into DecryptedPlaintextHardwarePod with verified Sequoia signer identity & fingerprint!
            composeRule.onNodeWithTag("pgp_rocker_pod").performTouchInput {
                down(center)
                moveBy(Offset(0f, -26.dp.toPx()))
                up()
            }
            composeRule.waitUntil(timeoutMillis = 15_000L) {
                composeRule.onAllNodesWithText("Plaintext secret drop").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("plaintext_sheet").assertIsDisplayed()
            composeRule.onNodeWithText("Plaintext secret drop").assertIsDisplayed()
            composeRule.onNodeWithText("GOOD SIG · ZeroJS Signer <zerojs@onion>")
                .assertIsDisplayed()
            composeRule.onNodeWithText("KEY · ${createdKey.fingerprint.takeLast(16)}")
                .assertIsDisplayed()

            // Closing DecryptedPlaintextHardwarePod must leave ciphertext intact in DOM (never leaking plaintext to page).
            composeRule.onNodeWithTag("plaintext_close").performClick()
            composeRule.waitForIdle()
            val postDecryptDomValue =
                readControlInnerEditorById(queryPiercedDomJson(activity, browser), "msg")
            assertTrue(postDecryptDomValue.contains("-----BEGIN PGP MESSAGE-----"))
            assertFalse(postDecryptDomValue.contains("Plaintext secret drop"))
        } finally {
            OvNative.nativeDeleteKey(createdKey.fingerprint)
            if (prefs.defaultKeyFingerprint == createdKey.fingerprint) {
                prefs.defaultKeyFingerprint = ""
            }
        }
    }

    private fun awaitControlInnerEditorContains(
        activity: MainActivity,
        browser: CefpackSession,
        elementId: String,
        expectedSubstring: String,
        timeoutMs: Long,
    ): String {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        var lastValue = ""
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            lastValue = readControlInnerEditorById(queryPiercedDomJson(activity, browser), elementId)
            if (lastValue.contains(expectedSubstring)) {
                return lastValue
            }
            android.os.SystemClock.sleep(150L)
        }
        return lastValue
    }

    private fun queryPiercedDomJson(
        activity: MainActivity,
        browser: CefpackSession,
    ): String {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference("")
        activity.runOnUiThread {
            browser.executeDevToolsMethod(
                "DOM.getDocument",
                "{\"depth\":-1,\"pierce\":true}",
            ) { ok, json ->
                if (ok && !json.isNullOrBlank()) {
                    resultRef.set(json)
                }
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return resultRef.get()
    }

    private fun readControlInnerEditorById(rawDocJson: String, elementId: String): String {
        if (rawDocJson.isBlank()) return ""
        val root = runCatching { JSONObject(rawDocJson).optJSONObject("root") }.getOrNull()
            ?: return ""
        val target = findElementById(root, elementId) ?: return ""
        val shadowRoots = target.optJSONArray("shadowRoots")
        if (shadowRoots != null && shadowRoots.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until shadowRoots.length()) {
                val sr = shadowRoots.optJSONObject(i) ?: continue
                val innerEditor = findElementById(sr, "inner-editor")
                collectTextNodes(innerEditor ?: sr, sb)
            }
            if (sb.isNotEmpty()) return sb.toString()
        }
        val childSb = StringBuilder()
        collectTextNodes(target, childSb)
        if (childSb.isNotEmpty()) return childSb.toString()
        return extractAttribute(target.optJSONArray("attributes"), "value")
    }

    private fun findElementById(node: JSONObject, targetId: String): JSONObject? {
        if (node.optInt("nodeType") == 1) {
            val idVal = extractAttribute(node.optJSONArray("attributes"), "id")
            if (idVal == targetId) return node
        }
        val children = node.optJSONArray("children")
        if (children != null) {
            for (i in 0 until children.length()) {
                val child = children.optJSONObject(i) ?: continue
                findElementById(child, targetId)?.let { return it }
            }
        }
        val shadowRoots = node.optJSONArray("shadowRoots")
        if (shadowRoots != null) {
            for (i in 0 until shadowRoots.length()) {
                val sr = shadowRoots.optJSONObject(i) ?: continue
                findElementById(sr, targetId)?.let { return it }
            }
        }
        node.optJSONObject("contentDocument")?.let { doc ->
            findElementById(doc, targetId)?.let { return it }
        }
        return null
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

    private fun collectTextNodes(node: JSONObject, out: StringBuilder) {
        if (node.optInt("nodeType") == 1) {
            val pseudo = extractAttribute(node.optJSONArray("attributes"), "pseudo")
            if (pseudo.contains("placeholder", ignoreCase = true)) {
                return
            }
        }
        val nodeType = node.optInt("nodeType")
        if (nodeType == 3) {
            out.append(node.optString("nodeValue", ""))
        } else if (nodeType == 1 && node.optString("nodeName").equals("BR", ignoreCase = true)) {
            out.append('\n')
        }
        val children = node.optJSONArray("children")
        if (children != null) {
            for (i in 0 until children.length()) {
                val child = children.optJSONObject(i) ?: continue
                collectTextNodes(child, out)
            }
        }
    }
}
