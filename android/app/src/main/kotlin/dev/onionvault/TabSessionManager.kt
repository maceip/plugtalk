package dev.onionvault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import com.cefrium.CefriumAudioHandler
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumDisplayHandler
import com.cefrium.CefriumLoadHandler
import com.cefrium.CefriumPickerHandler
import com.cefrium.CefriumRequestHandler
import dev.onionvault.platform.LanAccessController
import dev.onionvault.platform.MediaDisplayNormalizer
import dev.onionvault.platform.ScreenRecordingGuard
import dev.onionvault.platform.TouchpadPointerBridge
import dev.onionvault.ui.isBlankOrStartUrl
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Multi-tab Tor browser session manager backed by [CefriumBrowser] instances on
 * an ephemeral in-memory `CefriumProfile.incognito()` context.
 *
 * Enforces:
 *   1. `setJavaScriptEnabled(false)` + CDP `Emulation.setScriptExecutionDisabled(true)` on every tab.
 *   2. Fail-closed Tor SOCKS5 routing: blocks all non-`about:blank` loads until Arti reports
 *      its bound loopback SOCKS5 listener (`127.0.0.1:<port>`).
 *   3. Per-request URL policy (`OvNative.nativeCheckUrlPolicy`): blocks clearnet/disallowed
 *      schemes, `TYPE_PING`, `TYPE_CSP_REPORT`, and `TYPE_SCRIPT` at the network interceptor.
 *   4. Hardened text-selection `ActionMode.Callback` that strips external `WEB_SEARCH`,
 *      `SHARE`, and `ACTION_PROCESS_TEXT` intents while surfacing in-app OpenPGP actions.
 *   5. Touch-suppression gate (`setTouchSuppressed`) so native modals and predictive-back
 *      gestures never leak touch events to the underlying Chromium surface.
 */
