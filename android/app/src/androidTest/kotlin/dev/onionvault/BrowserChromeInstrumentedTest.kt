package dev.onionvault

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.onionvault.ui.DUCKDUCKGO_ONION_URL
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
 * End-to-end instrumentation suite running on a real Android device / emulator.
 *
 * Exercises live [MainActivity], [BrowserScreen], [TrapPreferences], [TabSessionManager],
 * and [OvNative] (Arti Tor status/policy + Sequoia Android Keystore OpenPGP keyring).
 */
@RunWith(AndroidJUnit4::class)
class BrowserChromeInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun chrome_settings_security_tabs_and_native_keyring_e2e() {
        val activity = composeRule.activity
        val prefs = TrapPreferences(activity)

        // 1. StartPage renders on about:blank with empty EditableText + honest .onion placeholder
        composeRule.onNodeWithTag("browser_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("start_page").assertIsDisplayed()
        composeRule.onNodeWithTag("address_input").assertTextEquals("Enter .onion address", "")

        // 2. Settings Sheet persists Theme Family, Mode, and Default Zoom
        composeRule.onNodeWithTag("start_open_settings").performScrollTo().performClick()
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

        // 3. TOR Pill -> Security & Circuit Sheet -> toggle Hidden Service Only -> native state transition
        composeRule.onNodeWithTag("top_tor_pill").performClick()
        composeRule.onNodeWithTag("security_circuit_sheet").assertIsDisplayed()

        val initialHiddenOnly = prefs.hiddenServiceOnly
        composeRule.onNodeWithTag("security_hidden_service_switch").performClick()
        composeRule.waitForIdle()
        assertEquals(!initialHiddenOnly, prefs.hiddenServiceOnly)
        if (OvNative.isLoaded) {
            val nativeStatus = OvNative.parseTorStatus(OvNative.nativeTorStatusJson("about:blank"))
            assertEquals(!initialHiddenOnly, nativeStatus.hiddenServiceOnly)
        }

        // Restore Hidden Service Only = true
        if (!prefs.hiddenServiceOnly) {
            composeRule.onNodeWithTag("security_hidden_service_switch").performClick()
            composeRule.waitForIdle()
        }
        assertTrue(prefs.hiddenServiceOnly)
        composeRule.onNodeWithTag("sheet_close_button").performClick()
        composeRule.waitForIdle()

        // 4. Multi-Tab Sheet: create tab, verify tab count, close sheet
        composeRule.onNodeWithTag("nav_tabs").performClick()
        composeRule.onNodeWithTag("tabs_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("tabs_new_tab").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_tabs").performClick()
        composeRule.onNodeWithTag("tabs_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet_close_button").performClick()
        composeRule.waitForIdle()

        // 5. Keyring Sheet: generate real Sequoia OpenPGP keypair & export public key to clipboard
        if (OvNative.isLoaded) {
            val beforeCount = OvNative.parseKeys(OvNative.nativeListKeysJson()).size
            composeRule.onNodeWithTag("top_keyring_button").performClick()
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

            // Clean up generated test key
            OvNative.nativeDeleteKey(created.fingerprint)
            composeRule.onNodeWithTag("sheet_close_button").performClick()
            composeRule.waitForIdle()
        }

        // 6. Hidden Service Only policy blocks clearnet URL & StartPage launches v3 .onion address
        composeRule.onNodeWithTag("address_input").performTextInput("https://example.com")
        composeRule.onNodeWithTag("address_go").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("status_notice_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("address_clear").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("start_ddg_onion_card").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("address_input").assertTextEquals(DUCKDUCKGO_ONION_URL)
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

        // Generate a dedicated Sequoia OpenPGP own keypair in Android Keystore.
        val keyGen = OvNative.parseGenerateKey(
            OvNative.nativeGenerateKey("ZeroJS Signer <zerojs@onion>", ""),
        )
        assertTrue("Key generation failed: ${keyGen.error}", keyGen.ok)
        assertFalse(keyGen.fingerprint.isBlank())

        try {
            // Set as default key via Keyring sheet so MainActivity uiState picks up the new key.
            composeRule.onNodeWithTag("top_keyring_button").performClick()
            composeRule.onNodeWithTag("keyring_sheet").assertIsDisplayed()
            composeRule.waitUntil(timeoutMillis = 10_000L) {
                composeRule.onAllNodesWithText("ZeroJS Signer <zerojs@onion>")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val setDefaultNodes = composeRule.onAllNodesWithText("Set Default")
            if (setDefaultNodes.fetchSemanticsNodes().isNotEmpty()) {
                setDefaultNodes[0].performClick()
                composeRule.waitForIdle()
            } else {
                prefs.defaultKeyFingerprint = keyGen.fingerprint
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
                    body { margin: 16px; background: #14100C; color: #E8DDD0; font-family: sans-serif; }
                    textarea { display: block; width: 92%; margin-bottom: 28px; font-size: 14px; }
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

            // 3. Tap "Insert My PubKey" on #pub (first pill) -> verify Blink #inner-editor read-back.
            composeRule.onAllNodesWithText("Insert My PubKey")[0].performClick()
            composeRule.waitUntil(timeoutMillis = 15_000L) {
                readControlInnerEditorById(queryPiercedDomJson(activity, browser), "pub")
                    .contains("BEGIN PGP PUBLIC KEY BLOCK")
            }
            val pubFieldValue = readControlInnerEditorById(queryPiercedDomJson(activity, browser), "pub")
            assertTrue(pubFieldValue.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
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
            composeRule.onNodeWithText("Key ID · ${keyGen.fingerprint.takeLast(16)}")
                .assertIsDisplayed()

            // Closing PlaintextSheet must leave ciphertext intact in DOM (never leaking plaintext to page).
            composeRule.onNodeWithTag("plaintext_close").performClick()
            composeRule.waitForIdle()
            val postDecryptDomValue =
                readControlInnerEditorById(queryPiercedDomJson(activity, browser), "msg")
            assertTrue(postDecryptDomValue.contains("-----BEGIN PGP MESSAGE-----"))
            assertFalse(postDecryptDomValue.contains("Plaintext secret drop"))
        } finally {
            OvNative.nativeDeleteKey(keyGen.fingerprint)
            if (prefs.defaultKeyFingerprint == keyGen.fingerprint) {
                prefs.defaultKeyFingerprint = ""
            }
        }
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
        if (shadows != null) {
            for (i in 0 until shadows.length()) {
                val shadow = shadows.optJSONObject(i) ?: continue
                val editor = findElementById(shadow, "inner-editor")
                if (editor != null) {
                    return collectTextNodes(editor)
                }
            }
        }
        return collectTextNodes(target)
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

    private fun collectTextNodes(node: JSONObject): String {
        val sb = StringBuilder()
        fun walk(cur: JSONObject) {
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
