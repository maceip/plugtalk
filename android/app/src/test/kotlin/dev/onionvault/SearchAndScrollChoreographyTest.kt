package dev.onionvault

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import dev.onionvault.ui.BrowserUiState
import dev.onionvault.ui.NormalizedBounds
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.PgpOverlayState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class SearchAndScrollChoreographyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun normalize_address_or_search_routes_queries_to_duckduckgo_onion_and_preserves_hosts() {
        val ddgPrefix = TabSessionManager.DEFAULT_ONION_SEARCH_PREFIX
        assertEquals(
            "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/html/?q=",
            ddgPrefix,
        )

        // Blank & explicit schemes preserved:
        assertEquals("about:blank", TabSessionManager.normalizeAddressOrSearch(""))
        assertEquals("about:blank", TabSessionManager.normalizeAddressOrSearch("   "))
        assertEquals("about:blank", TabSessionManager.normalizeAddressOrSearch("about:blank"))
        assertEquals("about:srcdoc", TabSessionManager.normalizeAddressOrSearch("about:srcdoc"))
        assertEquals(
            "http://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion/",
            TabSessionManager.normalizeAddressOrSearch(
                "http://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion/",
            ),
        )
        assertEquals(
            "https://tor.taxi/directory",
            TabSessionManager.normalizeAddressOrSearch("https://tor.taxi/directory"),
        )
        assertEquals(
            "javascript:alert(1)",
            TabSessionManager.normalizeAddressOrSearch("javascript:alert(1)"),
        )
        assertEquals(
            "mailto:tips@example.org",
            TabSessionManager.normalizeAddressOrSearch("mailto:tips@example.org"),
        )

        // Bare `.onion`, clearnet domains, localhost, IPv4, and `[IPv6]` normalize to host URLs:
        assertEquals(
            "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/lite",
            TabSessionManager.normalizeAddressOrSearch(
                "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/lite",
            ),
        )
        assertEquals("https://tor.taxi", TabSessionManager.normalizeAddressOrSearch("tor.taxi"))
        assertEquals(
            "https://www.tor.taxi/foo?bar=1",
            TabSessionManager.normalizeAddressOrSearch("www.tor.taxi/foo?bar=1"),
        )
        assertEquals(
            "https://tor.taxi:8443/dir",
            TabSessionManager.normalizeAddressOrSearch("tor.taxi:8443/dir"),
        )
        assertEquals(
            "http://localhost:8080/status",
            TabSessionManager.normalizeAddressOrSearch("localhost:8080/status"),
        )
        assertEquals(
            "http://192.168.1.1/admin",
            TabSessionManager.normalizeAddressOrSearch("192.168.1.1/admin"),
        )
        assertEquals(
            "https://1.1.1.1",
            TabSessionManager.normalizeAddressOrSearch("1.1.1.1"),
        )
        assertEquals(
            "http://[fd12:3456::1]:8443/",
            TabSessionManager.normalizeAddressOrSearch("[fd12:3456::1]:8443/"),
        )
        assertEquals(
            "https://[2606:4700:4700::1111]",
            TabSessionManager.normalizeAddressOrSearch("[2606:4700:4700::1111]"),
        )

        // Single-word, multi-word, and symbolic queries route to DuckDuckGo v3 `.onion` HTML search:
        assertEquals(
            "${ddgPrefix}dread",
            TabSessionManager.normalizeAddressOrSearch("dread"),
        )
        assertEquals(
            "${ddgPrefix}securedrop",
            TabSessionManager.normalizeAddressOrSearch("securedrop"),
        )
        assertEquals(
            "${ddgPrefix}whistleblower%20drop",
            TabSessionManager.normalizeAddressOrSearch("whistleblower drop"),
        )
        assertEquals(
            "${ddgPrefix}privacy%20news",
            TabSessionManager.normalizeAddressOrSearch("privacy news"),
        )
        assertEquals(
            "${ddgPrefix}how%20to%20verify%20pgp%3F",
            TabSessionManager.normalizeAddressOrSearch("how to verify pgp?"),
        )
        assertEquals(
            "${ddgPrefix}%3Fq%3Donion%2Bdirectory",
            TabSessionManager.normalizeAddressOrSearch("?q=onion+directory"),
        )
        assertEquals(
            "${ddgPrefix}c%2B%2B%20%26%20rust_lang~1.0",
            TabSessionManager.normalizeAddressOrSearch("c++ & rust_lang~1.0"),
        )
        assertEquals(
            "${ddgPrefix}site%3Apropublica.org",
            TabSessionManager.normalizeAddressOrSearch("site:propublica.org"),
        )
        assertEquals(
            "${ddgPrefix}v0.1.32",
            TabSessionManager.normalizeAddressOrSearch("v0.1.32"),
        )
    }

    @Test
    fun touch_gate_tracks_downward_retract_upward_flick_restore_and_navigation_reset_without_consuming_touches() {
        val cdp = CdpDomPgpController()
        val backStack = OnionBackStackManager()
        val manager = TabSessionManager(
            context = context,
            cdpController = cdp,
            backStackManager = backStack,
        )

        val tabId = manager.ensureInitialTab("about:blank")
        assertFalse(manager.podsRetracted.value)
        assertFalse(BrowserUiState().podsRetracted)

        val receivedActions = mutableListOf<Int>()
        val childSurface = object : View(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                receivedActions.add(event.actionMasked)
                return true
            }
        }
        val gate = manager.createTouchGateForTest(tabId)
        gate.addView(
            childSurface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        gate.measure(
            View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1782, View.MeasureSpec.EXACTLY),
        )
        gate.layout(0, 0, 822, 1782)

        val density = gate.resources.displayMetrics.density
        // 1. On StartPage (`about:blank`), scrolling never retracts pods:
        dispatchVerticalDrag(gate, startY = 800f, deltasPx = listOf(-40f * density))
        assertFalse("StartPage must keep podsRetracted == false", manager.podsRetracted.value)
        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
            receivedActions,
        )

        // 2. Navigate active tab to a live LAN URL (permitted synchronously without native JNI in JVM test):
        manager.loadUrl("http://192.168.1.1/index.html", tabId)
        assertEquals("http://192.168.1.1/index.html", manager.activeTab()?.url)
        assertFalse(manager.podsRetracted.value)

        // Micro-jitter <= 12dp does not retract:
        receivedActions.clear()
        dispatchVerticalDrag(gate, startY = 900f, deltasPx = listOf(-8f * density))
        assertFalse(manager.podsRetracted.value)
        assertEquals(3, receivedActions.size)

        // Downward webpage scroll (finger drags UPWARD by > 12dp, deltaY < -12dp) retracts pods,
        // and reversing upward flick (> 10dp downward finger move) within the same gesture restores pods:
        val now = SystemClock.uptimeMillis()
        gate.dispatchTouchEvent(obtainTouch(now, now, MotionEvent.ACTION_DOWN, 400f, 900f))
        gate.dispatchTouchEvent(
            obtainTouch(now, now + 16L, MotionEvent.ACTION_MOVE, 400f, 900f - 16f * density),
        )
        assertTrue("Expected podsRetracted == true after >12dp downward scroll", manager.podsRetracted.value)

        // Reverse direction: small +6dp move (< 10dp) keeps pods retracted, +7dp more (total +13dp > 10dp) restores:
        gate.dispatchTouchEvent(
            obtainTouch(now, now + 32L, MotionEvent.ACTION_MOVE, 400f, 900f - 10f * density),
        )
        assertTrue(manager.podsRetracted.value)
        gate.dispatchTouchEvent(
            obtainTouch(now, now + 48L, MotionEvent.ACTION_MOVE, 400f, 900f - 3f * density),
        )
        assertFalse("Expected podsRetracted == false after >10dp upward flick", manager.podsRetracted.value)
        gate.dispatchTouchEvent(obtainTouch(now, now + 64L, MotionEvent.ACTION_UP, 400f, 900f - 3f * density))

        // Retract again, then restore via explicit `setPodsRetracted(false)` (retracted pod tap):
        dispatchVerticalDrag(gate, startY = 900f, deltasPx = listOf(-20f * density))
        assertTrue(manager.podsRetracted.value)
        manager.setPodsRetracted(false)
        assertFalse(manager.podsRetracted.value)

        // Retract again, then verify navigation / New Tab / StartPage snaps `podsRetracted = false`:
        dispatchVerticalDrag(gate, startY = 900f, deltasPx = listOf(-20f * density))
        assertTrue(manager.podsRetracted.value)
        manager.loadUrl("about:blank", tabId)
        assertFalse("Navigating to StartPage must snap podsRetracted = false", manager.podsRetracted.value)

        manager.loadUrl("http://192.168.1.1/article", tabId)
        dispatchVerticalDrag(gate, startY = 900f, deltasPx = listOf(-25f * density))
        assertTrue(manager.podsRetracted.value)
        manager.createTab(initialUrl = "about:blank", select = true)
        assertFalse("Creating and selecting a new tab must snap podsRetracted = false", manager.podsRetracted.value)
    }

    @Test
    fun zero_interference_pgp_dom_controller_and_overlay_state_helpers_exclude_search_inputs() {
        val controllerSource = listOf(
            File("src/main/kotlin/dev/onionvault/CdpDomPgpController.kt"),
            File("app/src/main/kotlin/dev/onionvault/CdpDomPgpController.kt"),
            File("android/app/src/main/kotlin/dev/onionvault/CdpDomPgpController.kt"),
        ).first { it.isFile }.readText()

        assertFalse(
            "CdpDomPgpController must not inject unsolicited DOM.setAttributeValue inline styles",
            controllerSource.contains("DOM.setAttributeValue") || controllerSource.contains("outline:"),
        )

        val classifiedJson = """
            [
              {"nodeId":10,"backendNodeId":100,"tag":"input","kind":"pgp_textarea","actionLabel":"Encrypt Field","text":"","fingerprintHint":"","isSearchLike":true},
              {"nodeId":20,"backendNodeId":200,"tag":"textarea","kind":"pgp_textarea","actionLabel":"Encrypt Field","text":"Tip draft","fingerprintHint":"","isSearchLike":false},
              {"nodeId":30,"backendNodeId":300,"tag":"pre","kind":"pgp_message","actionLabel":"Decrypt Block","text":"-----BEGIN PGP MESSAGE-----\n...\n-----END PGP MESSAGE-----","fingerprintHint":"ABCD1234","isSearchLike":false}
            ]
        """.trimIndent()

        val passive = CdpDomPgpController.parseClassifiedDomEntries(classifiedJson)
        assertEquals(2, passive.size)
        assertEquals(listOf(20, 30), passive.map { it.nodeId })

        val explicitAll = CdpDomPgpController.parseClassifiedDomEntries(
            classifiedJson,
            includeSearchFields = true,
        )
        assertEquals(3, explicitAll.size)
        assertTrue(explicitAll.first().isSearchLike)

        val bounds = NormalizedBounds(0.1f, 0.2f, 0.9f, 0.4f)
        val searchField = PgpFieldState(
            nodeId = "search",
            kind = "pgp_textarea",
            label = "Encrypt Field",
            bounds = bounds,
            isSearchLike = true,
        )
        val composeField = PgpFieldState(
            nodeId = "compose",
            kind = "pgp_textarea",
            label = "Encrypt Field",
            bounds = bounds,
        )
        val pubkeyUploadField = PgpFieldState(
            nodeId = "pubkey-upload",
            kind = "pgp_public_key",
            label = "Insert My PubKey",
            bounds = bounds,
        )
        val pubkeyBlock = PgpFieldState(
            nodeId = "pubkey-block",
            kind = "pgp_public_key",
            label = "Import Key",
            bounds = bounds,
            armored = "-----BEGIN PGP PUBLIC KEY BLOCK-----\n...\n-----END PGP PUBLIC KEY BLOCK-----",
        )
        val messageBlock = PgpFieldState(
            nodeId = "msg-block",
            kind = "pgp_message",
            label = "Decrypt Block",
            bounds = bounds,
            armored = "-----BEGIN PGP MESSAGE-----\n...\n-----END PGP MESSAGE-----",
        )

        val searchOnlyOverlay = PgpOverlayState(fields = listOf(searchField))
        assertFalse(searchOnlyOverlay.hasArmoredPgpBlock)
        assertNull(searchOnlyOverlay.primaryArmoredBlock)
        assertTrue(searchOnlyOverlay.editablePgpFields.isEmpty())
        assertFalse(searchOnlyOverlay.hasPgpContent)

        val composeOverlay = PgpOverlayState(fields = listOf(searchField, composeField, pubkeyUploadField))
        assertFalse(composeOverlay.hasArmoredPgpBlock)
        assertNull(composeOverlay.primaryArmoredBlock)
        assertEquals(listOf(composeField, pubkeyUploadField), composeOverlay.editablePgpFields)
        assertTrue(composeOverlay.hasPgpContent)

        val mixedArmoredOverlay = PgpOverlayState(
            fields = listOf(pubkeyBlock, messageBlock, composeField),
        )
        assertTrue(mixedArmoredOverlay.hasArmoredPgpBlock)
        assertEquals(messageBlock, mixedArmoredOverlay.primaryArmoredBlock)
        assertEquals(listOf(composeField), mixedArmoredOverlay.editablePgpFields)
        assertTrue(mixedArmoredOverlay.hasPgpContent)
    }

    private fun dispatchVerticalDrag(
        target: View,
        startY: Float,
        deltasPx: List<Float>,
    ) {
        val downTime = SystemClock.uptimeMillis()
        var currentY = startY
        target.dispatchTouchEvent(obtainTouch(downTime, downTime, MotionEvent.ACTION_DOWN, 300f, currentY))
        deltasPx.forEachIndexed { idx, dy ->
            currentY += dy
            target.dispatchTouchEvent(
                obtainTouch(downTime, downTime + (idx + 1) * 16L, MotionEvent.ACTION_MOVE, 300f, currentY),
            )
        }
        target.dispatchTouchEvent(
            obtainTouch(downTime, downTime + (deltasPx.size + 1) * 16L, MotionEvent.ACTION_UP, 300f, currentY),
        )
    }

    private fun obtainTouch(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
}