class TabSessionManager(
    private val context: Context,
    private val cdpController: CdpDomPgpController,
    private val backStackManager: OnionBackStackManager,
    initialHiddenServiceOnly: Boolean = false,
    val lanAccessController: LanAccessController = LanAccessController(),
    val mediaDisplayNormalizer: MediaDisplayNormalizer = MediaDisplayNormalizer(),
    private val onContextMenu: (ContextMenuTarget) -> Unit = {},
    private val onSelectionPgpAction: (SelectionPgpAction) -> Unit = {},
    private val onClearnetConsentRequired: (
        host: String,
        normalizedUrl: String,
        suggestedPattern: String,
    ) -> Unit = { _, _, _ -> },
    private val onLocalNetworkPermissionRequired: (targetUrl: String) -> Unit = {},
    private val onColorPickerRequested: (
        initialColor: Int,
        callback: CefriumPickerHandler.ColorCallback,
    ) -> Boolean = { _, _ -> false },
    private val onViewportInteraction: (Boolean) -> Unit = {},
    private val onUrlBlocked: (tabId: String, blockedUrl: String, reason: String) -> Unit = { _, _, _ -> },
    private val onSecurityNotice: (String) -> Unit = {},
    private val onNavigationRecovered: (tabId: String) -> Unit = {},
) {

    data class TabState(
        val id: String,
        val url: String,
        val title: String,
        val isLoading: Boolean,
        val loadProgress: Int,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
        val zoomPercent: Int,
        val isOnion: Boolean,
        val isPrivate: Boolean = false,
        val isPlayingAudio: Boolean = false,
        val isMediaPausedByUser: Boolean = false,
        val isAudioMuted: Boolean = false,
        val isFullscreenVideo: Boolean = false,
        val hasWebRtcVideo: Boolean = false,
        val hasWebRtcAudio: Boolean = false,
        val atDocumentBottom: Boolean = false,
        val latestScrollY: Int = 0,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val lastBlockedUrl: String? = null,
        val lastBlockReason: String? = null,
    ) {
        val hasActiveVideo: Boolean
            get() = isFullscreenVideo ||
                hasWebRtcVideo ||
                (videoWidth > 0 && videoHeight > 0 && isPlayingAudio)

        val etldPlusOneOrigin: String
            get() = PipController.extractEtldPlusOneOrigin(url)
    }

    data class ContextMenuTarget(
        val tabId: String,
        val linkUrl: String?,
        val mediaUrl: String?,
        val selectedText: String?,
        val textClassification: OvNative.TextClassification?,
    )

    data class SelectionPgpAction(
        val tabId: String,
        val selectedText: String,
        val classification: OvNative.TextClassification,
    )

    private class ManagedTab(
        val id: String,
        val browser: CefriumBrowser?,
        val surfaceWrapper: TouchGateFrameLayout?,
        var state: TabState,
        var latestDomHtml: String = "",
        var overrideDisplayUrl: String? = null,
        var pendingNavigationUrl: String? = null,
        var hadErrorInCurrentLoad: Boolean = false,
    )

    private inner class TouchGateFrameLayout(
        ctx: Context,
        private val tabId: String,
    ) : FrameLayout(ctx) {
        val virtualPointer = TouchpadPointerBridge.VirtualPointerState()

        private var trackingPointerId: Int = MotionEvent.INVALID_POINTER_ID
        private var lastTrackY: Float = 0f
        private var directionalDeltaY: Float = 0f
        private var multiTouchGesture: Boolean = false

        private val refreshRunnable = Runnable {
            val browser = sessions[tabId]?.browser ?: return@Runnable
            cdpController.refreshViewportBounds(tabId, browser)
        }

        private val settleArrRunnable = Runnable {
            onViewportInteraction(false)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (touchSuppressed) {
                return true
            }
            trackScrollChoreography(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                -> {
                    removeCallbacks(settleArrRunnable)
                    onViewportInteraction(true)
                }
            }
            val handled = super.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                removeCallbacks(refreshRunnable)
                postDelayed(refreshRunnable, 120L)
                removeCallbacks(settleArrRunnable)
                postDelayed(settleArrRunnable, 350L)
            }
            return handled
        }

        private fun trackScrollChoreography(ev: MotionEvent) {
            if (tabId != _activeTabId.value) return
            val activeUrl = sessions[tabId]?.state?.url.orEmpty()
            if (isBlankOrStartUrl(activeUrl)) {
                if (_podsRetracted.value) {
                    _podsRetracted.value = false
                }
                trackingPointerId = MotionEvent.INVALID_POINTER_ID
                directionalDeltaY = 0f
                multiTouchGesture = false
                return
            }

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    trackingPointerId = ev.getPointerId(0)
                    lastTrackY = ev.y
                    directionalDeltaY = 0f
                    multiTouchGesture = false
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    multiTouchGesture = true
                    directionalDeltaY = 0f
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    val liftedIdx = ev.actionIndex
                    if (ev.getPointerId(liftedIdx) == trackingPointerId) {
                        val nextIdx = if (liftedIdx == 0) 1 else 0
                        if (nextIdx < ev.pointerCount) {
                            trackingPointerId = ev.getPointerId(nextIdx)
                            lastTrackY = ev.getY(nextIdx)
                        } else {
                            trackingPointerId = MotionEvent.INVALID_POINTER_ID
                        }
                    }
                    directionalDeltaY = 0f
                    if (ev.pointerCount - 1 <= 1) {
                        multiTouchGesture = false
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!multiTouchGesture && ev.pointerCount == 1) {
                        val idx = ev.findPointerIndex(trackingPointerId)
                        if (idx >= 0) {
                            val currentY = ev.getY(idx)
                            val stepDy = currentY - lastTrackY
                            lastTrackY = currentY
                            directionalDeltaY = if (
                                (stepDy > 0f && directionalDeltaY < 0f) ||
                                (stepDy < 0f && directionalDeltaY > 0f)
                            ) {
                                stepDy
                            } else {
                                directionalDeltaY + stepDy
                            }
                            val density = resources.displayMetrics.density.coerceAtLeast(1f)
                            val retractPx = SCROLL_RETRACT_THRESHOLD_DP * density
                            val restorePx = SCROLL_RESTORE_THRESHOLD_DP * density
                            if (directionalDeltaY < -retractPx) {
                                setPodsRetracted(true)
                            } else if (directionalDeltaY > restorePx) {
                                setPodsRetracted(false)
                            }
                        }
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    trackingPointerId = MotionEvent.INVALID_POINTER_ID
                    directionalDeltaY = 0f
                    multiTouchGesture = false
                }
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (touchSuppressed) {
                return true
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
            if (touchSuppressed) return true
            if (dispatchRelativeTouchpadOrPointerMotion(event)) {
                return true
            }
            return super.onCapturedPointerEvent(event)
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            if (touchSuppressed) return true
            if (event.actionMasked == MotionEvent.ACTION_SCROLL && tabId == _activeTabId.value) {
                val activeUrl = sessions[tabId]?.state?.url.orEmpty()
                if (!isBlankOrStartUrl(activeUrl)) {
                    val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if (vScroll < -0.2f) {
                        setPodsRetracted(true)
                    } else if (vScroll > 0.2f) {
                        setPodsRetracted(false)
                    }
                }
            }
            if (dispatchRelativeTouchpadOrPointerMotion(event)) {
                return true
            }
            return super.onGenericMotionEvent(event)
        }

        private fun dispatchRelativeTouchpadOrPointerMotion(event: MotionEvent): Boolean {
            val coords = TouchpadPointerBridge.accumulateRelativeMotion(
                event = event,
                state = virtualPointer,
                viewportWidth = width,
                viewportHeight = height,
            ) ?: return false
            val browser = sessions[tabId]?.browser ?: return false
            removeCallbacks(settleArrRunnable)
            onViewportInteraction(true)
            runCatching { browser.sendMouseMove(coords.first, coords.second) }
            postDelayed(settleArrRunnable, 350L)
            return true
        }
    }

    companion object {
        private const val TAG = "TabSessionManager"
        private const val MENU_ID_PGP_ACTION = 0x54524150
        private const val MIN_ZOOM_PERCENT = 50
        private const val MAX_ZOOM_PERCENT = 300
        private val HEX_UPPER = "0123456789ABCDEF".toCharArray()

        const val DEFAULT_ONION_SEARCH_PREFIX: String =
            "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/html/?q="

        const val SCROLL_RETRACT_THRESHOLD_DP: Float = 12f
        const val SCROLL_RESTORE_THRESHOLD_DP: Float = 10f

        /**
         * Normalizes user-entered address bar text:
         * - Preserves `about:` URIs and explicit `http://` or `https://` URLs (plus
         *   explicit schemes so URL policy rejects unsafe schemes fail-closed).
         * - Treats bare input without whitespace as a domain/host URL only when it ends
         *   with `.onion`, is `localhost` / IPv4 / `[IPv6]`, or has a valid hostname
         *   before path, port, or end containing at least one `.` and an alphabetic TLD >= 2 chars.
         * - Routes all other non-empty queries to DuckDuckGo's zero-JS HTML v3 `.onion`
         *   endpoint (`DEFAULT_ONION_SEARCH_PREFIX` + RFC 3986 percent-encoded query).
         */
        fun normalizeAddressOrSearch(rawInput: String): String {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty() || trimmed.equals("about:blank", ignoreCase = true)) {
                return "about:blank"
            }
            val lower = trimmed.lowercase()
            if (lower.startsWith("about:") ||
                lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("data:") ||
                lower.startsWith("blob:") ||
                lower.startsWith("javascript:") ||
                lower.startsWith("vbscript:") ||
                lower.startsWith("file:") ||
                lower.startsWith("content:") ||
                lower.startsWith("intent:") ||
                lower.startsWith("mailto:") ||
                lower.startsWith("tel:") ||
                lower.startsWith("sms:") ||
                lower.startsWith("geo:") ||
                lower.startsWith("magnet:") ||
                lower.startsWith("chrome:") ||
                lower.startsWith("chrome-error:") ||
                lower.startsWith("view-source:")
            ) {
                return trimmed
            }

            if (trimmed.none { it.isWhitespace() }) {
                val schemeIdx = trimmed.indexOf("://")
                if (schemeIdx > 0) {
                    val schemeCandidate = trimmed.substring(0, schemeIdx)
                    val validScheme = schemeCandidate[0].isLetter() &&
                        schemeCandidate.all { ch ->
                            ch.isLetterOrDigit() || ch == '+' || ch == '-' || ch == '.'
                        }
                    if (validScheme) {
                        return trimmed
                    }
                }
            }

            if (isBareDomainOrHost(trimmed)) {
                val authority = trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
                val hostOnly = stripPort(authority).trimEnd('.')
                val hostLower = hostOnly.lowercase()
                val scheme = if (
                    hostLower == "onion" ||
                    hostLower.endsWith(".onion") ||
                    LanAccessController.isLocalNetworkUrlOrHost(trimmed)
                ) {
                    "http://"
                } else {
                    "https://"
                }
                return "$scheme$trimmed"
            }

            return "$DEFAULT_ONION_SEARCH_PREFIX${percentEncodeRfc3986(trimmed)}"
        }

        fun percentEncodeRfc3986(input: String): String {
            val bytes = input.encodeToByteArray()
            val out = StringBuilder(bytes.size + 8)
            for (b in bytes) {
                val u = b.toInt() and 0xFF
                val isUnreserved = (u in 'A'.code..'Z'.code) ||
                    (u in 'a'.code..'z'.code) ||
                    (u in '0'.code..'9'.code) ||
                    u == '-'.code ||
                    u == '.'.code ||
                    u == '_'.code ||
                    u == '~'.code
                if (isUnreserved) {
                    out.append(u.toChar())
                } else {
                    out.append('%')
                    out.append(HEX_UPPER[u ushr 4])
                    out.append(HEX_UPPER[u and 0x0F])
                }
            }
            return out.toString()
        }

        private fun isBareDomainOrHost(trimmed: String): Boolean {
            if (trimmed.isEmpty() ||
                trimmed.any { it.isWhitespace() || it.code <= 0x20 || it.code == 0x7F }
            ) {
                return false
            }

            val authority = trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
            if (authority.isEmpty() || authority.contains('@')) {
                return false
            }

            if (authority.startsWith('[')) {
                val closeIdx = authority.indexOf(']')
                if (closeIdx <= 1) return false
                val ipv6Str = authority.substring(1, closeIdx)
                if (!isIpv6Literal(ipv6Str)) return false
                val rest = authority.substring(closeIdx + 1)
                return rest.isEmpty() || (rest.startsWith(':') && isValidPort(rest.substring(1)))
            }

            val colonIdx = authority.indexOf(':')
            val hostCandidate = if (colonIdx >= 0) {
                val port = authority.substring(colonIdx + 1)
                if (!isValidPort(port)) return false
                authority.substring(0, colonIdx)
            } else {
                authority
            }

            val host = hostCandidate.trimEnd('.')
            if (host.isEmpty()) return false
            val hostLower = host.lowercase()

            if (hostLower == "localhost") return true
            if (isIpv4Literal(host)) return true

            if (hostLower == "onion" || hostLower.endsWith(".onion")) {
                return hostLower.all { ch ->
                    ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '-'
                }
            }

            val labels = hostLower.split('.')
            if (labels.size < 2) return false
            val validLabels = labels.all { label ->
                label.isNotEmpty() &&
                    label.length <= 63 &&
                    !label.startsWith('-') &&
                    !label.endsWith('-') &&
                    label.all { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '-' }
            }
            if (!validLabels) return false
            val tld = labels.last()
            return (tld.length >= 2 && tld.all { it in 'a'..'z' }) ||
                (tld.length >= 5 && tld.startsWith("xn--"))
        }

        private fun stripPort(hostPort: String): String {
            if (hostPort.startsWith('[')) {
                val closeIdx = hostPort.indexOf(']')
                return if (closeIdx > 0) hostPort.substring(1, closeIdx) else hostPort
            }
            val colonIdx = hostPort.lastIndexOf(':')
            if (colonIdx > 0) {
                val port = hostPort.substring(colonIdx + 1)
                if (port.isNotEmpty() && port.all { it in '0'..'9' }) {
                    return hostPort.substring(0, colonIdx)
                }
            }
            return hostPort
        }

        private fun isValidPort(port: String): Boolean {
            if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' }) return false
            val num = port.toIntOrNull() ?: return false
            return num in 1..65535
        }

        private fun isIpv4Literal(raw: String): Boolean {
            val parts = raw.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                val n = part.toIntOrNull() ?: return false
                n in 0..255 && part == n.toString()
            }
        }

        private fun isIpv6Literal(raw: String): Boolean {
            if (raw.isEmpty() || !raw.contains(':') || raw.contains(":::")) return false
            val doubleColonCount = raw.windowed(size = 2, step = 1).count { it == "::" }
            if (doubleColonCount > 1) return false

            val hasDoubleColon = doubleColonCount == 1
            val halves = if (hasDoubleColon) raw.split("::", limit = 2) else listOf(raw)
            val leftGroups = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
            val rightGroups = if (hasDoubleColon && halves[1].isNotEmpty()) {
                halves[1].split(':')
            } else {
                emptyList()
            }

            val mutableRight = rightGroups.toMutableList()
            var ipv4TailSegments = 0
            val tailCandidate = mutableRight.lastOrNull()
                ?: if (!hasDoubleColon) leftGroups.lastOrNull() else null
            if (tailCandidate != null && tailCandidate.contains('.')) {
                if (!isIpv4Literal(tailCandidate)) return false
                ipv4TailSegments = 2
                if (mutableRight.isNotEmpty()) {
                    mutableRight.removeAt(mutableRight.lastIndex)
                } else {
                    return validateIpv6Hextets(
                        left = leftGroups.dropLast(1),
                        right = emptyList(),
                        extraSegments = ipv4TailSegments,
                        hasDoubleColon = hasDoubleColon,
                    )
                }
            }
            return validateIpv6Hextets(leftGroups, mutableRight, ipv4TailSegments, hasDoubleColon)
        }

        private fun validateIpv6Hextets(
            left: List<String>,
            right: List<String>,
            extraSegments: Int,
            hasDoubleColon: Boolean,
        ): Boolean {
            val allGroups = left + right
            if (allGroups.any { group ->
                    group.isEmpty() || group.length > 4 || group.any { ch ->
                        ch !in '0'..'9' && ch !in 'a'..'f' && ch !in 'A'..'F'
                    }
                }
            ) {
                return false
            }
            val totalSegments = allGroups.size + extraSegments
            return if (hasDoubleColon) totalSegments <= 7 else totalSegments == 8
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = LinkedHashMap<String, ManagedTab>()

    private val _tabs = MutableStateFlow<List<TabState>>(emptyList())
    val tabs: StateFlow<List<TabState>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _hiddenServiceOnly = MutableStateFlow(initialHiddenServiceOnly)
    val hiddenServiceOnly: StateFlow<Boolean> = _hiddenServiceOnly.asStateFlow()

    private val _activeSocksAddr = MutableStateFlow("")
    val activeSocksAddr: StateFlow<String> = _activeSocksAddr.asStateFlow()

    private val _podsRetracted = MutableStateFlow(false)
    val podsRetracted: StateFlow<Boolean> = _podsRetracted.asStateFlow()

    fun setPodsRetracted(retracted: Boolean) {
        _podsRetracted.value = retracted
    }

    @VisibleForTesting
    internal fun createTouchGateForTest(tabId: String): FrameLayout =
        TouchGateFrameLayout(context, tabId)

    @Volatile
    private var touchSuppressed: Boolean = false

    @Volatile
    private var lastConsentInterceptedUrl: String? = null

    fun activeTab(): TabState? {
        val currentId = _activeTabId.value ?: return null
        return sessions[currentId]?.state
    }

    fun activeBrowser(): CefriumBrowser? {
        val currentId = _activeTabId.value ?: return null
        return sessions[currentId]?.browser
    }

    fun activeSurfaceContainer(): FrameLayout? {
        val currentId = _activeTabId.value ?: return null
        return sessions[currentId]?.surfaceWrapper
    }

    fun surfaceContainerFor(tabId: String): FrameLayout? =
        sessions[tabId]?.surfaceWrapper

    fun browserFor(tabId: String): CefriumBrowser? =
        sessions[tabId]?.browser

    fun ensureInitialTab(initialUrl: String = "about:blank"): String {
        val existing = _activeTabId.value
        if (existing != null && sessions.containsKey(existing)) {
            return existing
        }
        return createTab(initialUrl = initialUrl, select = true)
    }

    fun createTab(
        initialUrl: String = "about:blank",
        select: Boolean = true,
        loadInitialBlank: Boolean = true,
        isPrivate: Boolean = false,
    ): String {
        val tabId = "tab-${UUID.randomUUID().toString().take(8)}"
        val activity = resolveActivity(context)
        val browser = if (activity != null && TrapApplication.isEngineReady) {
            runCatching {
                CefriumBrowser.createWithSurface(activity, TrapApplication.incognitoProfile())
            }.onFailure {
                Log.e(TAG, "Failed to create CefriumBrowser for $tabId", it)
            }.getOrNull()
        } else {
            null
        }

        val wrapper = if (browser != null) {
            TouchGateFrameLayout(context, tabId).apply {
                ScreenRecordingGuard.applyContentSensitivity(this, sensitive = isPrivate)
                val cefSurface = browser.surfaceContainer
                ScreenRecordingGuard.applyContentSensitivity(cefSurface, sensitive = isPrivate)
                (cefSurface.parent as? ViewGroup)?.removeView(cefSurface)
                addView(
                    cefSurface,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        } else {
            null
        }

        val normalizedInitial = initialUrl.trim().ifEmpty { "about:blank" }
        val initialDecision = evaluateUrlPolicy(normalizedInitial, _hiddenServiceOnly.value)
        val startUrl = if (initialDecision.allowed) initialDecision.normalizedUrl else "about:blank"

        val initialState = TabState(
            id = tabId,
            url = startUrl,
            title = displayTitleForUrl(startUrl),
            isLoading = startUrl != "about:blank",
            loadProgress = if (startUrl == "about:blank") 100 else 10,
            canGoBack = false,
            canGoForward = false,
            zoomPercent = 100,
            isOnion = initialDecision.isOnion,
            isPrivate = isPrivate,
        )

        val managed = ManagedTab(
            id = tabId,
            browser = browser,
            surfaceWrapper = wrapper,
            state = initialState,
            pendingNavigationUrl = startUrl.takeIf { it != "about:blank" },
        )
        sessions[tabId] = managed

        if (browser != null) {
            configureHardenedBrowser(tabId, browser)
        }

        if (select || _activeTabId.value == null) {
            selectTabInternal(tabId)
        } else {
            browser?.setPageVisible(false)
            publishTabsSnapshot()
        }

        if (startUrl != "about:blank") {
            loadUrl(startUrl, tabId)
        } else if (loadInitialBlank) {
            browser?.loadUrl("about:blank")
        }

        return tabId
    }

    fun selectTab(tabId: String) {
        if (!sessions.containsKey(tabId)) return
        selectTabInternal(tabId)
    }

    fun closeTab(tabId: String) {
        val removed = sessions.remove(tabId) ?: return
        cdpController.detachTab(tabId)
        backStackManager.clearTab(tabId)
        teardownBrowser(removed)

        if (sessions.isEmpty()) {
            _activeTabId.value = null
            createTab(initialUrl = "about:blank", select = true)
            runCatching { CefriumBrowser.reapUnusedRenderers() }
            return
        }

        if (_activeTabId.value == tabId) {
            val nextId = sessions.keys.last()
            selectTabInternal(nextId)
        } else {
            publishTabsSnapshot()
        }
        runCatching { CefriumBrowser.reapUnusedRenderers() }
    }

    /**
     * Destroys every open browser tab, rotates the ephemeral incognito profile,
     * wipes all CDP & predictive-back snapshots in RAM, rotates Arti Tor's stream
     * isolation token, and opens a fresh `about:blank` tab.
     */
    fun resetAllSessionsAndIdentity(targetUrlOrEmpty: String = ""): Int {
        val existing = sessions.values.toList()
        sessions.clear()
        _activeTabId.value = null
        _podsRetracted.value = false
        cdpController.clearAll()
        backStackManager.clearAll()

        existing.forEach { teardownBrowser(it) }
        runCatching { CefriumBrowser.reapUnusedRenderers() }

        if (TrapApplication.isEngineReady) {
            TrapApplication.resetIncognitoProfile()
            TrapApplication.applyProfileSocksServer(_activeSocksAddr.value)
        }

        val isolatedGroups = if (OvNative.isLoaded) {
            runCatching { OvNative.nativeResetTorIsolation(targetUrlOrEmpty) }.getOrDefault(0)
        } else {
            0
        }

        val target = targetUrlOrEmpty.trim().ifEmpty { "about:blank" }
        createTab(initialUrl = target, select = true)
        return isolatedGroups
    }

    fun updateTorSocksProxy(socksAddr: String) {
        val clean = socksAddr.trim().removePrefix("socks5://")
        if (_activeSocksAddr.value == clean) return
        _activeSocksAddr.value = clean
        if (TrapApplication.isEngineReady) {
            TrapApplication.applyProfileSocksServer(clean)
        }
    }

    fun setHiddenServiceOnly(enabled: Boolean) {
        _hiddenServiceOnly.value = enabled
        if (OvNative.isLoaded) {
            runCatching { OvNative.nativeSetHiddenServiceOnly(enabled) }
        }
    }

    fun recordTabDomHtml(tabId: String, domHtml: String) {
        sessions[tabId]?.latestDomHtml = domHtml
    }

    fun loadUrl(
        rawInput: String,
        tabId: String? = _activeTabId.value,
    ): OvNative.UrlPolicyResult {
        val targetTabId = tabId ?: ensureInitialTab()
        val managed = sessions[targetTabId] ?: return OvNative.UrlPolicyResult(
            allowed = false,
            normalizedUrl = "",
            isOnion = false,
            reason = "Tab not found",
        )

        val candidate = normalizeUserAddressInput(rawInput)
        val lanDecision = lanAccessController.evaluateRequest(
            pageUrl = managed.state.url,
            targetUrl = candidate,
            isMainFrame = true,
            hiddenServiceOnly = _hiddenServiceOnly.value,
            hasOsPermission = lanAccessController.hasLocalNetworkPermission(context),
        )
        when (lanDecision) {
            LanAccessController.LanDecision.PROMPT_OS_PERMISSION -> {
                onLocalNetworkPermissionRequired(candidate)
                return OvNative.UrlPolicyResult(
                    allowed = false,
                    normalizedUrl = candidate,
                    isOnion = false,
                    reason = "Local network permission required",
                )
            }

            LanAccessController.LanDecision.BLOCKED_HIDDEN_SERVICE_ONLY,
            LanAccessController.LanDecision.BLOCKED_CROSS_ORIGIN_LAN_PROBE,
            -> {
                val reason = "Local network navigation blocked in Hidden Service Only mode"
                updateTabState(targetTabId) {
                    it.copy(
                        lastBlockedUrl = candidate,
                        lastBlockReason = reason,
                    )
                }
                onUrlBlocked(targetTabId, candidate, reason)
                return OvNative.UrlPolicyResult(
                    allowed = false,
                    normalizedUrl = candidate,
                    isOnion = false,
                    reason = reason,
                )
            }

            LanAccessController.LanDecision.ALLOW_LAN -> {
                if (candidate.startsWith("http://", ignoreCase = true) ||
                    candidate.startsWith("https://", ignoreCase = true)
                ) {
                    val browser = managed.browser
                    if (browser != null &&
                        managed.state.url.isNotBlank() &&
                        managed.state.url != "about:blank"
                    ) {
                        backStackManager.capture(
                            tabId = targetTabId,
                            browser = browser,
                            domHtml = managed.latestDomHtml,
                            nodes = cdpController.nodesForTab(targetTabId),
                        )
                    }
                    managed.overrideDisplayUrl = null
                    managed.pendingNavigationUrl = candidate
                    if (targetTabId == _activeTabId.value) {
                        _podsRetracted.value = false
                    }
                    updateTabState(targetTabId) {
                        it.copy(
                            url = candidate,
                            title = it.title.ifBlank { displayTitleForUrl(candidate) },
                            isLoading = true,
                            loadProgress = 10,
                            isOnion = false,
                            lastBlockedUrl = null,
                            lastBlockReason = null,
                        )
                    }
                    browser?.loadUrl(candidate)
                    return OvNative.UrlPolicyResult(
                        allowed = true,
                        normalizedUrl = candidate,
                        isOnion = false,
                        reason = "",
                    )
                }
            }

            LanAccessController.LanDecision.NOT_LAN -> Unit
        }

        val decision = evaluateUrlPolicy(candidate, _hiddenServiceOnly.value)
        if (!decision.allowed) {
            if (decision.requiresClearnetConsent) {
                val normalized = decision.normalizedUrl.ifBlank { candidate }
                val host = decision.host.ifBlank {
                    runCatching { Uri.parse(normalized).host.orEmpty() }.getOrDefault("")
                }.trim().trimEnd('.').lowercase()
                val suggested = decision.suggestedPattern.ifBlank {
                    OvNative.suggestGlob(host)
                }
                onClearnetConsentRequired(host, normalized, suggested)
                return decision
            }
            updateTabState(targetTabId) {
                it.copy(
                    lastBlockedUrl = candidate,
                    lastBlockReason = decision.reason,
                )
            }
            onUrlBlocked(targetTabId, candidate, decision.reason)
            return decision
        }

        val normalized = decision.normalizedUrl
        if (normalized != "about:blank" && _activeSocksAddr.value.isBlank() && TrapApplication.isEngineReady) {
            val waitReason = "Waiting for Tor SOCKS5 tunnel to bind before connecting"
            updateTabState(targetTabId) {
                it.copy(
                    lastBlockedUrl = normalized,
                    lastBlockReason = waitReason,
                )
            }
            onSecurityNotice(waitReason)
            return OvNative.UrlPolicyResult(
                allowed = false,
                normalizedUrl = normalized,
                isOnion = decision.isOnion,
                reason = waitReason,
            )
        }

        val browser = managed.browser
        if (browser != null && managed.state.url.isNotBlank() && managed.state.url != "about:blank") {
            backStackManager.capture(
                tabId = targetTabId,
                browser = browser,
                domHtml = managed.latestDomHtml,
                nodes = cdpController.nodesForTab(targetTabId),
            )
        }

        managed.overrideDisplayUrl = null
        managed.pendingNavigationUrl = normalized.takeIf { it != "about:blank" }
        if (targetTabId == _activeTabId.value) {
            _podsRetracted.value = false
        }
        updateTabState(targetTabId) {
            it.copy(
                url = normalized,
                title = if (normalized == "about:blank") "New Tab" else it.title,
                isLoading = normalized != "about:blank",
                loadProgress = if (normalized == "about:blank") 100 else 10,
                isOnion = decision.isOnion,
                lastBlockedUrl = null,
                lastBlockReason = null,
            )
        }

        browser?.loadUrl(normalized)
        return decision
    }

    /**
     * Injects a zero-JavaScript HTML document into the live [CefriumBrowser] frame via
     * `Page.getFrameTree` + `Page.setDocumentContent` for instrumentation verification
     * while preserving `setJavaScriptEnabled(false)` and `Emulation.setScriptExecutionDisabled(true)`.
     */
    @VisibleForTesting
    internal fun loadInlineHtmlForTest(
        html: String,
        displayUrl: String,
        tabId: String? = _activeTabId.value,
        onComplete: (Boolean) -> Unit = {},
    ) {
        mainHandler.post {
            val targetTabId = tabId ?: ensureInitialTab()
            val managed = sessions[targetTabId]
            val browser = managed?.browser
            if (managed == null || browser == null) {
                onComplete(false)
                return@post
            }
            managed.overrideDisplayUrl = displayUrl
            if (targetTabId == _activeTabId.value) {
                _podsRetracted.value = false
            }
            updateTabState(targetTabId) {
                it.copy(
                    url = displayUrl,
                    title = displayTitleForUrl(displayUrl),
                    isLoading = false,
                    loadProgress = 100,
                    isOnion = isOnionAddress(displayUrl),
                    lastBlockedUrl = null,
                    lastBlockReason = null,
                )
            }
            browser.executeDevToolsMethod("Page.getFrameTree", "{}") { treeOk, treeJson ->
                val frameId = if (treeOk && !treeJson.isNullOrBlank()) {
                    runCatching {
                        JSONObject(treeJson)
                            .getJSONObject("frameTree")
                            .getJSONObject("frame")
                            .getString("id")
                    }.getOrNull()
                } else {
                    null
                }
                if (frameId.isNullOrBlank()) {
                    mainHandler.post { onComplete(false) }
                    return@executeDevToolsMethod
                }
                val setParams = JSONObject()
                    .put("frameId", frameId)
                    .put("html", html)
                    .toString()
                browser.executeDevToolsMethod("Page.setDocumentContent", setParams) { setOk, _ ->
                    mainHandler.post {
                        if (setOk) {
                            cdpController.attachAndScan(targetTabId, browser, displayUrl)
                        }
                        onComplete(setOk)
                    }
                }
            }
        }
    }

    fun reload(tabId: String? = _activeTabId.value) {
        val managed = tabId?.let { sessions[it] } ?: return
        if (managed.state.url != "about:blank" && _activeSocksAddr.value.isBlank() && TrapApplication.isEngineReady) {
            onSecurityNotice("Tor SOCKS5 tunnel is still initializing")
            return
        }
        val browserUrl = managed.browser?.url?.trim().orEmpty()
        if (managed.state.url != "about:blank" &&
            (browserUrl.isEmpty() || browserUrl == "about:blank" || browserUrl.startsWith("chrome-error://"))
        ) {
            managed.pendingNavigationUrl = managed.state.url
            updateTabState(managed.id) {
                it.copy(isLoading = true, loadProgress = 10)
            }
            managed.browser?.loadUrl(managed.state.url)
        } else {
            managed.browser?.reload()
        }
    }

    fun stopLoading(tabId: String? = _activeTabId.value) {
        val managed = tabId?.let { sessions[it] } ?: return
        managed.browser?.stopLoading()
        updateTabState(managed.id) {
            it.copy(isLoading = false, loadProgress = 100)
        }
    }

    fun goBack(tabId: String? = _activeTabId.value): Boolean {
        val managed = tabId?.let { sessions[it] } ?: return false
        val browser = managed.browser ?: return false
        if (!browser.canGoBack()) return false
        if (managed.id == _activeTabId.value) {
            _podsRetracted.value = false
        }
        backStackManager.popNewest(managed.id)
        browser.goBack()
        return true
    }

    fun goForward(tabId: String? = _activeTabId.value): Boolean {
        val managed = tabId?.let { sessions[it] } ?: return false
        val browser = managed.browser ?: return false
        if (!managed.state.canGoForward && !canNavigateForward(browser)) {
            return false
        }
        if (managed.id == _activeTabId.value) {
            _podsRetracted.value = false
        }
        backStackManager.capture(
            tabId = managed.id,
            browser = browser,
            domHtml = managed.latestDomHtml,
            nodes = cdpController.nodesForTab(managed.id),
        )
        browser.goForward()
        return true
    }

    fun setZoomPercent(percent: Int, tabId: String? = _activeTabId.value) {
        val managed = tabId?.let { sessions[it] } ?: return
        val clamped = percent.coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)
        val cefZoomLevel = if (clamped == 100) {
            0.0
        } else {
            ln(clamped / 100.0) / ln(1.2)
        }
        managed.browser?.setZoomLevel(cefZoomLevel)
        updateTabState(managed.id) { it.copy(zoomPercent = clamped) }
        managed.browser?.let { cdpController.refreshViewportBounds(managed.id, it) }
    }

    fun stepZoom(deltaPercent: Int, tabId: String? = _activeTabId.value): Int {
        val managed = tabId?.let { sessions[it] } ?: return 100
        val next = (managed.state.zoomPercent + deltaPercent).coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)
        setZoomPercent(next, managed.id)
        return next
    }

    fun setTouchSuppressed(suppressed: Boolean) {
        touchSuppressed = suppressed
    }

    fun toggleActiveMediaPlayPause(tabId: String? = _activeTabId.value): Boolean {
        val managed = tabId?.let { sessions[it] } ?: return false
        val browser = managed.browser
        val currentlyPaused = managed.state.isMediaPausedByUser
        return if (currentlyPaused) {
            runCatching { browser?.resumeMedia() }
            updateTabState(managed.id) {
                it.copy(
                    isMediaPausedByUser = false,
                    isPlayingAudio = true,
                )
            }
            true
        } else {
            runCatching { browser?.suspendMedia() }
            updateTabState(managed.id) {
                it.copy(
                    isMediaPausedByUser = true,
                    isPlayingAudio = false,
                )
            }
            false
        }
    }

    fun toggleActiveAudioMute(tabId: String? = _activeTabId.value): Boolean {
        val managed = tabId?.let { sessions[it] } ?: return false
        val browser = managed.browser
        val nextMuted = !managed.state.isAudioMuted
        runCatching { browser?.setAudioMuted(nextMuted) }
        val actualMuted = runCatching { browser?.isAudioMuted }.getOrNull() ?: nextMuted
        updateTabState(managed.id) { it.copy(isAudioMuted = actualMuted) }
        return actualMuted
    }

    /**
     * Steps active HTML5 media forward (~10s) or triggers next-track via zero-JS CDP
     * (`DOM.getDocument` -> `DOM.querySelector("video, audio")` -> `DOM.focus` ->
     * `Input.dispatchKeyEvent` `ArrowRight` / `MediaTrackNext`).
     */
    fun seekActiveMediaForward(tabId: String? = _activeTabId.value) {
        val managed = tabId?.let { sessions[it] } ?: return
        val browser = managed.browser ?: return
        browser.executeDevToolsMethod("DOM.getDocument", """{"depth":1}""") { docOk, docJson ->
            val rootNodeId = if (docOk && !docJson.isNullOrBlank()) {
                runCatching { JSONObject(docJson).getJSONObject("root").optInt("nodeId", 0) }
                    .getOrDefault(0)
            } else {
                0
            }
            if (rootNodeId <= 0) {
                dispatchCdpKeyTap(browser, "MediaTrackNext", "MediaTrackNext", 176)
                return@executeDevToolsMethod
            }
            val queryParams = JSONObject()
                .put("nodeId", rootNodeId)
                .put("selector", "video, audio")
                .toString()
            browser.executeDevToolsMethod("DOM.querySelector", queryParams) { qOk, qJson ->
                val mediaNodeId = if (qOk && !qJson.isNullOrBlank()) {
                    runCatching { JSONObject(qJson).optInt("nodeId", 0) }.getOrDefault(0)
                } else {
                    0
                }
                if (mediaNodeId > 0) {
                    val focusParams = JSONObject().put("nodeId", mediaNodeId).toString()
                    browser.executeDevToolsMethod("DOM.focus", focusParams) { _, _ ->
                        // Native Chromium HTML5 media controls step forward on ArrowRight (VK_RIGHT=39).
                        dispatchCdpKeyTap(browser, "ArrowRight", "ArrowRight", 39)
                    }
                } else {
                    dispatchCdpKeyTap(browser, "MediaTrackNext", "MediaTrackNext", 176)
                }
            }
        }
    }

    /**
     * Executes a smooth vertical scroll of 75% viewport height via zero-JS CDP
     * (`Page.getLayoutMetrics` + `Input.synthesizeScrollGesture`, with `sendMouseWheel`
     * fallback), wrapping smoothly back to the top when already at the document bottom.
     */
    fun scrollActiveViewportFromPip(
        viewportFraction: Double = 0.75,
        tabId: String? = _activeTabId.value,
        onUpdated: () -> Unit = {},
    ) {
        val managed = tabId?.let { sessions[it] } ?: return
        val browser = managed.browser ?: return
        browser.executeDevToolsMethod("Page.getLayoutMetrics", "{}") { ok, json ->
            if (!ok || json.isNullOrBlank()) {
                mainHandler.post {
                    val fallbackStep = ((managed.surfaceWrapper?.height ?: 800) * viewportFraction)
                        .roundToInt()
                        .coerceAtLeast(160)
                    runCatching { browser.sendMouseWheel(200, 300, 0, -fallbackStep) }
                    onUpdated()
                }
                return@executeDevToolsMethod
            }

            val parsed = runCatching {
                val root = JSONObject(json)
                val viewport = root.optJSONObject("cssVisualViewport")
                    ?: root.getJSONObject("visualViewport")
                val content = root.optJSONObject("cssContentSize")
                    ?: root.getJSONObject("contentSize")
                val pageY = viewport.optDouble("pageY", 0.0)
                val clientW = viewport.optDouble("clientWidth", 360.0).coerceAtLeast(120.0)
                val clientH = viewport.optDouble("clientHeight", 640.0).coerceAtLeast(120.0)
                val scrollH = content.optDouble("height", clientH).coerceAtLeast(clientH)
                QuadMetrics(pageY, clientW, clientH, scrollH)
            }.getOrNull()

            if (parsed == null) {
                mainHandler.post { onUpdated() }
                return@executeDevToolsMethod
            }

            val wasAtBottom = managed.state.atDocumentBottom ||
                (parsed.pageY + parsed.clientHeight >= parsed.scrollHeight - 4.0)

            val centerX = (parsed.clientWidth * 0.5).roundToInt()
            val centerY = (parsed.clientHeight * 0.5).roundToInt()

            if (wasAtBottom && parsed.pageY > 2.0) {
                // Smoothly wrap back to top of document.
                val scrollUpDistance = parsed.pageY.roundToInt().coerceAtLeast(1)
                val gestureParams = JSONObject()
                    .put("x", centerX)
                    .put("y", centerY)
                    .put("yDistance", scrollUpDistance)
                    .put("speed", 2400)
                    .toString()
                browser.executeDevToolsMethod("Input.synthesizeScrollGesture", gestureParams) { gOk, _ ->
                    if (!gOk) {
                        mainHandler.post {
                            runCatching { browser.sendMouseWheel(centerX, centerY, 0, scrollUpDistance) }
                        }
                    }
                    mainHandler.post {
                        updateTabState(managed.id) {
                            it.copy(atDocumentBottom = false, latestScrollY = 0)
                        }
                        cdpController.refreshViewportBounds(managed.id, browser)
                        onUpdated()
                    }
                }
            } else {
                val stepPx = (parsed.clientHeight * viewportFraction).roundToInt().coerceAtLeast(120)
                val nextBottom = (parsed.pageY + stepPx + parsed.clientHeight) >= (parsed.scrollHeight - 4.0)
                val nextScrollY = (parsed.pageY + stepPx).roundToInt().coerceAtLeast(0)
                val gestureParams = JSONObject()
                    .put("x", centerX)
                    .put("y", centerY)
                    .put("yDistance", -stepPx)
                    .put("speed", 1400)
                    .toString()
                browser.executeDevToolsMethod("Input.synthesizeScrollGesture", gestureParams) { gOk, _ ->
                    if (!gOk) {
                        mainHandler.post {
                            runCatching { browser.sendMouseWheel(centerX, centerY, 0, -stepPx) }
                        }
                    }
                    mainHandler.post {
                        updateTabState(managed.id) {
                            it.copy(
                                atDocumentBottom = nextBottom,
                                latestScrollY = nextScrollY,
                            )
                        }
                        cdpController.refreshViewportBounds(managed.id, browser)
                        onUpdated()
                    }
                }
            }
        }
    }

    /**
     * Restores vertical scroll position ([scrollY]) via zero-JS CDP when resuming
     * a tab from Android 17 Cross-Device Handoff.
     */
    fun scrollToVerticalOffset(
        scrollY: Int,
        tabId: String? = _activeTabId.value,
    ) {
        if (scrollY <= 0) return
        val managed = tabId?.let { sessions[it] } ?: return
        val browser = managed.browser
        if (browser == null) {
            updateTabState(managed.id) { it.copy(latestScrollY = scrollY) }
            return
        }
        browser.executeDevToolsMethod("Page.getLayoutMetrics", "{}") { ok, json ->
            val currentY = if (ok && !json.isNullOrBlank()) {
                runCatching {
                    val root = JSONObject(json)
                    val viewport = root.optJSONObject("cssVisualViewport")
                        ?: root.getJSONObject("visualViewport")
                    viewport.optDouble("pageY", 0.0)
                }.getOrDefault(0.0)
            } else {
                0.0
            }
            val delta = scrollY - currentY.roundToInt()
            if (abs(delta) > 4) {
                val gestureParams = JSONObject()
                    .put("x", 180)
                    .put("y", 320)
                    .put("yDistance", -delta)
                    .put("speed", 2400)
                    .toString()
                browser.executeDevToolsMethod("Input.synthesizeScrollGesture", gestureParams) { gOk, _ ->
                    if (!gOk) {
                        mainHandler.post {
                            runCatching { browser.sendMouseWheel(180, 320, 0, -delta) }
                        }
                    }
                    mainHandler.post {
                        updateTabState(managed.id) { it.copy(latestScrollY = scrollY) }
                        cdpController.refreshViewportBounds(managed.id, browser)
                    }
                }
            } else {
                mainHandler.post {
                    updateTabState(managed.id) { it.copy(latestScrollY = scrollY) }
                }
            }
        }
    }

    /**
     * Requests Android 17 relative pointer capture (`POINTER_CAPTURE_MODE_RELATIVE`)
     * on the active tab's surface so foldables/tablets with trackpads deliver relative
     * pointer deltas rather than legacy absolute finger coordinates.
     */
    fun requestPointerLockCapture(tabId: String? = _activeTabId.value): Boolean {
        val wrapper = tabId?.let { sessions[it]?.surfaceWrapper } ?: return false
        wrapper.virtualPointer.reset()
        return TouchpadPointerBridge.requestRelativePointerCapture(wrapper)
    }

    fun releasePointerLockCapture(tabId: String? = _activeTabId.value) {
        val wrapper = tabId?.let { sessions[it]?.surfaceWrapper } ?: return
        wrapper.virtualPointer.reset()
        TouchpadPointerBridge.releasePointerCapture(wrapper)
    }

    /**
     * Proactively pauses non-active background tabs and reaps idle renderers when
     * API 36 `SystemHealthManager` reports low CPU or GPU thermal headroom.
     */
    fun throttleBackgroundTabs(): Int {
        val activeId = _activeTabId.value
        var throttledCount = 0
        sessions.forEach { (id, managed) ->
            if (id != activeId) {
                throttledCount++
                managed.browser?.let { browser ->
                    runCatching {
                        browser.setPageVisible(false)
                        browser.onPause()
                    }
                }
            }
        }
        if (throttledCount > 0 && TrapApplication.isEngineReady) {
            runCatching { CefriumBrowser.reapUnusedRenderers() }
        }
        return throttledCount
    }

    /**
     * Cycles forward through open tabs from the PiP overlay, prioritizing the next
     * audible/WebRTC tab when [preferAudible] is true.
     */
    fun cycleTabFromPip(preferAudible: Boolean = false): String? {
        val orderedIds = sessions.keys.toList()
        if (orderedIds.isEmpty()) return null
        if (orderedIds.size == 1) return orderedIds.first()

        val currentId = _activeTabId.value
        val startIdx = orderedIds.indexOf(currentId).let { if (it < 0) 0 else it }

        if (preferAudible) {
            for (offset in 1 until orderedIds.size) {
                val candidateId = orderedIds[(startIdx + offset) % orderedIds.size]
                val candidateState = sessions[candidateId]?.state ?: continue
                if (candidateState.isPlayingAudio ||
                    candidateState.hasActiveVideo ||
                    candidateState.hasWebRtcAudio
                ) {
                    selectTabInternal(candidateId)
                    return candidateId
                }
            }
        }

        val nextId = orderedIds[(startIdx + 1) % orderedIds.size]
        selectTabInternal(nextId)
        return nextId
    }

    /**
     * Cleanly pauses and tears down active HTML5 media playback and WebRTC capture
     * across tabs when the user dismisses the PiP `"X"` close button so no orphaned
     * background audio lingers.
     */
    fun pauseAndStopActiveMediaForPipClose() {
        sessions.forEach { (id, managed) ->
            managed.browser?.let { browser ->
                runCatching { browser.suspendMedia() }
                runCatching { browser.stopAllMedia() }
                runCatching { browser.stopMediaSession() }
                runCatching { browser.exitFullscreen() }
            }
            managed.state = managed.state.copy(
                isPlayingAudio = false,
                isMediaPausedByUser = false,
                isFullscreenVideo = false,
                hasWebRtcVideo = false,
                hasWebRtcAudio = false,
            )
        }
        publishTabsSnapshot()
    }

    private data class QuadMetrics(
        val pageY: Double,
        val clientWidth: Double,
        val clientHeight: Double,
        val scrollHeight: Double,
    )

    private fun dispatchCdpKeyTap(
        browser: CefriumBrowser,
        key: String,
        code: String,
        windowsVirtualKeyCode: Int,
    ) {
        val down = JSONObject()
            .put("type", "rawKeyDown")
            .put("key", key)
            .put("code", code)
            .put("windowsVirtualKeyCode", windowsVirtualKeyCode)
            .put("nativeVirtualKeyCode", windowsVirtualKeyCode)
            .toString()
        val up = JSONObject()
            .put("type", "keyUp")
            .put("key", key)
            .put("code", code)
            .put("windowsVirtualKeyCode", windowsVirtualKeyCode)
            .put("nativeVirtualKeyCode", windowsVirtualKeyCode)
            .toString()
        browser.executeDevToolsMethod("Input.dispatchKeyEvent", down) { _, _ ->
            browser.executeDevToolsMethod("Input.dispatchKeyEvent", up) { _, _ -> }
        }
    }

    fun destroyAll() {
        val all = sessions.values.toList()
        sessions.clear()
        _activeTabId.value = null
        _podsRetracted.value = false
        cdpController.clearAll()
        backStackManager.clearAll()
        mediaDisplayNormalizer.releaseAll()
        all.forEach { teardownBrowser(it) }
        _tabs.value = emptyList()
    }

    private fun configureHardenedBrowser(tabId: String, browser: CefriumBrowser) {
        // Hard zero-JavaScript policy; keep pinchToZoomEnabled at its default (true)
        // because CefriumBrowser.setPinchToZoomEnabled(false) injects JS.
        browser.setJavaScriptEnabled(false)
        browser.setMediaPlaybackRequiresUserGesture(true)
        browser.setAdBlockEnabled(false)

        // Deny all sensitive Web permissions (geolocation, camera, mic, notifications, clipboard, NFC).
        browser.setPermissionHandler { _, _, callback ->
            callback.respond(false)
        }

        // Route HTML `<input type="color">` / EyeDropper requests to Android 17's
        // zero-permission system `ACTION_OPEN_EYE_DROPPER` picker when available.
        browser.setPickerHandler(object : CefriumPickerHandler {
            override fun onShowColor(
                initialColor: Int,
                suggestions: IntArray?,
                callback: CefriumPickerHandler.ColorCallback,
            ): Boolean = onColorPickerRequested(initialColor, callback)
        })

        // Deny hardware chooser dialogs (Bluetooth, USB, Serial).
        browser.setDeviceChooserHandler(object : CefriumBrowser.DeviceChooserHandler {
            override fun onShowDeviceChooser(session: CefriumBrowser.DeviceChooserSession) {
                session.cancel()
            }

            override fun onDeviceAdded(
                session: CefriumBrowser.DeviceChooserSession,
                device: CefriumBrowser.ChooserDevice,
            ) = Unit

            override fun onDeviceChooserClosed(session: CefriumBrowser.DeviceChooserSession) = Unit
        })

        // Network-thread request policy enforcement.
        browser.setRequestInterceptor(object : CefriumBrowser.RequestInterceptor {
            override fun shouldBlockRequest(
                url: String,
                method: String,
                isMainFrame: Boolean,
            ): Boolean = shouldBlockUrlInternal(
                tabId = tabId,
                url = url,
                isMainFrame = isMainFrame,
                resourceType = -1,
                topLevelUrl = "",
            )

            override fun shouldBlockRequest(request: CefriumBrowser.ResourceRequest): Boolean {
                if (request.resourceType == CefriumBrowser.ResourceRequest.TYPE_PING ||
                    request.resourceType == CefriumBrowser.ResourceRequest.TYPE_CSP_REPORT ||
                    request.resourceType == CefriumBrowser.ResourceRequest.TYPE_SCRIPT
                ) {
                    return true
                }
                return shouldBlockUrlInternal(
                    tabId = tabId,
                    url = request.url ?: "",
                    isMainFrame = request.isMainFrame,
                    resourceType = request.resourceType,
                    topLevelUrl = request.topLevelUrl ?: request.documentUrl ?: "",
                )
            }
        })

        // Block unsolicited popups; route explicit user-gesture links into a managed tab if allowed.
        browser.setPopupHandler { targetUrl, userGesture ->
            if (userGesture && !targetUrl.isNullOrBlank()) {
                val normalizedInput = normalizeUserAddressInput(targetUrl)
                val decision = evaluateUrlPolicy(normalizedInput, _hiddenServiceOnly.value)
                if (decision.allowed) {
                    mainHandler.post { createTab(decision.normalizedUrl, select = true) }
                } else if (decision.requiresClearnetConsent) {
                    val normalized = decision.normalizedUrl.ifBlank { normalizedInput }
                    val host = decision.host.ifBlank {
                        runCatching { Uri.parse(normalized).host.orEmpty() }.getOrDefault("")
                    }.trim().trimEnd('.').lowercase()
                    val suggested = decision.suggestedPattern.ifBlank {
                        OvNative.suggestGlob(host)
                    }
                    mainHandler.post { onClearnetConsentRequired(host, normalized, suggested) }
                } else {
                    mainHandler.post { onUrlBlocked(tabId, targetUrl, decision.reason) }
                }
            }
            true
        }

        // Strip clearnet/external-app intents (`WEB_SEARCH`, `SHARE`, `PROCESS_TEXT`) from text selection.
        browser.setTextSelectionActionModeCallback(createHardenedSelectionCallback(tabId, browser))

        // Long-press context menu callback (`menuItems` is newline-separated "id|label",
        // `url` is the link or media URL). Immediately cancel Chromium's native pending
        // `CefRunContextMenuCallback` (`contextMenuCommand(-1)`) on the UI thread and
        // surface Trap's native Compose context sheet instead.
        browser.setOnContextMenuListener { menuItems, url ->
            mainHandler.post {
                runCatching { browser.contextMenuCommand(-1) }
                val cleanUrl = url?.trim()?.takeIf { it.isNotEmpty() }
                val hasMediaCommand = menuItems.orEmpty()
                    .lineSequence()
                    .map { it.substringAfter('|', "").lowercase() }
                    .any { label ->
                        label.contains("image") ||
                            label.contains("video") ||
                            label.contains("audio") ||
                            label.contains("media")
                    }
                val selected = runCatching { browser.selectedText }.getOrNull()?.takeIf { it.isNotBlank() }
                val classification = selected?.let { classifySelectedText(it) }?.takeIf { it.isPgp }
                onContextMenu(
                    ContextMenuTarget(
                        tabId = tabId,
                        linkUrl = cleanUrl,
                        mediaUrl = if (hasMediaCommand) cleanUrl else null,
                        selectedText = selected,
                        textClassification = classification,
                    ),
                )
            }
        }

        browser.setDisplayHandler(object : CefriumDisplayHandler {
            override fun onLoadingProgress(progress: Double) {
                val pct = (progress * 100.0).roundToInt().coerceIn(5, 100)
                mainHandler.post {
                    updateTabState(tabId) { it.copy(loadProgress = pct) }
                }
            }

            override fun onMediaAccessChange(hasVideo: Boolean, hasAudio: Boolean) {
                mainHandler.post {
                    updateTabState(tabId) {
                        it.copy(
                            hasWebRtcVideo = hasVideo,
                            hasWebRtcAudio = hasAudio,
                        )
                    }
                }
            }
        })

        browser.setAudioHandler(object : CefriumAudioHandler {
            override fun onAudioStreamStarted(channels: Int, sampleRate: Int) {
                mainHandler.post {
                    mediaDisplayNormalizer.ensureAudioSessionNormalized(context)
                    updateTabState(tabId) {
                        it.copy(
                            isPlayingAudio = true,
                            isMediaPausedByUser = false,
                        )
                    }
                    probePrimaryVideoBounds(tabId, browser)
                }
            }

            override fun onAudioStreamStopped() {
                mainHandler.post {
                    val stillPlaying = runCatching { browser.isPlayingAudio }.getOrDefault(false)
                    updateTabState(tabId) { it.copy(isPlayingAudio = stillPlaying) }
                }
            }

            override fun onAudioStreamError(message: String?) {
                mainHandler.post {
                    val stillPlaying = runCatching { browser.isPlayingAudio }.getOrDefault(false)
                    updateTabState(tabId) { it.copy(isPlayingAudio = stillPlaying) }
                }
            }
        })

        browser.setLoadHandler(object : CefriumLoadHandler {
            override fun onLoadError(errorCode: Int, errorText: String?, failedUrl: String?) {
                // Ignore ERR_ABORTED (-3), which occurs during normal stop/redirect.
                if (errorCode == -3) return
                if (!failedUrl.isNullOrBlank() && failedUrl == lastConsentInterceptedUrl) {
                    lastConsentInterceptedUrl = null
                    mainHandler.post {
                        updateTabState(tabId) {
                            it.copy(isLoading = false, loadProgress = 100)
                        }
                    }
                    return
                }
                mainHandler.post {
                    sessions[tabId]?.hadErrorInCurrentLoad = true
                    updateTabState(tabId) {
                        it.copy(isLoading = false, loadProgress = 100)
                    }
                    if (tabId == _activeTabId.value &&
                        !failedUrl.isNullOrBlank() &&
                        failedUrl != "about:blank"
                    ) {
                        onSecurityNotice(
                            "Navigation failed (${errorText ?: "code $errorCode"}): $failedUrl",
                        )
                    }
                }
            }
        })

        browser.setRequestHandler(object : CefriumRequestHandler {
            override fun onDocumentAvailable() {
                mainHandler.post {
                    val managed = sessions[tabId] ?: return@post
                    val current = managed.browser ?: return@post
                    val effectiveUrl = managed.overrideDisplayUrl ?: current.url.orEmpty()
                    cdpController.attachAndScan(tabId, current, effectiveUrl)
                }
            }
        })

        browser.setCallback(object : CefriumBrowser.Callback() {
            override fun onUrlChanged(url: String?) {
                val rawSafeUrl = url?.trim().orEmpty().ifEmpty { "about:blank" }
                mainHandler.post {
                    val managed = sessions[tabId]
                    val overrideUrl = managed?.overrideDisplayUrl
                    val pendingUrl = managed?.pendingNavigationUrl
                    val safeUrl = when {
                        rawSafeUrl == "about:blank" && !overrideUrl.isNullOrBlank() -> overrideUrl
                        rawSafeUrl == "about:blank" && !pendingUrl.isNullOrBlank() -> pendingUrl
                        rawSafeUrl.startsWith("chrome-error://") ->
                            pendingUrl ?: managed?.state?.url?.takeIf { it.isNotBlank() } ?: "about:blank"
                        else -> {
                            if (rawSafeUrl != "about:blank") {
                                managed?.pendingNavigationUrl = null
                            }
                            rawSafeUrl
                        }
                    }
                    val isOnion = isOnionAddress(safeUrl)
                    var activeUrlChanged = false
                    updateTabState(tabId) { state ->
                        val urlChanged = state.url != safeUrl
                        activeUrlChanged = urlChanged || isBlankOrStartUrl(safeUrl)
                        state.copy(
                            url = safeUrl,
                            title = if (safeUrl == "about:blank") "New Tab" else state.title.ifBlank { displayTitleForUrl(safeUrl) },
                            canGoBack = browser.canGoBack(),
                            canGoForward = canNavigateForward(browser),
                            isOnion = isOnion,
                            isMediaPausedByUser = if (urlChanged) false else state.isMediaPausedByUser,
                            atDocumentBottom = if (urlChanged) false else state.atDocumentBottom,
                            latestScrollY = if (urlChanged) 0 else state.latestScrollY,
                            isFullscreenVideo = if (safeUrl == "about:blank") false else state.isFullscreenVideo,
                            videoWidth = if (safeUrl == "about:blank") 0 else state.videoWidth,
                            videoHeight = if (safeUrl == "about:blank") 0 else state.videoHeight,
                        )
                    }
                    if (tabId == _activeTabId.value && activeUrlChanged) {
                        _podsRetracted.value = false
                    }
                }
            }

            override fun onTitleChanged(title: String?) {
                val cleanTitle = title?.trim().orEmpty()
                if (cleanTitle.isEmpty()) return
                mainHandler.post {
                    updateTabState(tabId) { it.copy(title = cleanTitle) }
                }
            }

            override fun onFullscreenChanged(isFullscreen: Boolean) {
                mainHandler.post {
                    updateTabState(tabId) { it.copy(isFullscreenVideo = isFullscreen) }
                    probePrimaryVideoBounds(tabId, browser)
                }
            }

            override fun onLoadingStateChanged(
                isLoading: Boolean,
                canGoBack: Boolean,
                canGoForward: Boolean,
            ) {
                mainHandler.post {
                    if (isLoading) {
                        sessions[tabId]?.hadErrorInCurrentLoad = false
                    }
                    val playingAudio = runCatching { browser.isPlayingAudio }.getOrDefault(false)
                    updateTabState(tabId) {
                        it.copy(
                            isLoading = isLoading,
                            loadProgress = if (isLoading) it.loadProgress.coerceAtLeast(15) else 100,
                            canGoBack = canGoBack,
                            canGoForward = canGoForward,
                            isPlayingAudio = playingAudio || it.isPlayingAudio,
                        )
                    }
                    if (!isLoading) {
                        val currentUrl = sessions[tabId]?.overrideDisplayUrl ?: browser.url.orEmpty()
                        if (sessions[tabId]?.hadErrorInCurrentLoad == false &&
                            currentUrl.isNotBlank() &&
                            currentUrl != "about:blank" &&
                            !currentUrl.startsWith("chrome-error://")
                        ) {
                            onNavigationRecovered(tabId)
                        }
                        cdpController.attachAndScan(tabId, browser, currentUrl)
                        probePrimaryVideoBounds(tabId, browser)
                        if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                            mainHandler.postDelayed({
                                val managed = sessions[tabId] ?: return@postDelayed
                                val liveBrowser = managed.browser ?: return@postDelayed
                                backStackManager.capture(
                                    tabId = tabId,
                                    browser = liveBrowser,
                                    domHtml = managed.latestDomHtml,
                                    nodes = cdpController.nodesForTab(tabId),
                                )
                            }, 180L)
                        }
                    }
                }
            }
        })

        cdpController.attachAndScan(tabId, browser, "about:blank")
    }

    private fun createHardenedSelectionCallback(
        tabId: String,
        browser: CefriumBrowser,
    ): ActionMode.Callback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            sanitizeSelectionMenu(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            sanitizeSelectionMenu(menu)
            menu.removeItem(MENU_ID_PGP_ACTION)
            val selected = runCatching { browser.selectedText }.getOrNull()?.trim().orEmpty()
            if (selected.isNotEmpty()) {
                val classification = classifySelectedText(selected)
                if (classification.isPgp && classification.actionLabel.isNotBlank()) {
                    menu.add(Menu.NONE, MENU_ID_PGP_ACTION, Menu.FIRST, classification.actionLabel)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
            }
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId == MENU_ID_PGP_ACTION) {
                val selected = runCatching { browser.selectedText }.getOrNull()?.trim().orEmpty()
                if (selected.isNotEmpty()) {
                    val classification = classifySelectedText(selected)
                    if (classification.isPgp) {
                        onSelectionPgpAction(SelectionPgpAction(tabId, selected, classification))
                    }
                }
                mode.finish()
                return true
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }

    private fun sanitizeSelectionMenu(menu: Menu) {
        val pkg = context.packageName
        val res = context.resources
        val webSearchId = res.getIdentifier("select_action_menu_web_search", "id", pkg)
        val shareId = res.getIdentifier("select_action_menu_share", "id", pkg)
        val processTextGroupId = res.getIdentifier("select_action_menu_text_processing_items", "id", pkg)

        if (webSearchId != 0) menu.removeItem(webSearchId)
        if (shareId != 0) menu.removeItem(shareId)
        menu.removeItem(android.R.id.shareText)
        if (processTextGroupId != 0) menu.removeGroup(processTextGroupId)

        val idsToRemove = ArrayList<Int>()
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val title = item.title?.toString()?.lowercase().orEmpty()
            if (item.intent != null ||
                title.contains("web search") ||
                title == "share"
            ) {
                idsToRemove.add(item.itemId)
            }
        }
        idsToRemove.forEach { menu.removeItem(it) }
    }

    private fun shouldBlockUrlInternal(
        tabId: String,
        url: String,
        isMainFrame: Boolean,
        resourceType: Int,
        topLevelUrl: String = "",
    ): Boolean {
        if (url.isBlank()) return true
        if (url == "about:blank") return false

        // In CEF, `request.isMainFrame` is true for ANY resource (images, favicons, CSS)
        // originating inside the main frame; only `TYPE_MAIN_FRAME` (or legacy `-1`) is
        // an actual top-level document navigation!
        val isTopLevelNavigation = isMainFrame && (
            resourceType < 0 || resourceType == CefriumBrowser.ResourceRequest.TYPE_MAIN_FRAME
            )
        val pageUrl = topLevelUrl.ifBlank { sessions[tabId]?.state?.url.orEmpty() }
        val lanDecision = lanAccessController.evaluateRequest(
            pageUrl = pageUrl,
            targetUrl = url,
            isMainFrame = isTopLevelNavigation,
            hiddenServiceOnly = _hiddenServiceOnly.value,
            hasOsPermission = lanAccessController.hasLocalNetworkPermission(context),
        )
        when (lanDecision) {
            LanAccessController.LanDecision.ALLOW_LAN -> return false

            LanAccessController.LanDecision.PROMPT_OS_PERMISSION -> {
                if (isTopLevelNavigation) {
                    lastConsentInterceptedUrl = url
                    mainHandler.post { onLocalNetworkPermissionRequired(url) }
                }
                return true
            }

            LanAccessController.LanDecision.BLOCKED_CROSS_ORIGIN_LAN_PROBE,
            LanAccessController.LanDecision.BLOCKED_HIDDEN_SERVICE_ONLY,
            -> {
                if (isTopLevelNavigation || resourceType == CefriumBrowser.ResourceRequest.TYPE_SUB_FRAME) {
                    val reason = if (lanDecision == LanAccessController.LanDecision.BLOCKED_HIDDEN_SERVICE_ONLY) {
                        "Local network request blocked in Hidden Service Only mode"
                    } else {
                        "Blocked cross-origin local network probe"
                    }
                    mainHandler.post {
                        updateTabState(tabId) {
                            it.copy(
                                lastBlockedUrl = url,
                                lastBlockReason = reason,
                            )
                        }
                        onUrlBlocked(tabId, url, reason)
                    }
                }
                return true
            }

            LanAccessController.LanDecision.NOT_LAN -> Unit
        }

        // Fail closed before Arti binds its local SOCKS5 proxy.
        if (_activeSocksAddr.value.isBlank() && TrapApplication.isEngineReady) {
            if (isTopLevelNavigation) {
                mainHandler.post {
                    onUrlBlocked(tabId, url, "Blocked until Tor SOCKS5 proxy is bound")
                }
            }
            return true
        }

        val decision = evaluateUrlPolicy(url, _hiddenServiceOnly.value)
        if (!decision.allowed) {
            if (decision.requiresClearnetConsent && !_hiddenServiceOnly.value) {
                if (isTopLevelNavigation) {
                    lastConsentInterceptedUrl = url
                    val normalized = decision.normalizedUrl.ifBlank { url }
                    val host = decision.host.ifBlank {
                        runCatching { Uri.parse(normalized).host.orEmpty() }.getOrDefault("")
                    }.trim().trimEnd('.').lowercase()
                    val suggested = decision.suggestedPattern.ifBlank {
                        OvNative.suggestGlob(host)
                    }
                    mainHandler.post {
                        onClearnetConsentRequired(host, normalized, suggested)
                    }
                    return true
                }
                // Enforce strict .onion subresource realm isolation: never allow a .onion
                // document to pull unconsented clearnet subresources (e.g. external favicons).
                val pendingNav = sessions[tabId]?.pendingNavigationUrl.orEmpty()
                val sessionUrl = sessions[tabId]?.state?.url.orEmpty()
                if (isOnionAddress(topLevelUrl) || isOnionAddress(sessionUrl) || isOnionAddress(pendingNav)) {
                    return true
                }
                // Non-main-frame subresources on clearnet pages flow to Trap's local
                // Socks5Server, which drops Chromium Google background noise and holds
                // legitimate clearnet subresources in `pendingClearnetHost` for live consent.
                return false
            }
            if (isTopLevelNavigation || resourceType == CefriumBrowser.ResourceRequest.TYPE_SUB_FRAME) {
                mainHandler.post {
                    updateTabState(tabId) {
                        it.copy(
                            lastBlockedUrl = url,
                            lastBlockReason = decision.reason,
                        )
                    }
                    onUrlBlocked(tabId, url, decision.reason)
                }
            }
            return true
        }
        return false
    }

    private fun classifySelectedText(text: String): OvNative.TextClassification {
        if (!OvNative.isLoaded) {
            return OvNative.TextClassification()
        }
        val json = runCatching { OvNative.nativeClassifyText(text) }.getOrDefault("")
        return OvNative.parseTextClassification(json)
    }

    private fun normalizeUserAddressInput(rawInput: String): String =
        normalizeAddressOrSearch(rawInput)

    private fun evaluateUrlPolicy(
        url: String,
        hiddenServiceOnly: Boolean,
    ): OvNative.UrlPolicyResult {
        val trimmed = url.trim()
        if (trimmed.equals("about:blank", ignoreCase = true)) {
            return OvNative.UrlPolicyResult(
                allowed = true,
                normalizedUrl = "about:blank",
                isOnion = false,
                reason = "",
            )
        }
        if (!OvNative.isLoaded) {
            return OvNative.UrlPolicyResult(
                allowed = false,
                normalizedUrl = trimmed,
                isOnion = false,
                reason = "Native Tor/policy engine not loaded",
            )
        }
        val json = runCatching {
            OvNative.nativeCheckUrlPolicy(trimmed, hiddenServiceOnly)
        }.getOrDefault("")
        return OvNative.parseUrlPolicy(json)
    }

    private fun isOnionAddress(url: String): Boolean =
        evaluateUrlPolicy(url, hiddenServiceOnly = false).isOnion

    private fun canNavigateForward(browser: CefriumBrowser): Boolean {
        val entries = runCatching { browser.navigationEntries }.getOrNull() ?: return false
        val currentIdx = entries.indexOfFirst { it.current }
        return currentIdx >= 0 && currentIdx < entries.lastIndex
    }

    private fun selectTabInternal(tabId: String) {
        _activeTabId.value = tabId
        _podsRetracted.value = false
        sessions.forEach { (id, managed) ->
            val visible = id == tabId
            managed.browser?.let { browser ->
                runCatching {
                    browser.setPageVisible(visible)
                    if (visible) browser.onResume() else browser.onPause()
                }
            }
        }
        publishTabsSnapshot()
    }

    private fun updateTabState(
        tabId: String,
        transform: (TabState) -> TabState,
    ) {
        val managed = sessions[tabId] ?: return
        managed.state = transform(managed.state)
        publishTabsSnapshot()
    }

    private fun publishTabsSnapshot() {
        _tabs.value = sessions.values.map { it.state }
    }

    private fun teardownBrowser(managed: ManagedTab) {
        val wrapper = managed.surfaceWrapper
        val browser = managed.browser
        runCatching { wrapper?.removeAllViews() }
        runCatching { browser?.stopLoading() }
        runCatching { browser?.close() }
    }

    /**
     * Queries `<video>` element dimensions through 100% zero-JavaScript CDP
     * (`DOM.getDocument` -> `DOM.querySelector("video")` -> `DOM.getBoxModel`)
     * so dynamic PiP aspect ratios match live HTML5 video bounds without script execution.
     */
    private fun probePrimaryVideoBounds(tabId: String, browser: CefriumBrowser) {
        browser.executeDevToolsMethod("DOM.getDocument", """{"depth":1}""") { docOk, docJson ->
            if (!docOk || docJson.isNullOrBlank()) return@executeDevToolsMethod
            val rootNodeId = runCatching {
                JSONObject(docJson).getJSONObject("root").optInt("nodeId", 0)
            }.getOrDefault(0)
            if (rootNodeId <= 0) return@executeDevToolsMethod

            val queryParams = JSONObject()
                .put("nodeId", rootNodeId)
                .put("selector", "video")
                .toString()
            browser.executeDevToolsMethod("DOM.querySelector", queryParams) { qOk, qJson ->
                val videoNodeId = if (qOk && !qJson.isNullOrBlank()) {
                    runCatching { JSONObject(qJson).optInt("nodeId", 0) }.getOrDefault(0)
                } else {
                    0
                }
                if (videoNodeId <= 0) {
                    mainHandler.post {
                        updateTabState(tabId) {
                            if (it.videoWidth == 0 && it.videoHeight == 0) {
                                it
                            } else {
                                it.copy(videoWidth = 0, videoHeight = 0)
                            }
                        }
                    }
                    return@executeDevToolsMethod
                }

                val boxParams = JSONObject().put("nodeId", videoNodeId).toString()
                browser.executeDevToolsMethod("DOM.getBoxModel", boxParams) { boxOk, boxJson ->
                    val dims = if (boxOk && !boxJson.isNullOrBlank()) {
                        runCatching {
                            val model = JSONObject(boxJson).getJSONObject("model")
                            val w = model.optInt("width", 0)
                            val h = model.optInt("height", 0)
                            w to h
                        }.getOrNull()
                    } else {
                        null
                    }
                    mainHandler.post {
                        val w = dims?.first?.coerceAtLeast(0) ?: 0
                        val h = dims?.second?.coerceAtLeast(0) ?: 0
                        updateTabState(tabId) { it.copy(videoWidth = w, videoHeight = h) }
                    }
                }
            }
        }
    }

    private fun displayTitleForUrl(url: String): String {
        if (url.isBlank() || url == "about:blank") return "New Tab"
        return runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
    }

    private tailrec fun resolveActivity(ctx: Context?): Activity? = when (ctx) {
        null -> null
        is Activity -> ctx
        is ContextWrapper -> resolveActivity(ctx.baseContext)
        else -> null
    }
}
