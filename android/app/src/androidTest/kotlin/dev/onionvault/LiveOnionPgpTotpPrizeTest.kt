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
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
 * Mandatory live Tor v3 `.onion` 3-round OpenPGP TOTP Prize Gate (`TrapPrizeGate`).
 *
 * Cold-boots [MainActivity] with JavaScript completely disabled, waits for real embedded
 * Arti Tor bootstrap, generates a fresh ephemeral Sequoia OpenPGP keypair wrapped in
 * Android Keystore via `KeyringHardwarePod`, navigates over Tor to the DigitalOcean
 * OpenPGP TOTP `.onion` hidden service, inserts the public key via `"Insert My PubKey"`,
 * decrypts all 3 OpenPGP-encrypted TOTP challenge rounds through Trap's hardware UI
 * (`OpenPgpRockerPod` -> `DecryptedPlaintextHardwarePod`), submits all 3 answers via
 * zero-JS HTML `<form method="POST">` over Tor, verifies Round 3 prize unlock, displays
 * the decrypted OpenPGP Prize Certificate on screen, captures `/data/local/tmp/trap-prize-won.png`,
 * and logs `PRIZE_WON_JSON=...` for fail-closed verification.
 */
@RunWith(AndroidJUnit4::class)
class LiveOnionPgpTotpPrizeTest {

    companion object {
        const val TAG = "TrapPrizeGate"
        const val APP_PRIZE_SCREENSHOT_FILENAME = "prize-won.png"
        const val DEVICE_PRIZE_SCREENSHOT_PATH = "/data/local/tmp/trap-prize-won.png"

        /**
         * Live DigitalOcean Tor v3 `.onion` OpenPGP TOTP service URL (overridable via
         * `-e totpOnionUrl http://<56-char>.onion/`).
         */
        const val DEFAULT_TOTP_ONION_URL: String =
            "http://b36xrf3dwtizfea3z6pl2i2el2vzraju4gdddben5eqpicdswuvfgoyd.onion/"

        private val SIX_DIGIT_TOTP_REGEX = Regex("\\b(\\d{6})\\b")
    }

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun cold_boot_tor_three_round_openpgp_totp_unlocks_and_decrypts_live_onion_prize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val args = InstrumentationRegistry.getArguments()
        val targetOnionUrl = args.getString("totpOnionUrl")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_TOTP_ONION_URL
        val prizeHoldMs = args.getString("prizeHoldMs")
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(500L, 15_000L)
            ?: 2_500L

        assertTrue(
            "totpOnionUrl must be configured to a live v3 .onion URL",
            targetOnionUrl.isNotBlank(),
        )

        // Clear any stale crash or prize screenshot file from earlier sessions before cold-launching.
        CrashReporter.clear(appContext)
        runCatching { File(appContext.filesDir, APP_PRIZE_SCREENSHOT_FILENAME).delete() }
        wakeAndUnlockDisplay(instrumentation)

        val prefs = TrapPreferences(appContext)
        val previousBiometric = prefs.requireBiometric
        val previousDefaultFp = prefs.defaultKeyFingerprint
        prefs.requireBiometric = false
        if (OvNative.isLoaded) {
            OvNative.nativeSetRequireBiometric(false)
        }

        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(MainActivity.EXTRA_SHOW_OVER_KEYGUARD_FOR_TEST, true)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            val activityRef = AtomicReference<MainActivity>()
            scenario.onActivity { act ->
                act.setShowWhenLocked(true)
                act.setTurnScreenOn(true)
                act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activityRef.set(act)
            }
            val activity = activityRef.get()
            assertNotNull("MainActivity instance must be created", activity)

            assertTrue("Native libonion_vault.so must be loaded", OvNative.isLoaded)
            assertTrue("Cefpack content engine must be ready", TrapApplication.isEngineReady)
            OvNative.nativeSetRequireBiometric(false)

            // Strict fail-closed policy check: target MUST be a valid Tor v3 .onion URL (zero clearnet backdoors).
            val policy = OvNative.parseUrlPolicy(OvNative.nativeCheckUrlPolicy(targetOnionUrl, true))
            assertTrue(
                "Prize gate target URL must pass strict .onion-only policy ($targetOnionUrl): ${policy.reason}",
                policy.allowed && policy.isOnion,
            )

