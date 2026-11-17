package dev.onionvault.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.onionvault.CdpDomPgpController
import dev.onionvault.OnionBackStackManager
import dev.onionvault.TabSessionManager
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cryptographic Tor Rendezvous Spec v3 (`rend-spec-v3.txt` §6) checksum verification,
 * DuckDuckGo `.onion` search resolution, and Robolectric Compose UI verification for
 * [TrustedOnionDirectory] and [StartPage].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrustedOnionDirectoryTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun every_directory_entry_passes_independent_tor_v3_sha3_256_checksum_verification() {
        val entries = TrustedOnionDirectory.entries
        assertTrue("Expected at least 11 curated v3 .onion services", entries.size >= 11)

        val seenIds = mutableSetOf<String>()
        val seenUrls = mutableSetOf<String>()
        val jdkSha3 = MessageDigest.getInstance("SHA3-256")
        val prefixBytes = ".onion checksum".toByteArray(Charsets.US_ASCII)

        for (entry in entries) {
            assertTrue("Duplicate entry id: ${entry.id}", seenIds.add(entry.id))
            assertTrue("Duplicate entry url: ${entry.url}", seenUrls.add(entry.url))
            assertTrue("Blank title for ${entry.id}", entry.title.isNotBlank())
            assertTrue("Blank description for ${entry.id}", entry.description.isNotBlank())

            val parsed = URI(entry.url)
            assertTrue(
                "Invalid scheme for ${entry.id}: ${parsed.scheme}",
                parsed.scheme == "http" || parsed.scheme == "https",
            )
            val host = parsed.host
            assertNotNull("Missing host for ${entry.id}: ${entry.url}", host)
            assertTrue("Host must end with .onion: $host", host!!.endsWith(".onion"))

            val rootLabel = host.removeSuffix(".onion").substringAfterLast('.')
            assertEquals(
                "v3 .onion label must be exactly 56 base32 chars for ${entry.id} ($host)",
                56,
                rootLabel.length,
            )
            assertTrue(
                "v3 .onion label must be lowercase RFC 4648 base32 [a-z2-7] for ${entry.id}",
                rootLabel.all { it in 'a'..'z' || it in '2'..'7' },
            )

            // Independent RFC 4648 Base32 decode -> 35 bytes = pubkey[32] || checksum[2] || version[1]
            val raw = independentBase32Decode(rootLabel)
            assertEquals("Decoded v3 .onion payload must be 35 bytes for ${entry.id}", 35, raw.size)

            val pubkey = raw.copyOfRange(0, 32)
            val checksum = raw.copyOfRange(32, 34)
            val version = raw[34]

            assertEquals(
                "Tor v3 version byte must be 0x03 for ${entry.id}",
                0x03.toByte(),
                version,
            )
            assertFalse(
                "Ed25519 master public key must not be all-zero for ${entry.id}",
                pubkey.all { it == 0.toByte() },
            )

            jdkSha3.reset()
            jdkSha3.update(prefixBytes)
            jdkSha3.update(pubkey)
            jdkSha3.update(version)
            val expectedDigest = jdkSha3.digest()
            val expectedChecksum = expectedDigest.copyOfRange(0, 2)

            assertArrayEquals(
                "SHA3-256(\".onion checksum\" || pubkey || 0x03)[0..2] mismatch for ${entry.id} ($rootLabel)",
                expectedChecksum,
                checksum,
            )

            assertTrue(
                "TrustedOnionDirectory verifier must accept ${entry.id}",
                TrustedOnionDirectory.isCryptographicallyValidV3OnionUrl(entry.url),
            )
            assertEquals(
                "Short .onion key must be compact 18-char format for ${entry.id}",
                18,
                entry.shortOnionKey.length,
            )
        }

        // Exact 18-char display truncation contract: <first6>…<last5>.onion
        val duckDuckGo = entries.first { it.id == "duckduckgo" }
        assertEquals("duckdu\u2026zczad.onion", duckDuckGo.shortOnionKey)
        val bellingcat = entries.first { it.id == "bellingcat" }
        assertEquals("bellca\u2026tbzad.onion", bellingcat.shortOnionKey)
    }

    @Test
    fun pure_kotlin_keccak_sha3_256_matches_jdk_fips_202_across_block_boundaries() {
        val jdkSha3 = MessageDigest.getInstance("SHA3-256")
        val vectors = listOf(
            ByteArray(0),
            "abc".encodeToByteArray(),
            ".onion checksum".encodeToByteArray(),
            ByteArray(48) { (it * 7 + 3).toByte() },
            ByteArray(135) { (it xor 0x5A).toByte() }, // 1 byte shy of 136-byte rate
            ByteArray(136) { (it * 13).toByte() }, // exact 136-byte rate boundary
            ByteArray(137) { (255 - it).toByte() }, // 1 byte past rate boundary
            ByteArray(409) { (it * 31 + 11).toByte() }, // multi-block absorb
        )
        for (input in vectors) {
            val expected = jdkSha3.digest(input)
            val actual = TrustedOnionDirectory.sha3_256(input)
            assertArrayEquals("SHA3-256 mismatch for input length ${input.size}", expected, actual)
        }
    }

    @Test
    fun checksum_verifier_rejects_forged_corrupted_and_v2_onion_addresses() {
        // Flip a single base32 character in DuckDuckGo's valid 56-char label
        val validLabel = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad"
        assertTrue(TrustedOnionDirectory.isCryptographicallyValidV3OnionLabel(validLabel))

        val corrupted = "e" + validLabel.drop(1)
        assertFalse(TrustedOnionDirectory.isCryptographicallyValidV3OnionLabel(corrupted))
        assertFalse(
            TrustedOnionDirectory.isCryptographicallyValidV3OnionUrl("https://$corrupted.onion/"),
        )

        // Legacy 16-char v2 .onion must fail
        assertFalse(
            TrustedOnionDirectory.isCryptographicallyValidV3OnionUrl("http://3g2upl4pq6kufc4m.onion/"),
        )

        // Out-of-alphabet characters (0, 1, 8, 9) must fail
        val invalidBase32 = "0".repeat(56)
        assertFalse(TrustedOnionDirectory.isCryptographicallyValidV3OnionLabel(invalidBase32))

        // Clearnet URL and non-HTTP schemes must fail
        assertFalse(TrustedOnionDirectory.isCryptographicallyValidV3OnionUrl("https://tor.taxi/"))
        assertFalse(
            TrustedOnionDirectory.isCryptographicallyValidV3OnionUrl(
                "javascript://$validLabel.onion/",
            ),
        )
    }

    @Test
    fun categories_sections_and_duckduckgo_search_resolver_route_accurately() {
        for (category in OnionCategory.entries) {
            assertTrue(
                "Category $category must not be empty",
                TrustedOnionDirectory.entriesForCategory(category).isNotEmpty(),
            )
        }
        for ((section, items) in TrustedOnionDirectory.entriesBySection) {
            assertTrue("Section $section must not be empty", items.isNotEmpty())
        }

        assertNull(TrustedOnionDirectory.resolveSearchOrAddress(""))
        assertNull(TrustedOnionDirectory.resolveSearchOrAddress("   "))

        // Search terms route to DuckDuckGo zero-JS HTML v3 .onion with RFC 3986 %20 encoding
        val multiWordSearch = TrustedOnionDirectory.resolveSearchOrAddress("whistleblower drop")
        assertEquals(
            "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/html/?q=whistleblower%20drop",
            multiWordSearch,
        )
        assertTrue(TrustedOnionDirectory.isCryptographicallyValidV3OnionUrl(multiWordSearch!!))

        assertEquals(
            "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/html/?q=securedrop",
            TrustedOnionDirectory.resolveSearchOrAddress("securedrop"),
        )
        assertEquals(
            "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/html/?q=pgp%20%26%20tor%3F",
            TrustedOnionDirectory.resolveSearchOrAddress("pgp & tor?"),
        )

        // Direct URLs and bare hosts pass through untouched
        assertEquals(
            "p53lf57qovyuvwsc6xnrppyply3vtqm7l6pcobkmyqsiofyeznfu5uqd.onion",
            TrustedOnionDirectory.resolveSearchOrAddress(
                "p53lf57qovyuvwsc6xnrppyply3vtqm7l6pcobkmyqsiofyeznfu5uqd.onion",
            ),
        )
        assertEquals("tor.taxi", TrustedOnionDirectory.resolveSearchOrAddress("tor.taxi"))
        assertEquals(
            "https://www.propublica.org/tips",
            TrustedOnionDirectory.resolveSearchOrAddress("https://www.propublica.org/tips"),
        )
        assertEquals("about:blank", TrustedOnionDirectory.resolveSearchOrAddress("about:blank"))
    }

    @Test
    fun start_page_renders_zine_surface_search_directory_scroll_clearance_and_label_density() {
        var securityOpened = 0
        val navigatedUrls = mutableListOf<String>()
        var uiState by mutableStateOf(
            BrowserUiState(
                url = "about:blank",
                tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
            ),
        )

        compose.setContent {
            TrapTheme(
                family = uiState.themeFamily,
                mode = uiState.themeMode,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    StartPage(
                        state = uiState,
                        onOpenSecurity = { securityOpened += 1 },
                        onNavigate = { navigatedUrls += it },
                    )
                }
            }
        }

        // 1. Masthead, live Tor readout, recessed CRT search box, and directory headers exist
        compose.onNodeWithTag("start_page").assertIsDisplayed()
        compose.onNodeWithText("TRAP").assertIsDisplayed()
        compose.onNodeWithTag("start_tor_status").assertIsDisplayed().performClick()
        assertEquals(1, securityOpened)
        compose.onNodeWithText("Tor ready").assertIsDisplayed()

        compose.onNodeWithTag("start_search_box").assertIsDisplayed()
        compose.onNodeWithText("Search DuckDuckGo .onion or enter address...").assertIsDisplayed()
        compose.onNodeWithTag("trusted_onion_directory").assertIsDisplayed()
        compose.onNodeWithText("SEARCH & PRESS").assertIsDisplayed()

        // Capture dark zine screenshot for visual inspection
        captureStartPageBitmap("start-page-zine-dark")

        // 2. Search input submits DuckDuckGo v3 .onion HTML query on ImeAction.Search
        compose.onNodeWithTag("start_search_box").performTextInput("whistleblower drop")
        compose.onNodeWithTag("start_search_box").performImeAction()
        assertEquals(
            "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/html/?q=whistleblower%20drop",
            navigatedUrls.last(),
        )

        // Clear button resets query, and GO button submits direct host
        compose.onNodeWithTag("start_search_clear").performClick()
        compose.onNodeWithTag("start_search_box").performTextInput("tor.taxi")
        compose.onNodeWithTag("start_search_submit").performClick()
        assertEquals("tor.taxi", navigatedUrls.last())
        compose.onNodeWithTag("start_search_clear").performClick()

        // 3. Every single directory row obeys <= 2:1 label density (1 title + 2 secondary tokens = 3 text nodes)
        //    and navigates to its exact verified v3 .onion URL on 1 tap!
        for (entry in TrustedOnionDirectory.entries) {
            val rowInteraction = compose.onNodeWithTag("onion_dir_${entry.id}")
            rowInteraction.performScrollTo().assertIsDisplayed()
            assertRowObeysTwoToOneLabelDensity(entry.id)

            rowInteraction.performClick()
            assertEquals(
                "Row ${entry.id} must navigate to ${entry.url}",
                entry.url,
                navigatedUrls.last(),
            )
        }

        // 4. Verify >= 132.dp bottom clearance spacer so bottom items scroll clear of floating pods
        compose.onNodeWithTag("start_bottom_clearance", useUnmergedTree = true).performScrollTo()
        val clearanceHeight = compose
            .onNodeWithTag("start_bottom_clearance", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .height
        assertTrue(
            "Expected >= 132.dp bottom clearance spacer, got $clearanceHeight",
            clearanceHeight >= 131.dp,
        )

        // 5. Verify Tor bootstrapping state + Light newsprint mode render cleanly
        uiState = uiState.copy(
            tor = TorBootstrapState(percent = 45, stage = "connecting", ready = false),
            themeMode = TrapThemeMode.Light,
        )
        compose.waitForIdle()
        compose.onNodeWithTag("start_tor_status").performScrollTo()
        compose.onNodeWithText("Connecting to Tor \u00B7 45%").assertIsDisplayed()
        captureStartPageBitmap("start-page-zine-light")
    }

    @Test
    fun onion_rendezvous_auto_retry_and_start_page_back_navigation_work_end_to_end() {
        var fakeClockMs = 1_000L
        val notices = mutableListOf<String>()
        val recoveredTabs = mutableListOf<String>()
        val reloadedUrls = mutableListOf<String>()

        val manager = TabSessionManager(
            context = compose.activity,
            cdpController = CdpDomPgpController(),
            backStackManager = OnionBackStackManager(),
            onSecurityNotice = { notices += it },
            onNavigationRecovered = { recoveredTabs += it },
        )
        manager.elapsedRealtimeClock = { fakeClockMs }

        val tabId = manager.ensureInitialTab("about:blank")
        assertEquals("about:blank", manager.activeTab()?.url)
        assertFalse("StartPage root must have canGoBack=false", manager.activeTab()!!.canGoBack)

        // Verify all transient Chromium .onion SOCKS/timeout codes are recognized:
        for (transientCode in listOf(-7, -100, -101, -118, -120, -130, -324)) {
            assertTrue(
                "Error $transientCode must be transient for .onion auto-retry",
                TabSessionManager.isTransientOnionLoadError(transientCode),
            )
        }
        assertFalse(TabSessionManager.isTransientOnionLoadError(-202)) // ERR_CERT_AUTHORITY_INVALID

        // Navigate from StartPage to an http:// .onion service (e.g. Riseup):
        val riseupHttp = "http://vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion/"
        val riseupHttpsUpgrade = "https://vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion/"
        manager.beginOnionNavigationForTest(tabId, riseupHttp, fromStartPage = true)
        assertTrue("Navigating from StartPage must enable canGoBack", manager.activeTab()!!.canGoBack)
        assertTrue(manager.activeTab()!!.isLoading)

        // Simulate Chromium 30s SOCKS5 timeout + HttpsUpgrades URL rewrite -> Retry #1 (attempt 2/4):
        fakeClockMs += 30_000L
        manager.handleLoadErrorOnMainThread(
            tabId = tabId,
            errorCode = TabSessionManager.ERR_SOCKS_CONNECTION_FAILED,
            errorText = "ERR_SOCKS_CONNECTION_FAILED",
            failedUrl = riseupHttpsUpgrade,
            reloadUrl = { reloadedUrls += it },
        )
        assertTrue("Tab must stay isLoading=true during auto-retry", manager.activeTab()!!.isLoading)
        assertEquals("Building .onion rendezvous circuit (attempt 2/4)\u2026", notices.last())
        assertTrue(reloadedUrls.isEmpty())

        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        assertEquals(
            "Retry must preserve original http:// .onion scheme across HttpsUpgrades",
            listOf(riseupHttp),
            reloadedUrls,
        )

        // Retry #2 (attempt 3/4) at 60s elapsed:
        fakeClockMs += 30_000L
        manager.handleLoadErrorOnMainThread(
            tabId = tabId,
            errorCode = TabSessionManager.ERR_TIMED_OUT,
            errorText = "ERR_TIMED_OUT",
            failedUrl = riseupHttp,
            reloadUrl = { reloadedUrls += it },
        )
        assertEquals("Building .onion rendezvous circuit (attempt 3/4)\u2026", notices.last())
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        assertEquals(2, reloadedUrls.size)

        // Warm rendezvous circuit completes -> status clears and isLoading becomes false:
        manager.simulateLoadCompletedForTest(tabId, riseupHttp)
        assertFalse(manager.activeTab()!!.isLoading)
        assertEquals("", notices.last())
        assertEquals(listOf(tabId), recoveredTabs)
        assertTrue("canGoBack must remain true to return to StartPage", manager.activeTab()!!.canGoBack)

        // Pressing Back cleanly returns to StartPage (about:blank):
        assertTrue(manager.goBack(tabId))
        assertEquals("about:blank", manager.activeTab()!!.url)
        assertFalse(manager.activeTab()!!.canGoBack)

        // Verify >= 110_000 ms elapsed budget stops retrying and surfaces real failure:
        val bbcUrl = "https://www.bbcnewsd73hkzno2ini43t4gblxvycyac5aw4gnv7t2rccijh7745uqd.onion/news"
        manager.beginOnionNavigationForTest(tabId, bbcUrl, fromStartPage = true)
        fakeClockMs += 115_000L
        manager.handleLoadErrorOnMainThread(
            tabId = tabId,
            errorCode = TabSessionManager.ERR_CONNECTION_TIMED_OUT,
            errorText = "ERR_CONNECTION_TIMED_OUT",
            failedUrl = bbcUrl,
            reloadUrl = { reloadedUrls += it },
        )
        assertFalse(manager.activeTab()!!.isLoading)
        assertTrue(notices.last().startsWith("Navigation failed (ERR_CONNECTION_TIMED_OUT):"))
        manager.destroyAll()
    }

    private fun assertRowObeysTwoToOneLabelDensity(entryId: String) {
        val unmergedNode = compose
            .onNodeWithTag("onion_dir_$entryId", useUnmergedTree = true)
            .fetchSemanticsNode()

        val textElements = mutableListOf<String>()
        fun collectTexts(node: androidx.compose.ui.semantics.SemanticsNode) {
            val texts = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
            texts.forEach { textElements += it.text }
            node.children.forEach { collectTexts(it) }
        }
        collectTexts(unmergedNode)

        assertTrue(
            "Expected <= 3 text labels (1 primary + <= 2 secondary, <= 2:1 density) in onion_dir_$entryId, found ${textElements.size}: $textElements",
            textElements.size in 1..3,
        )
    }

    private fun independentBase32Decode(label: String): ByteArray {
        val out = ByteArray((label.length * 5) / 8)
        var acc = 0
        var bits = 0
        var idx = 0
        for (c in label) {
            val v = when (c) {
                in 'a'..'z' -> c - 'a'
                in '2'..'7' -> (c - '2') + 26
                else -> error("Invalid RFC 4648 base32 char: $c")
            }
            acc = (acc shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[idx++] = ((acc ushr bits) and 0xFF).toByte()
            }
        }
        assertEquals(out.size, idx)
        return out
    }

    private fun captureStartPageBitmap(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        view.measure(
            View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY),
        )
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
