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
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumDisplayHandler
import com.cefrium.CefriumLoadHandler
import com.cefrium.CefriumRequestHandler
import java.util.UUID
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
    initialHiddenServiceOnly: Boolean = true,
    private val onContextMenu: (ContextMenuTarget) -> Unit = {},
    private val onSelectionPgpAction: (SelectionPgpAction) -> Unit = {},
    private val onUrlBlocked: (tabId: String, blockedUrl: String, reason: String) -> Unit = { _, _, _ -> },
    private val onSecurityNotice: (String) -> Unit = {},
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
        val lastBlockedUrl: String? = null,
        val lastBlockReason: String? = null,
    )

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
    )

    private inner class TouchGateFrameLayout(
        ctx: Context,
        private val tabId: String,
    ) : FrameLayout(ctx) {
        private val refreshRunnable = Runnable {
            val browser = sessions[tabId]?.browser ?: return@Runnable
            cdpController.refreshViewportBounds(tabId, browser)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (touchSuppressed) {
                return true
            }
            val handled = super.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                removeCallbacks(refreshRunnable)
                postDelayed(refreshRunnable, 120L)
            }
            return handled
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (touchSuppressed) {
                return true
            }
            return super.onInterceptTouchEvent(ev)
        }
    }

    private companion object {
        const val TAG = "TabSessionManager"
        const val MENU_ID_PGP_ACTION = 0x54524150
        const val MIN_ZOOM_PERCENT = 50
        const val MAX_ZOOM_PERCENT = 300
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

    @Volatile
    private var touchSuppressed: Boolean = false

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

    fun createTab(initialUrl: String = "about:blank", select: Boolean = true): String {
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
                val cefSurface = browser.surfaceContainer
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
            isLoading = false,
            loadProgress = 100,
            canGoBack = false,
            canGoForward = false,
            zoomPercent = 100,
            isOnion = initialDecision.isOnion,
        )

        val managed = ManagedTab(
            id = tabId,
            browser = browser,
            surfaceWrapper = wrapper,
            state = initialState,
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
        } else {
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
    ): OvNative.UrlPolicyDecision {
        val targetTabId = tabId ?: ensureInitialTab()
        val managed = sessions[targetTabId] ?: return OvNative.UrlPolicyDecision(
            allowed = false,
            reason = "Tab not found",
            isOnion = false,
            normalizedUrl = "",
        )

        val candidate = normalizeUserAddressInput(rawInput)
        val decision = evaluateUrlPolicy(candidate, _hiddenServiceOnly.value)
        if (!decision.allowed) {
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
            return OvNative.UrlPolicyDecision(
                allowed = false,
                reason = waitReason,
                isOnion = decision.isOnion,
                normalizedUrl = normalized,
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
        managed.browser?.reload()
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

    fun destroyAll() {
        val all = sessions.values.toList()
        sessions.clear()
        _activeTabId.value = null
        cdpController.clearAll()
        backStackManager.clearAll()
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
            ): Boolean = shouldBlockUrlInternal(tabId, url, isMainFrame, resourceType = -1)

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
                )
            }
        })

        // Block unsolicited popups; route explicit user-gesture links into a managed tab if allowed.
        browser.setPopupHandler { targetUrl, userGesture ->
            if (userGesture && !targetUrl.isNullOrBlank()) {
                val decision = evaluateUrlPolicy(targetUrl, _hiddenServiceOnly.value)
                if (decision.allowed) {
                    mainHandler.post { createTab(decision.normalizedUrl, select = true) }
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
        })

        browser.setLoadHandler(object : CefriumLoadHandler {
            override fun onLoadError(errorCode: Int, errorText: String?, failedUrl: String?) {
                // Ignore ERR_ABORTED (-3), which occurs during normal stop/redirect.
                if (errorCode == -3) return
                mainHandler.post {
                    updateTabState(tabId) {
                        it.copy(isLoading = false, loadProgress = 100)
                    }
                    if (!failedUrl.isNullOrBlank() && failedUrl != "about:blank") {
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
                    val overrideUrl = sessions[tabId]?.overrideDisplayUrl
                    val safeUrl = if (rawSafeUrl == "about:blank" && !overrideUrl.isNullOrBlank()) {
                        overrideUrl
                    } else {
                        rawSafeUrl
                    }
                    val isOnion = isOnionAddress(safeUrl)
                    updateTabState(tabId) { state ->
                        state.copy(
                            url = safeUrl,
                            title = if (safeUrl == "about:blank") "New Tab" else state.title.ifBlank { displayTitleForUrl(safeUrl) },
                            canGoBack = browser.canGoBack(),
                            canGoForward = canNavigateForward(browser),
                            isOnion = isOnion,
                        )
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

            override fun onLoadingStateChanged(
                isLoading: Boolean,
                canGoBack: Boolean,
                canGoForward: Boolean,
            ) {
                mainHandler.post {
                    updateTabState(tabId) {
                        it.copy(
                            isLoading = isLoading,
                            loadProgress = if (isLoading) it.loadProgress.coerceAtLeast(15) else 100,
                            canGoBack = canGoBack,
                            canGoForward = canGoForward,
                        )
                    }
                    if (!isLoading) {
                        val currentUrl = sessions[tabId]?.overrideDisplayUrl ?: browser.url.orEmpty()
                        cdpController.attachAndScan(tabId, browser, currentUrl)
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
    ): Boolean {
        if (url.isBlank()) return true
        if (url == "about:blank") return false

        // Fail closed before Arti binds its local SOCKS5 proxy.
        if (_activeSocksAddr.value.isBlank() && TrapApplication.isEngineReady) {
            if (isMainFrame) {
                mainHandler.post {
                    onUrlBlocked(tabId, url, "Blocked until Tor SOCKS5 proxy is bound")
                }
            }
            return true
        }

        val decision = evaluateUrlPolicy(url, _hiddenServiceOnly.value)
        if (!decision.allowed) {
            if (isMainFrame || resourceType == CefriumBrowser.ResourceRequest.TYPE_SUB_FRAME) {
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

    private fun normalizeUserAddressInput(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty() || trimmed.equals("about:blank", ignoreCase = true)) {
            return "about:blank"
        }
        if (trimmed.contains("://") || trimmed.startsWith("about:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true) ||
            trimmed.startsWith("file:", ignoreCase = true) ||
            trimmed.startsWith("javascript:", ignoreCase = true)
        ) {
            return trimmed
        }
        // Hidden services default to `http://` over Tor's end-to-end encrypted v3 circuit.
        val hostPart = trimmed.substringBefore('/').substringBefore(':').lowercase()
        val scheme = if (hostPart.endsWith(".onion")) "http://" else "https://"
        return "$scheme$trimmed"
    }

    private fun evaluateUrlPolicy(
        url: String,
        hiddenServiceOnly: Boolean,
    ): OvNative.UrlPolicyDecision {
        val trimmed = url.trim()
        if (trimmed.equals("about:blank", ignoreCase = true)) {
            return OvNative.UrlPolicyDecision(
                allowed = true,
                reason = "",
                isOnion = false,
                normalizedUrl = "about:blank",
            )
        }
        if (!OvNative.isLoaded) {
            return OvNative.UrlPolicyDecision(
                allowed = false,
                reason = "Native Tor/policy engine not loaded",
                isOnion = false,
                normalizedUrl = trimmed,
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