            // 1. Verify cold-start MetaToolsBrowserShell chrome & wait up to 90s for real Arti Tor bootstrap.
            composeRule.waitUntil(timeoutMillis = 15_000L) {
                composeRule.onAllNodesWithTag("browser_screen").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("browser_screen").assertIsDisplayed()
            composeRule.onNodeWithTag("metatools_browser_shell").assertIsDisplayed()
            composeRule.onNodeWithTag("crt_address_pod").assertIsDisplayed()
            composeRule.onNodeWithTag("pgp_rocker_pod").assertIsDisplayed()
            composeRule.onNodeWithTag("tor_orb_pod").assertIsDisplayed()

            val torBootDeadline = SystemClock.uptimeMillis() + 90_000L
            var lastTorSnapshot = OvNative.TorStatusSnapshot()
            var boundSocksPort = 0
            while (SystemClock.uptimeMillis() < torBootDeadline) {
                lastTorSnapshot = OvNative.parseTorStatus(OvNative.nativeTorStatusJson("about:blank"))
                val resolvedAddr = lastTorSnapshot.socksAddr.ifBlank { OvNative.nativeSocksProxy() }.trim()
                boundSocksPort = extractSocksPort(resolvedAddr)
                if (lastTorSnapshot.bootstrapped && boundSocksPort > 1) {
                    break
                }
                SystemClock.sleep(400L)
            }
            assertTrue(
                "Arti Tor failed to bootstrap within 90s (percent=${lastTorSnapshot.bootstrapPercent}, " +
                    "stage=${lastTorSnapshot.bootstrapSummary}, socks=${lastTorSnapshot.socksAddr})",
                lastTorSnapshot.bootstrapped && boundSocksPort > 1,
            )
            Log.i(TAG, "Arti Tor bootstrapped on loopback SOCKS5 port $boundSocksPort")

            // Ensure live CefpackSession is attached and zero-JS enforcement is active.
            composeRule.waitUntil(timeoutMillis = 15_000L) {
                activity.activeCefpackSessionForTest() != null
            }
            val browser = activity.activeCefpackSessionForTest()
            assertNotNull("Active CefpackSession must be non-null", browser)
            enforceZeroJsCdp(activity, browser!!)

            // 2. Generate a fresh ephemeral Sequoia OpenPGP keypair via Trap's real KeyringHardwarePod UI.
            val beforeFingerprints = OvNative.parseKeys(OvNative.nativeListKeysJson())
                .map { it.fingerprint }
                .toSet()
            val challengerEmail = "prize-${System.currentTimeMillis()}@onion"
            val challengerUserId = "Trap Prize Challenger <$challengerEmail>"

            openKeyringSheetReliably(timeoutMs = 15_000L)
            composeRule.onNodeWithTag("keyring_sheet").assertIsDisplayed()
            composeRule.onNodeWithText("Generate").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("keygen_userid_input").performTextInput(challengerUserId)
            hideSoftKeyboard(activity)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("keygen_submit_button").performClick()

            composeRule.waitUntil(timeoutMillis = 20_000L) {
                OvNative.parseKeys(OvNative.nativeListKeysJson()).any {
                    it.userId.contains(challengerEmail) &&
                        it.fingerprint !in beforeFingerprints &&
                        it.hasSecret
                } && composeRule.onAllNodesWithText(challengerUserId)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            val createdKey = OvNative.parseKeys(OvNative.nativeListKeysJson()).first {
                it.userId.contains(challengerEmail) &&
                    it.fingerprint !in beforeFingerprints &&
                    it.hasSecret
            }
            assertFalse("Generated Sequoia key fingerprint must be non-blank", createdKey.fingerprint.isBlank())

            try {
                val setDefaultTag = "key_set_default_${createdKey.fingerprint}"
                if (composeRule.onAllNodesWithTag(setDefaultTag).fetchSemanticsNodes().isNotEmpty()) {
                    composeRule.onNodeWithTag(setDefaultTag).performScrollTo().performClick()
                    composeRule.waitForIdle()
                }
                composeRule.onNodeWithTag("sheet_close_button").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("keyring_sheet").fetchSemanticsNodes().isEmpty()
                }

                // 3. Enter the live DigitalOcean .onion URL into Trap's RecessedCrtAddressPod and submit.
                composeRule.onNodeWithTag("crt_address_pod").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("crt_address_input").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("crt_address_input").performTextReplacement(targetOnionUrl)
                composeRule.onNodeWithTag("crt_address_input").performImeAction()

                // 4. Enrollment (GET / -> POST /enroll): wait up to 120s for .onion rendezvous + "Insert My PubKey" pill.
                awaitEnrollmentPageAndPubKeyPill(
                    activity = activity,
                    browser = browser,
                    targetOnionUrl = targetOnionUrl,
                    timeoutMs = 120_000L,
                )
                composeRule.onNodeWithTag("pgp_field_overlay").assertIsDisplayed()
                composeRule.onAllNodesWithText("Insert My PubKey")[0].performClick()

                val pubKeyFieldText = awaitControlValueContains(
                    activity = activity,
                    browser = browser,
                    elementId = "pubkey",
                    expectedSubstring = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
                    timeoutMs = 20_000L,
                )
                composeRule.onNodeWithTag("status_notice_banner").assertDoesNotExist()
                assertTrue(
                    "Expected ASCII-armored PGP public key in #pubkey, got len=${pubKeyFieldText.length}",
                    pubKeyFieldText.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----") &&
                        pubKeyFieldText.contains("-----END PGP PUBLIC KEY BLOCK-----"),
                )

                clickElementByIdZeroJsCdp(activity, browser, "enroll-submit")

                // 5. Complete Rounds 1, 2, and 3 (POST /verify x 3) strictly via Trap's OpenPGP UI + zero-JS CDP.
                val seenChallengeCiphertexts = HashSet<String>()
                val submittedTotpCodes = ArrayList<String>(3)

                for (expectedRound in 1..3) {
                    val challengeDoc = awaitChallengeRoundDom(
                        activity = activity,
                        browser = browser,
                        expectedRound = expectedRound,
                        timeoutMs = 60_000L,
                    )
                    val challengeCiphertext = readElementVisibleTextById(challengeDoc, "pgp-challenge").trim()
                    assertTrue(
                        "Round $expectedRound #pgp-challenge must contain armored PGP MESSAGE",
                        challengeCiphertext.contains("-----BEGIN PGP MESSAGE-----") &&
                            challengeCiphertext.contains("-----END PGP MESSAGE-----"),
                    )
                    assertTrue(
                        "Round $expectedRound must emit a fresh OpenPGP ciphertext distinct from prior rounds",
                        seenChallengeCiphertexts.add(challengeCiphertext),
                    )

                    val decryptedPlaintext = decryptCurrentPageBlockViaRockerUi(
                        activity = activity,
                        browser = browser,
                        expectedSubstring = "ROUND $expectedRound OF 3",
                        timeoutMs = 25_000L,
                    )
                    assertTrue(
                        "Decrypted plaintext must confirm ROUND $expectedRound OF 3: $decryptedPlaintext",
                        decryptedPlaintext.contains("ROUND $expectedRound OF 3"),
                    )
                    val codeMatch = SIX_DIGIT_TOTP_REGEX.find(decryptedPlaintext)
                    assertNotNull(
                        "Decrypted plaintext in DecryptedPlaintextHardwarePod must contain 6-digit TOTP code: $decryptedPlaintext",
                        codeMatch,
                    )
                    val totpCode = codeMatch!!.groupValues[1]
                    submittedTotpCodes.add(totpCode)
                    Log.i(TAG, "Decrypted Round $expectedRound/3 TOTP challenge via DecryptedPlaintextHardwarePod")

                    // Close DecryptedPlaintextHardwarePod before interacting with the page form.
                    composeRule.onNodeWithTag("plaintext_close").performClick()
                    composeRule.waitUntil(timeoutMillis = 5_000L) {
                        composeRule.onAllNodesWithTag("plaintext_sheet").fetchSemanticsNodes().isEmpty()
                    }

                    // Write the 6-digit TOTP code into <input id="totp-code"> via zero-JS CdpDomPgpController
                    // and submit <button id="verify-submit"> via zero-JS CDP mouse dispatch.
                    writeInputByIdZeroJs(
                        activity = activity,
                        browser = browser,
                        elementId = "totp-code",
                        value = totpCode,
                    )
                    clickElementByIdZeroJsCdp(activity, browser, "verify-submit")
                }

                assertEquals("All 3 TOTP rounds must be submitted", 3, submittedTotpCodes.size)

                // 6. Prize Verification (Round 3 won!): verify data-won="true", non-blank #prize-banner,
                //    and pull OpenPgpRockerPod upward on <pre id="pgp-prize"> so DecryptedPlaintextHardwarePod
                //    remains visibly open displaying the decrypted OpenPGP Prize Certificate.
                val wonDoc = awaitPrizeWonDom(
                    activity = activity,
                    browser = browser,
                    timeoutMs = 60_000L,
                )
                val prizeBanner = readElementVisibleTextById(wonDoc, "prize-banner").trim()
                assertTrue("Won #prize-banner text in DOM must be non-blank", prizeBanner.isNotBlank())

                val armoredPrizeCert = readElementVisibleTextById(wonDoc, "pgp-prize").trim()
                assertTrue(
                    "Won #pgp-prize must contain an ASCII-armored OpenPGP message",
                    armoredPrizeCert.contains("-----BEGIN PGP MESSAGE-----") &&
                        armoredPrizeCert.contains("-----END PGP MESSAGE-----"),
                )

                val decryptedCertificate = decryptCurrentPageBlockViaRockerUi(
                    activity = activity,
                    browser = browser,
                    expectedSubstring = prizeBanner,
                    timeoutMs = 25_000L,
                )
                composeRule.onNodeWithTag("plaintext_sheet").assertIsDisplayed()
                assertTrue(
                    "Decrypted OpenPGP Prize Certificate must include won prize banner",
                    decryptedCertificate.contains(prizeBanner),
                )
                assertTrue(
                    "Decrypted OpenPGP Prize Certificate must bind winner key fingerprint ${createdKey.fingerprint}",
                    decryptedCertificate.contains(createdKey.fingerprint, ignoreCase = true),
                )

                // Allow RenderThread & SurfaceFlinger to commit the frame with plaintext_sheet open,
                // then capture the frame-accurate PNG screenshot before logging PRIZE_WON_JSON.
                composeRule.waitForIdle()
                SystemClock.sleep(650L)
                capturePrizeScreenshotToDeviceTmp(instrumentation, activity)

                val prizePayload = JSONObject()
                    .put("onionUrl", targetOnionUrl)
                    .put("roundsCompleted", 3)
                    .put("winnerFingerprint", createdKey.fingerprint)
                    .put("prizeBanner", prizeBanner)
                    .put("decryptedCertificate", decryptedCertificate)
                Log.i(TAG, "PRIZE_WON_JSON=$prizePayload")

                // Keep DecryptedPlaintextHardwarePod visibly open briefly for outer gate screenshot capture.
                SystemClock.sleep(prizeHoldMs)
            } finally {
                runCatching { OvNative.nativeDeleteKey(createdKey.fingerprint) }
                prefs.defaultKeyFingerprint = previousDefaultFp
                prefs.requireBiometric = previousBiometric
                runCatching { OvNative.nativeSetRequireBiometric(previousBiometric) }
            }
        }
    }

    private fun awaitEnrollmentPageAndPubKeyPill(
        activity: MainActivity,
        browser: CefpackSession,
        targetOnionUrl: String,
        timeoutMs: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastStage = ""
        var lastScanKickMs = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
            val domJson = queryPiercedDomJson(activity, liveBrowser)
            val root = parseDomRoot(domJson)
            if (root != null) {
                val statusNode = findElementById(root, "totp-status")
                val pubkeyNode = findElementById(root, "pubkey")
                val submitNode = findElementById(root, "enroll-submit")
                lastStage = extractAttribute(statusNode?.optJSONArray("attributes"), "data-stage")

                if (pubkeyNode != null && submitNode != null) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastScanKickMs >= 1_200L) {
                        lastScanKickMs = now
                        val tabId = activity.activeTabIdForTest()
                        if (tabId != null) {
                            activity.cdpControllerForTest()?.attachAndScan(tabId, liveBrowser, targetOnionUrl)
                        }
                    }
                    val hasInsertPill = runCatching {
                        composeRule.onAllNodesWithText("Insert My PubKey")
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    }.getOrDefault(false)
                    if (hasInsertPill) {
                        return
                    }
                }
            }
            SystemClock.sleep(350L)
        }
        fail(
            "Timed out after ${timeoutMs}ms waiting for .onion enrollment page ($targetOnionUrl) " +
                "and 'Insert My PubKey' Compose pill (lastStage='$lastStage')",
        )
    }

    private fun awaitChallengeRoundDom(
        activity: MainActivity,
        browser: CefpackSession,
        expectedRound: Int,
        timeoutMs: Long,
    ): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastStage = ""
        var lastRound = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
            val domJson = queryPiercedDomJson(activity, liveBrowser)
            val root = parseDomRoot(domJson)
            if (root != null) {
                val statusNode = findElementById(root, "totp-status")
                if (statusNode != null) {
                    val attrs = statusNode.optJSONArray("attributes")
                    lastStage = extractAttribute(attrs, "data-stage")
                    lastRound = extractAttribute(attrs, "data-round")
                    if (lastStage.equals("failed", ignoreCase = true)) {
                        val failBody = readElementVisibleTextById(domJson, "totp-status")
                        fail("Hidden service rejected TOTP submission on round $expectedRound: $failBody")
                    }
                    if (lastStage == "challenge" &&
                        lastRound == expectedRound.toString() &&
                        findElementById(root, "pgp-challenge") != null &&
                        findElementById(root, "totp-code") != null &&
                        findElementById(root, "verify-submit") != null
                    ) {
                        val challengeText = readElementVisibleTextById(domJson, "pgp-challenge")
                        if (challengeText.contains("-----BEGIN PGP MESSAGE-----") &&
                            challengeText.contains("-----END PGP MESSAGE-----")
                        ) {
                            return domJson
                        }
                    }
                }
            }
            SystemClock.sleep(250L)
        }
        fail(
            "Timed out waiting for challenge round $expectedRound DOM " +
                "(lastStage='$lastStage', lastRound='$lastRound')",
        )
        return ""
    }

    private fun awaitPrizeWonDom(
        activity: MainActivity,
        browser: CefpackSession,
        timeoutMs: Long,
    ): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastStage = ""
        var lastRound = ""
        var lastWon = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
            val domJson = queryPiercedDomJson(activity, liveBrowser)
            val root = parseDomRoot(domJson)
            if (root != null) {
                val statusNode = findElementById(root, "totp-status")
                if (statusNode != null) {
                    val attrs = statusNode.optJSONArray("attributes")
                    lastStage = extractAttribute(attrs, "data-stage")
                    lastRound = extractAttribute(attrs, "data-round")
                    lastWon = extractAttribute(attrs, "data-won")
                    if (lastStage.equals("failed", ignoreCase = true)) {
                        val failBody = readElementVisibleTextById(domJson, "totp-status")
                        fail("Hidden service rejected Round 3 TOTP verification: $failBody")
                    }
                    if (lastWon.equals("true", ignoreCase = true) &&
                        lastStage == "won" &&
                        lastRound == "3" &&
                        findElementById(root, "prize-banner") != null &&
                        findElementById(root, "pgp-prize") != null
                    ) {
                        val bannerText = readElementVisibleTextById(domJson, "prize-banner").trim()
                        val prizeArmor = readElementVisibleTextById(domJson, "pgp-prize")
                        if (bannerText.isNotBlank() &&
                            prizeArmor.contains("-----BEGIN PGP MESSAGE-----") &&
                            prizeArmor.contains("-----END PGP MESSAGE-----")
                        ) {
                            return domJson
                        }
                    }
                }
            }
            SystemClock.sleep(250L)
        }
        fail(
            "Timed out waiting for Round 3 prize won DOM " +
                "(lastStage='$lastStage', lastRound='$lastRound', lastWon='$lastWon')",
        )
        return ""
    }

    private fun decryptCurrentPageBlockViaRockerUi(
        activity: MainActivity,
        browser: CefpackSession,
        expectedSubstring: String,
        timeoutMs: Long,
    ): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastSheetText = ""

        while (SystemClock.uptimeMillis() < deadline) {
            val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
            val tabId = activity.activeTabIdForTest()
            if (tabId != null) {
                activity.cdpControllerForTest()?.attachAndScan(
                    tabId = tabId,
                    browser = liveBrowser,
                    currentUrl = liveBrowser.url.orEmpty(),
                )
            }

            // Wait for CdpDomPgpController to classify the current armored block so OpenPgpRockerPod arms.
            val pillDeadline = minOf(deadline, SystemClock.uptimeMillis() + 4_000L)
            while (SystemClock.uptimeMillis() < pillDeadline) {
                val armed = runCatching {
                    composeRule.onAllNodesWithText("Decrypt Block")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }.getOrDefault(false)
                if (armed) break
                SystemClock.sleep(150L)
            }
            composeRule.waitForIdle()
            SystemClock.sleep(180L)

            // Pull OpenPgpRockerPod's mechanical latch upward (`dy <= -18.dp`) to decrypt primaryArmoredBlock.
            composeRule.onNodeWithTag("pgp_rocker_pod").performTouchInput {
                down(center)
                moveBy(Offset(0f, -28.dp.toPx()))
                up()
            }

            val sheetWaitDeadline = minOf(deadline, SystemClock.uptimeMillis() + 5_000L)
            var sheetVisible = false
            while (SystemClock.uptimeMillis() < sheetWaitDeadline) {
                sheetVisible = runCatching {
                    composeRule.onAllNodesWithTag("plaintext_sheet")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }.getOrDefault(false)
                if (sheetVisible) break
                SystemClock.sleep(120L)
            }

            if (!sheetVisible) {
                val decryptPills = runCatching {
                    composeRule.onAllNodesWithText("Decrypt Block").fetchSemanticsNodes().size
                }.getOrDefault(0)
                if (decryptPills > 0) {
                    composeRule.onAllNodesWithText("Decrypt Block")[0].performClick()
                    val fallbackDeadline = minOf(deadline, SystemClock.uptimeMillis() + 4_000L)
                    while (SystemClock.uptimeMillis() < fallbackDeadline) {
                        sheetVisible = runCatching {
                            composeRule.onAllNodesWithTag("plaintext_sheet")
                                .fetchSemanticsNodes()
                                .isNotEmpty()
                        }.getOrDefault(false)
                        if (sheetVisible) break
                        SystemClock.sleep(120L)
                    }
                }
            }

            if (sheetVisible) {
                composeRule.waitForIdle()
                lastSheetText = readSemanticsTextUnderTag("plaintext_sheet")
                if (lastSheetText.contains(expectedSubstring)) {
                    return lastSheetText
                }
                // Stale block from previous DOM scan still in flight; dismiss and re-pull once updated.
                composeRule.onNodeWithTag("plaintext_close").performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    composeRule.onAllNodesWithTag("plaintext_sheet").fetchSemanticsNodes().isEmpty()
                }
            }

            SystemClock.sleep(300L)
        }

        fail(
            "Timed out decrypting current OpenPGP block expecting '$expectedSubstring' " +
                "(lastSheetText='${lastSheetText.take(200)}')",
        )
        return ""
    }

    private fun readSemanticsTextUnderTag(tag: String): String {
        val rootNode = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val lines = ArrayList<String>()
        fun collect(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { annotated ->
                if (annotated.text.isNotEmpty()) {
                    lines.add(annotated.text)
                }
            }
            node.children.forEach { collect(it) }
        }
        collect(rootNode)
        return lines.joinToString("\n")
    }

    private fun writeInputByIdZeroJs(
        activity: MainActivity,
        browser: CefpackSession,
        elementId: String,
        value: String,
    ) {
        val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
        val domJson = queryPiercedDomJson(activity, liveBrowser)
        val root = parseDomRoot(domJson)
        assertNotNull("DOM root must exist when locating #$elementId", root)
        val target = findElementById(root!!, elementId)
        assertNotNull("Input element #$elementId must exist in DOM", target)

        val nodeId = target!!.optInt("nodeId", 0)
        val backendNodeId = target.optInt("backendNodeId", 0)
        val controller = activity.cdpControllerForTest()
        assertNotNull("CdpDomPgpController must be initialized", controller)

        val writeLatch = CountDownLatch(1)
        val outcomeRef = AtomicReference<CdpDomPgpController.FieldMutationOutcome?>()
        controller!!.writeFieldValue(
            browser = liveBrowser,
            nodeId = nodeId,
            backendNodeId = backendNodeId,
            newValue = value,
            tabId = activity.activeTabIdForTest(),
        ) { outcome ->
            outcomeRef.set(outcome)
            writeLatch.countDown()
        }
        assertTrue("Zero-JS field write to #$elementId timed out", writeLatch.await(15, TimeUnit.SECONDS))
        val outcome = outcomeRef.get()
        assertNotNull("Zero-JS field write outcome missing", outcome)
        assertTrue(
            "Zero-JS field write to #$elementId failed verification: ${outcome!!.error}",
            outcome.ok && outcome.verified,
        )
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
        assertNotNull("Button/element #$elementId must exist in DOM", target)

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
        val (pressOk, pressResp) = executeCdpSync(activity, liveBrowser, "Input.dispatchMouseEvent", pressParams)
        val (relOk, relResp) = executeCdpSync(activity, liveBrowser, "Input.dispatchMouseEvent", releaseParams)
        assertTrue(
            "Zero-JS CDP click on #$elementId failed (press=$pressOk/$pressResp, release=$relOk/$relResp)",
            pressOk && relOk,
        )
    }

    private fun enforceZeroJsCdp(activity: MainActivity, browser: CefpackSession) {
        val (ok, resp) = executeCdpSync(
            activity = activity,
            browser = browser,
            method = "Emulation.setScriptExecutionDisabled",
            paramsJson = "{\"value\":true}",
        )
        assertTrue("Emulation.setScriptExecutionDisabled(true) must succeed ($resp)", ok)
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

    private fun queryPiercedDomJson(
        activity: MainActivity,
        browser: CefpackSession,
    ): String {
        val latch = CountDownLatch(1)
        val jsonRef = AtomicReference("")
        activity.runOnUiThread {
            runCatching {
                browser.executeDevToolsMethod(
                    "DOM.getDocument",
                    "{\"depth\":-1,\"pierce\":true}",
                ) { ok, json ->
                    if (ok && !json.isNullOrBlank()) {
                        jsonRef.set(json)
                    }
                    latch.countDown()
                }
            }.onFailure {
                latch.countDown()
            }
        }
        // Cross-origin RenderFrameHost swaps (about:blank -> .onion) can drop an in-flight
        // InspectorDOMAgent request without firing its callback; return empty so callers poll cleanly.
        latch.await(5, TimeUnit.SECONDS)
        return jsonRef.get()
    }

    private fun awaitControlValueContains(
        activity: MainActivity,
        browser: CefpackSession,
        elementId: String,
        expectedSubstring: String,
        timeoutMs: Long,
    ): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastValue = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val liveBrowser = activity.activeCefpackSessionForTest() ?: browser
            lastValue = readControlInnerEditorById(queryPiercedDomJson(activity, liveBrowser), elementId)
            if (lastValue.contains(expectedSubstring)) {
                return lastValue
            }
            SystemClock.sleep(150L)
        }
        return lastValue
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
            val attrs = node.optJSONArray("attributes")
            val idAttr = extractAttribute(attrs, "id")
            val pseudo = extractAttribute(attrs, "pseudo")
            if (idAttr.equals("placeholder", ignoreCase = true) ||
                pseudo.contains("placeholder", ignoreCase = true)
            ) {
                return
            }
            val tag = node.optString("localName", node.optString("nodeName", "")).lowercase()
            if (tag == "script" || tag == "style") {
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

    private fun extractSocksPort(rawAddr: String): Int {
        val clean = rawAddr.trim().removePrefix("socks5://").removePrefix("socks5h://")
        return clean.substringAfterLast(':', "").toIntOrNull() ?: 0
    }

    private fun wakeAndUnlockDisplay(instrumentation: android.app.Instrumentation) {
        // Wake display and collapse any open shade/bouncer without invoking wm dismiss-keyguard
        // (which summons the biometric/PIN bouncer overlay on secure-lock devices).
        runShellCommandDrained(instrumentation, "input keyevent KEYCODE_WAKEUP")
        runShellCommandDrained(instrumentation, "cmd statusbar collapse")
    }

    private fun hideSoftKeyboard(activity: MainActivity) {
        activity.runOnUiThread {
            val imm = activity.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
    }

    private fun openKeyringSheetReliably(timeoutMs: Long) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val isOpen = runCatching {
                composeRule.onAllNodesWithTag("keyring_sheet")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
            if (isOpen) return

            composeRule.waitForIdle()
            runCatching {
                composeRule.onNodeWithTag("pgp_rocker_pod").performTouchInput {
                    doubleClick()
                }
            }

            val stepWait = minOf(deadline, SystemClock.uptimeMillis() + 1_500L)
            while (SystemClock.uptimeMillis() < stepWait) {
                val nowOpen = runCatching {
                    composeRule.onAllNodesWithTag("keyring_sheet")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }.getOrDefault(false)
                if (nowOpen) return
                SystemClock.sleep(100L)
            }
        }
        fail("Timed out opening KeyringHardwarePod ('keyring_sheet') via double-tap on 'pgp_rocker_pod'")
    }

    private fun capturePrizeScreenshotToDeviceTmp(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
    ) {
        val hasFocusRef = AtomicReference(false)
        activity.runOnUiThread {
            hasFocusRef.set(activity.hasWindowFocus())
        }

        // Prefer UiAutomation.takeScreenshot() when MainActivity holds top window focus (e.g.
        // unlocked devices and Limrun Cloud Android 15); otherwise composite CefpackSession's
        // SurfaceView + MainActivity's Window HardwareBuffer directly via PixelCopy so a physical
        // device lockscreen / UDFPS overlay never obscures the rendered prize UI.
        var capturedBitmap: Bitmap? = null
        if (hasFocusRef.get()) {
            capturedBitmap = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
        }
        if (capturedBitmap == null) {
            capturedBitmap = captureActivityWindowComposite(activity)
                ?: runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
        }
        assertNotNull("Failed to capture rendered prize screenshot bitmap", capturedBitmap)

        try {
            val pngOut = ByteArrayOutputStream()
            capturedBitmap!!.compress(Bitmap.CompressFormat.PNG, 100, pngOut)
            val pngBytes = pngOut.toByteArray()
            assertTrue("Encoded prize PNG must be non-empty", pngBytes.size > 512)

            // Persist deterministically inside app-private filesDir (`run-as dev.onionvault cat files/prize-won.png`).
            val appPrizeFile = File(activity.filesDir, APP_PRIZE_SCREENSHOT_FILENAME)
            FileOutputStream(appPrizeFile).use { out ->
                out.write(pngBytes)
                out.fd.sync()
            }

            // Best-effort secondary copy in externalCacheDir and /data/local/tmp (never fatal if restricted).
            runCatching {
                val extFile = File(activity.externalCacheDir ?: activity.cacheDir, APP_PRIZE_SCREENSHOT_FILENAME)
                FileOutputStream(extFile).use { it.write(pngBytes) }
            }
            runCatching {
                val pfds = instrumentation.uiAutomation.executeShellCommandRw(
                    "dd of=$DEVICE_PRIZE_SCREENSHOT_PATH bs=65536",
                )
                ParcelFileDescriptor.AutoCloseOutputStream(pfds[1]).use { stdin ->
                    stdin.write(pngBytes)
                    stdin.flush()
                }
                ParcelFileDescriptor.AutoCloseInputStream(pfds[0]).use { stdout ->
                    val drain = ByteArray(1024)
                    while (stdout.read(drain) > 0) {
                        // Drain dd stderr/stdout summary.
                    }
                }
            }
        } finally {
            capturedBitmap?.recycle()
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
        canvas.drawColor(Color.rgb(8, 9, 12))
        val mainHandler = Handler(Looper.getMainLooper())

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
            canvas.drawBitmap(winBmp, 0f, 0f, null)
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

    private fun runShellCommandDrained(
        instrumentation: android.app.Instrumentation,
        command: String,
    ): String {
        return runCatching {
            val pfd = instrumentation.uiAutomation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            }
        }.getOrDefault("")
    }
}
