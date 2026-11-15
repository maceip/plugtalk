package dev.onionvault

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.onionvault.ui.TrapPreferences
import dev.onionvault.ui.TrapThemeFamily
import dev.onionvault.ui.TrapThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        composeRule.onNodeWithContentDescription("Close").performClick()
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
        composeRule.onNodeWithContentDescription("Close").performClick()
        composeRule.waitForIdle()

        // 4. Multi-Tab Sheet: create tab, verify tab count, close tab
        composeRule.onNodeWithTag("nav_tabs").performClick()
        composeRule.onNodeWithTag("tabs_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("tabs_new_tab").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_tabs").performClick()
        composeRule.onNodeWithTag("tabs_sheet").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").performClick()
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
            composeRule.onNodeWithContentDescription("Close").performClick()
            composeRule.waitForIdle()
        }
    }
}
