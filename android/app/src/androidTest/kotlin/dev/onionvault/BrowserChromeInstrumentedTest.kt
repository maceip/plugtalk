package dev.onionvault

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * Exercises live [MainActivity], single-row [BrowserScreen] chrome, minimal [StartPage],
 * [ClearnetConsentSheet] (3-day TTL persistence, custom `*` globbing, and rule revocation),
 * [TrapPreferences], [TabSessionManager], and [OvNative] (Arti Tor + Sequoia OpenPGP).
 */
@RunWith(AndroidJUnit4::class)
class BrowserChromeInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun chrome_settings_security_tabs_clearnet_consent_and_native_keyring_e2e() {
        val activity = composeRule.activity
        val prefs = TrapPreferences(activity)

        if (OvNative.isLoaded) {
            OvNative.nativeClearClearnetRules()
        }

        // 1. Minimal StartPage + single-row bottom dock (~56.dp content height, zero top bar)
        composeRule.onNodeWithTag("browser_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("start_page").assertIsDisplayed()
        composeRule.onNodeWithTag("start_tor_status").assertIsDisplayed()
        composeRule.onNodeWithTag("top_identity_header").assertDoesNotExist()
        composeRule.onNodeWithTag("transport_footer").assertDoesNotExist()
        composeRule.onNodeWithTag("start_security_card").assertDoesNotExist()
        composeRule.onNodeWithTag("start_ddg_onion_card").assertDoesNotExist()
        composeRule.onNodeWithText("Launch").assertDoesNotExist()
        composeRule.onNodeWithTag("address_input").assertTextEquals("Enter address", "")

        val dockHeight = composeRule.onNodeWithTag("bottom_control_dock")
            .getUnclippedBoundsInRoot()
            .height
        assertTrue(
            "Expected single-row bottom_control_dock content height < 90dp, got $dockHeight",
            dockHeight >= 48.dp && dockHeight < 90.dp,
        )

        // 2. Menu -> Settings Sheet persists Theme Family & Appearance Mode
        composeRule.onNodeWithTag("nav_menu").performClick()
        composeRule.onNodeWithTag("browser_menu_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("menu_settings").performScrollTo().performClick()
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

        // 3. Tor Shield -> Security & Circuit Sheet -> verify Hidden Service Only toggle
        composeRule.onNodeWithTag("address_security_button").performClick()
        composeRule.onNodeWithTag("security_circuit_sheet").assertIsDisplayed()

        val initialHiddenOnly = prefs.hiddenServiceOnly
        composeRule.onNodeWithTag("security_hidden_service_switch").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(!initialHiddenOnly, prefs.hiddenServiceOnly)
        if (OvNative.isLoaded) {
            val nativeStatus = OvNative.parseTorStatus(OvNative.nativeTorStatusJson("about:blank"))
            assertEquals(!initialHiddenOnly, nativeStatus.hiddenServiceOnly)
        }

        // Ensure Hidden Service Only = false (default clearnet consent mode)
        if (prefs.hiddenServiceOnly) {
            composeRule.onNodeWithTag("security_hidden_service_switch").performScrollTo().performClick()
            composeRule.waitForIdle()
        }
        assertFalse(prefs.hiddenServiceOnly)
        composeRule.onNodeWithTag("sheet_close_button").performClick()
        composeRule.waitForIdle()

        // 4. Multi-Tab Sheet: create tab, verify sheet, close sheet
        composeRule.onNodeWithTag("nav_tabs").performClick()
        composeRule.onNodeWithTag("tabs_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("tabs_new_tab").performClick()
        composeRule.waitForIdle()

        // 5. Keyring Sheet via Menu -> PGP Keyring: generate real Sequoia keypair & Copy PubKey
        if (OvNative.isLoaded) {
            val beforeCount = OvNative.parseKeys(OvNative.nativeListKeysJson()).size
            composeRule.onNodeWithTag("nav_menu").performClick()
            composeRule.onNodeWithTag("menu_keyring").performScrollTo().performClick()
            composeRule.onNodeWithTag("keyring_sheet").assertIsDisplayed()

            composeRule.onNodeWithText("Generate").performClick()
            composeRule.onNodeWithTag("keygen_userid_input").performTextInput("E2E Test <e2e@onion>")
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

        // 6. Clearnet Consent Interstitial Sheet E2E: `tor.taxi` -> `*.tor.taxi` -> Allow for 3 days -> Revoke
        if (OvNative.isLoaded) {
            try {
                OvNative.nativeClearClearnetRules()
                composeRule.onNodeWithTag("address_input").performTextInput("tor.taxi")
                composeRule.onNodeWithTag("address_go").performClick()

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
                composeRule.onNodeWithTag("address_input").assertTextEquals("https://tor.taxi")

                // Custom glob editing + Revoke via SecurityCircuitSheet
                composeRule.onNodeWithTag("nav_tabs").performClick()
                composeRule.onNodeWithTag("tabs_new_tab").performClick()
                composeRule.waitForIdle()

                composeRule.onNodeWithTag("address_input").performTextInput("sub.example.org")
                composeRule.onNodeWithTag("address_go").performClick()
                composeRule.waitUntil(timeoutMillis = 10_000L) {
                    composeRule.onAllNodesWithTag("clearnet_consent_sheet")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithTag("clearnet_pattern_input")
                    .performTextReplacement("*example.org")
                composeRule.onNodeWithTag("clearnet_allow_3d_button").performClick()
                composeRule.waitUntil(timeoutMillis = 10_000L) {
                    OvNative.parseClearnetRules(OvNative.nativeListClearnetRulesJson())
                        .any { it.pattern == "*example.org" } &&
                        composeRule.onAllNodesWithTag("clearnet_consent_sheet")
                            .fetchSemanticsNodes()
                            .isEmpty()
                }
                composeRule.waitForIdle()

                composeRule.onNodeWithTag("address_security_button").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("security_circuit_sheet")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithTag("security_circuit_sheet").assertIsDisplayed()
                composeRule.onNodeWithTag("clearnet_revoke_*example.org")
                    .performScrollTo()
                    .performClick()
                composeRule.waitUntil(timeoutMillis = 10_000L) {
                    OvNative.parseClearnetRules(OvNative.nativeListClearnetRulesJson())
                        .none { it.pattern == "*example.org" }
                }
                composeRule.onNodeWithTag("sheet_close_button").performClick()
                composeRule.waitForIdle()
            } finally {
                OvNative.nativeClearClearnetRules()
            }
        }
    }

    @Test
    fun zero_js_cdp_dom_pgp_insert_encrypt_and_decrypt_on_live_cefrium_browser() {
        val activity = composeRule.activity
        assertTrue("Native libonion_vault.so must be loaded", OvNative.isLoaded)
        assertTrue("Cefrium 0.19.0 engine must be ready", TrapApplication.isEngineReady)

        // Ensure biometric prompt is off for unattended instrumentation execution.
        OvNative.nativeSetRequireBiometric(false)
        val prefs = TrapPreferences(activity)
        prefs.requireBiometric = false

        // Generate a dedicated Sequoia OpenPGP own keypair via the real KeyringSheet UI
        // (opened via Menu -> PGP Keyring) so MainActivity refreshes uiState.keys end-to-end.
        val beforeKeys = OvNative.parseKeys(OvNative.nativeListKeysJson())
        val beforeFingerprints = beforeKeys.map { it.fingerprint }.toSet()

        composeRule.onNodeWithTag("nav_menu").performClick()
        composeRule.onNodeWithTag("menu_keyring").performScrollTo().performClick()
        composeRule.onNodeWithTag("keyring_sheet").assertIsDisplayed()
        composeRule.onNodeWithText("Generate").performClick()
        composeRule.onNodeWithTag("keygen_userid_input").performTextInput("ZeroJS Signer <zerojs@onion>")
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

            // Wait for live CefriumBrowser to attach on the active tab.
            composeRule.waitUntil(timeoutMillis = 15_000L) {
                activity.activeCefriumBrowserForTest() != null
            }
            val browser = activity.activeCefriumBrowserForTest()
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

            // 2. Verify 100% zero JavaScript execution inside live CefriumBrowser via CDP DOM.getDocument.
            val initialDom = queryPiercedDomJson(activity, browser!!)
            assertFalse(
                "Page <script> must NEVER execute",
                initialDom.contains("data-js-ran") && initialDom.contains("\"1\""),
            )
            assertEquals("Plaintext secret drop", readControlInnerEditorById(initialDom, "msg").trim())

            // 3. Tap "Insert My PubKey" on #pub (first pill) -> verify status banner & Blink #inner-editor read-back.
            composeRule.onAllNodesWithText("Insert My PubKey")[0].performClick()
            composeRule.waitUntil(timeoutMillis = 15_000L) {
                composeRule.onAllNodesWithText("Inserted public key and verified DOM field")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val pubFieldValue = awaitControlInnerEditorContains(
                activity = activity,
                browser = browser,
                elementId = "pub",
                expectedSubstring = "BEGIN PGP PUBLIC KEY BLOCK",
                timeoutMs = 10_000L,
            )
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

            // 4. Tap "Encrypt Field" on #msg -> EncryptPickerSheet -> Sign & Encrypt -> verify #inner-editor.
            composeRule.onNodeWithText("Encrypt Field").performClick()
            composeRule.onNodeWithTag("encrypt_picker_sheet").assertIsDisplayed()
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

            // 5. Tap "Decrypt Block" -> native PlaintextSheet with verified Sequoia signer identity & fingerprint.
            composeRule.onNodeWithText("Decrypt Block").performClick()
            composeRule.waitUntil(timeoutMillis = 15_000L) {
                composeRule.onAllNodesWithText("Plaintext secret drop").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("plaintext_sheet").assertIsDisplayed()
            composeRule.onNodeWithText("Plaintext secret drop").assertIsDisplayed()
            composeRule.onNodeWithText("Good signature · ZeroJS Signer <zerojs@onion>")
                .assertIsDisplayed()
            composeRule.onNodeWithText("Key ID · ${createdKey.fingerprint.takeLast(16)}")
                .assertIsDisplayed()

            // Closing PlaintextSheet must leave ciphertext intact in DOM (never leaking plaintext to page).
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
        browser: com.cefrium.CefriumBrowser,
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
        browser: com.cefrium.CefriumBrowser,
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
        val shadows = target.optJSONArray("shadowRoots")
        if (shadows != null && shadows.length() > 0) {
            for (i in 0 until shadows.length()) {
                val shadow = shadows.optJSONObject(i) ?: continue
                val editor = findElementById(shadow, "inner-editor")
                if (editor != null) {
                    return collectNonPlaceholderTextNodes(editor)
                }
            }
            val shadowText = StringBuilder()
            for (i in 0 until shadows.length()) {
                val shadow = shadows.optJSONObject(i) ?: continue
                shadowText.append(collectNonPlaceholderTextNodes(shadow))
            }
            return shadowText.toString()
        }
        return collectNonPlaceholderTextNodes(target)
    }

    private fun findElementById(node: JSONObject, targetId: String): JSONObject? {
        if (node.optInt("nodeType", 0) == 1) {
            val attrs = node.optJSONArray("attributes")
            if (attrs != null) {
                var i = 0
                while (i + 1 < attrs.length()) {
                    if (attrs.optString(i).equals("id", ignoreCase = true) &&
                        attrs.optString(i + 1) == targetId
                    ) {
                        return node
                    }
                    i += 2
                }
            }
        }
        val children: JSONArray? = node.optJSONArray("children")
        if (children != null) {
            for (i in 0 until children.length()) {
                val child = children.optJSONObject(i) ?: continue
                findElementById(child, targetId)?.let { return it }
            }
        }
        val shadows: JSONArray? = node.optJSONArray("shadowRoots")
        if (shadows != null) {
            for (i in 0 until shadows.length()) {
                val shadow = shadows.optJSONObject(i) ?: continue
                findElementById(shadow, targetId)?.let { return it }
            }
        }
        return null
    }

    private fun isPlaceholderNode(node: JSONObject): Boolean {
        if (node.optInt("nodeType", 0) != 1) return false
        val attrs = node.optJSONArray("attributes") ?: return false
        var i = 0
        while (i + 1 < attrs.length()) {
            val k = attrs.optString(i)
            val v = attrs.optString(i + 1)
            if (k.equals("id", ignoreCase = true) && v.equals("placeholder", ignoreCase = true)) {
                return true
            }
            if (k.equals("pseudo", ignoreCase = true) && v.contains("placeholder", ignoreCase = true)) {
                return true
            }
            i += 2
        }
        return false
    }

    private fun collectNonPlaceholderTextNodes(node: JSONObject): String {
        val sb = StringBuilder()
        fun walk(cur: JSONObject) {
            if (isPlaceholderNode(cur)) return
            val type = cur.optInt("nodeType", 0)
            if (type == 3) {
                sb.append(cur.optString("nodeValue", ""))
                return
            }
            if (type == 1 && cur.optString("localName", "").equals("br", ignoreCase = true)) {
                sb.append('\n')
                return
            }
            val kids = cur.optJSONArray("children") ?: return
            for (i in 0 until kids.length()) {
                val child = kids.optJSONObject(i) ?: continue
                walk(child)
            }
        }
        walk(node)
        return sb.toString()
    }
}
