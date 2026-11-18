package dev.onionvault

import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.cefpack.android.CefpackSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 100% Zero-JavaScript DOM OpenPGP scanner, in-page text searcher, and form-field mutator
 * powered by Cefpack's in-process Chrome DevTools Protocol (`executeDevToolsMethod`).
 *
 * Operates exclusively through Chromium's C++ `InspectorDOMAgent`, `InspectorPageAgent`,
 * and `InspectorInputAgent` (`DOM.getDocument`, `DOM.getOuterHTML`, `DOM.getBoxModel`,
 * `DOM.performSearch`, `DOM.getSearchResults`, `DOM.scrollIntoViewIfNeeded`,
 * `DOM.focus`, `Input.dispatchKeyEvent`, `Input.insertText`) while leaving webpage
 * DOM styling untouched and enforcing zero JavaScript execution on every session.
 */
class CdpDomPgpController(
    private val onNodesDetected: (
        tabId: String,
        url: String,
        domHtml: String,
        nodes: List<DetectedPgpNode>,
    ) -> Unit = { _, _, _, _ -> },
) {

    data class DetectedPgpNode(
        val key: String,
        val nodeId: Int,
        val backendNodeId: Int,
        val tag: String,
        val kind: String,
        val actionLabel: String,
        val text: String,
        val fingerprintHint: String,
        val normalizedLeft: Float,
        val normalizedTop: Float,
        val normalizedRight: Float,
        val normalizedBottom: Float,
        val isSearchLike: Boolean = false,
    )

    data class NormalizedMatchBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    data class TextSearchOutcome(
        val query: String,
        val matchCount: Int,
        val activeIndex: Int,
        val activeBounds: NormalizedMatchBox? = null,
    )

    private data class ActiveSearchSession(
        val query: String,
        val searchId: String,
        val matchCount: Int,
        val activeZeroIndex: Int,
    )

    internal data class ClassifiedNodeEntry(
        val nodeId: Int,
        val backendNodeId: Int,
        val tag: String,
        val kind: String,
        val actionLabel: String,
        val text: String,
        val fingerprintHint: String,
        val isSearchLike: Boolean,
    )

    data class FieldMutationOutcome(
        val ok: Boolean,
        val verified: Boolean,
        val nodeId: Int,
        val backendNodeId: Int,
        val error: String = "",
    )

    private data class ViewportMetrics(
        val pageX: Double,
        val pageY: Double,
        val width: Double,
        val height: Double,
    )

    internal companion object {
        private const val TAG = "CdpDomPgp"
        private val SCRIPT_STYLE_REGEX =
            Regex("""<(script|style|noscript)\b[^>]*>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("""<[^>]+>""")

        /**
         * Pure-Kotlin visible text match counter used when no live native `CefpackSession`
         * is attached (e.g., cached snapshot tabs or Robolectric JVM Compose tests).
         */
        internal fun countPlainTextMatchesInHtml(html: String, query: String): Int {
            val needle = query.trim()
            if (html.isBlank() || needle.isEmpty()) return 0
            val visibleText = html
                .replace(SCRIPT_STYLE_REGEX, " ")
                .replace(HTML_TAG_REGEX, " ")
                .replace("&nbsp;", " ", ignoreCase = true)
                .replace("&amp;", "&", ignoreCase = true)
                .replace("&lt;", "<", ignoreCase = true)
                .replace("&gt;", ">", ignoreCase = true)
            var count = 0
            var searchFrom = 0
            while (searchFrom <= visibleText.length - needle.length) {
                val found = visibleText.indexOf(needle, startIndex = searchFrom, ignoreCase = true)
                if (found < 0) break
                count++
                searchFrom = found + needle.length.coerceAtLeast(1)
            }
            return count
        }

        /**
         * Parses the JSON array returned by `OvNative.nativeClassifyCdpDom`, excluding
         * passive search-box inputs (`isSearchLike == true`) from editable PGP compose
         * candidates unless [includeSearchFields] is explicitly requested.
         */
        internal fun parseClassifiedDomEntries(
            json: String,
            includeSearchFields: Boolean = false,
        ): List<ClassifiedNodeEntry> {
            if (json.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val nodeId = obj.optInt("nodeId", 0)
                        val backendNodeId = obj.optInt("backendNodeId", 0)
                        if (nodeId <= 0 && backendNodeId <= 0) continue
                        val isSearchLike = obj.optBoolean("isSearchLike", false)
                        if (isSearchLike && !includeSearchFields) continue
                        add(
                            ClassifiedNodeEntry(
                                nodeId = nodeId,
                                backendNodeId = backendNodeId,
                                tag = obj.optString("tag", ""),
                                kind = obj.optString("kind", ""),
                                actionLabel = obj.optString("actionLabel", ""),
                                text = obj.optString("text", ""),
                                fingerprintHint = obj.optString("fingerprintHint", ""),
                                isSearchLike = isSearchLike,
                            ),
                        )
                    }
                }
            }.getOrElse { emptyList() }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _activeNodes = MutableStateFlow<Map<String, List<DetectedPgpNode>>>(emptyMap())
    val activeNodes: StateFlow<Map<String, List<DetectedPgpNode>>> = _activeNodes.asStateFlow()

    private val attachedBrowsers = ConcurrentHashMap<String, CefpackSession>()
    private val lastUrlByTab = ConcurrentHashMap<String, String>()
    private val lastClassifiedByTab = ConcurrentHashMap<String, List<ClassifiedNodeEntry>>()
    private val scanGenerationByTab = ConcurrentHashMap<String, AtomicInteger>()
    private val activeSearchByTab = ConcurrentHashMap<String, ActiveSearchSession>()

    fun nodesForTab(tabId: String): List<DetectedPgpNode> =
        _activeNodes.value[tabId].orEmpty()

    fun attachAndScan(
        tabId: String,
        browser: CefpackSession,
        currentUrl: String = browser.url ?: "",
    ) {
        runOnMain {
            if (currentUrl.isNotBlank()) {
                lastUrlByTab[tabId] = currentUrl
            }
            val alreadyAttached = attachedBrowsers.put(tabId, browser) === browser
            if (!alreadyAttached) {
                configureDevToolsDomains(tabId, browser)
            }
            performFullScan(tabId, browser, resolveScanUrl(tabId, browser, currentUrl))
        }
    }

    fun refreshViewportBounds(tabId: String, browser: CefpackSession) {
        runOnMain {
            val classified = lastClassifiedByTab[tabId]
            if (classified.isNullOrEmpty()) {
                return@runOnMain
            }
            val generation = currentGeneration(tabId)
            fetchViewportMetrics(browser) { metrics ->
                if (metrics == null || generation != currentGeneration(tabId)) {
                    return@fetchViewportMetrics
                }
                resolveAllNodeBounds(
                    tabId = tabId,
                    browser = browser,
                    classified = classified,
                    metrics = metrics,
                    generation = generation,
                ) { resolved ->
                    if (generation != currentGeneration(tabId)) return@resolveAllNodeBounds
                    publishTabNodes(tabId, resolved)
                }
            }
        }
    }

    /**
     * Executes a 100% zero-JavaScript in-page text search using Chromium C++
     * `InspectorDOMAgent` (`DOM.performSearch`, `DOM.getSearchResults`,
     * `DOM.scrollIntoViewIfNeeded`, and `DOM.getContentQuads`), falling back to
     * [fallbackDomHtml] when no live native [CefpackSession] is attached.
     */
    fun performTextSearch(
        tabId: String,
        browser: CefpackSession?,
        query: String,
        stepDelta: Int = 0,
        fallbackDomHtml: String = "",
        onResult: (TextSearchOutcome) -> Unit,
    ) {
        runOnMain {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                clearTextSearch(tabId, browser)
                onResult(TextSearchOutcome(query = "", matchCount = 0, activeIndex = 0))
                return@runOnMain
            }

            val previous = activeSearchByTab[tabId]
            if (browser == null) {
                val total = countPlainTextMatchesInHtml(fallbackDomHtml, trimmed)
                if (total <= 0) {
                    activeSearchByTab.remove(tabId)
                    onResult(TextSearchOutcome(query = query, matchCount = 0, activeIndex = 0))
                    return@runOnMain
                }
                val zeroIdx = computeNextZeroIndex(previous, trimmed, total, stepDelta)
                activeSearchByTab[tabId] = ActiveSearchSession(
                    query = trimmed,
                    searchId = "",
                    matchCount = total,
                    activeZeroIndex = zeroIdx,
                )
                onResult(
                    TextSearchOutcome(
                        query = query,
                        matchCount = total,
                        activeIndex = zeroIdx + 1,
                    ),
                )
                return@runOnMain
            }

            if (previous != null && previous.searchId.isNotEmpty()) {
                val discardParams = JSONObject().put("searchId", previous.searchId).toString()
                browser.executeDevToolsMethod("DOM.discardSearchResults", discardParams, null)
            }

            val docParams = JSONObject().put("depth", -1).put("pierce", true).toString()
            browser.executeDevToolsMethod("DOM.getDocument", docParams) { _, _ ->
                val searchParams = JSONObject()
                    .put("query", trimmed)
                    .put("includeUserAgentShadowDOM", true)
                    .toString()
                browser.executeDevToolsMethod("DOM.performSearch", searchParams) { ok, searchJson ->
                    if (!ok || searchJson.isNullOrBlank()) {
                        val fallbackCount = countPlainTextMatchesInHtml(fallbackDomHtml, trimmed)
                        val zeroIdx = if (fallbackCount > 0) {
                            computeNextZeroIndex(previous, trimmed, fallbackCount, stepDelta)
                        } else {
                            0
                        }
                        if (fallbackCount > 0) {
                            activeSearchByTab[tabId] = ActiveSearchSession(
                                query = trimmed,
                                searchId = "",
                                matchCount = fallbackCount,
                                activeZeroIndex = zeroIdx,
                            )
                        } else {
                            activeSearchByTab.remove(tabId)
                        }
                        runOnMain {
                            onResult(
                                TextSearchOutcome(
                                    query = query,
                                    matchCount = fallbackCount,
                                    activeIndex = if (fallbackCount > 0) zeroIdx + 1 else 0,
                                ),
                            )
                        }
                        return@executeDevToolsMethod
                    }

                    val parsed = runCatching { JSONObject(searchJson) }.getOrNull()
                    val searchId = parsed?.optString("searchId", "").orEmpty()
                    val resultCount = parsed?.optInt("resultCount", 0)?.coerceAtLeast(0) ?: 0
                    if (searchId.isEmpty() || resultCount <= 0) {
                        if (searchId.isNotEmpty()) {
                            val discard = JSONObject().put("searchId", searchId).toString()
                            browser.executeDevToolsMethod("DOM.discardSearchResults", discard, null)
                        }
                        activeSearchByTab.remove(tabId)
                        runOnMain {
                            onResult(TextSearchOutcome(query = query, matchCount = 0, activeIndex = 0))
                        }
                        return@executeDevToolsMethod
                    }

                    val zeroIdx = computeNextZeroIndex(previous, trimmed, resultCount, stepDelta)
                    activeSearchByTab[tabId] = ActiveSearchSession(
                        query = trimmed,
                        searchId = searchId,
                        matchCount = resultCount,
                        activeZeroIndex = zeroIdx,
                    )
                    val resultsParams = JSONObject()
                        .put("searchId", searchId)
                        .put("fromIndex", zeroIdx)
                        .put("toIndex", zeroIdx + 1)
                        .toString()
                    browser.executeDevToolsMethod("DOM.getSearchResults", resultsParams) { rOk, rJson ->
                        val nodeId = if (rOk && !rJson.isNullOrBlank()) {
                            runCatching {
                                JSONObject(rJson).optJSONArray("nodeIds")?.optInt(0, 0) ?: 0
                            }.getOrDefault(0)
                        } else {
                            0
                        }
                        if (nodeId <= 0) {
                            runOnMain {
                                onResult(
                                    TextSearchOutcome(
                                        query = query,
                                        matchCount = resultCount,
                                        activeIndex = zeroIdx + 1,
                                    ),
                                )
                            }
                            return@executeDevToolsMethod
                        }

                        val scrollParams = JSONObject().put("nodeId", nodeId).toString()
                        browser.executeDevToolsMethod("DOM.scrollIntoViewIfNeeded", scrollParams) { _, _ ->
                            resolveSearchMatchBounds(browser, nodeId) { matchBox ->
                                runOnMain {
                                    onResult(
                                        TextSearchOutcome(
                                            query = query,
                                            matchCount = resultCount,
                                            activeIndex = zeroIdx + 1,
                                            activeBounds = matchBox,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun clearTextSearch(tabId: String, browser: CefpackSession? = attachedBrowsers[tabId]) {
        val removed = activeSearchByTab.remove(tabId)
        if (removed != null && removed.searchId.isNotEmpty() && browser != null) {
            runOnMain {
                val discard = JSONObject().put("searchId", removed.searchId).toString()
                browser.executeDevToolsMethod("DOM.discardSearchResults", discard, null)
            }
        }
    }

    private fun computeNextZeroIndex(
        previous: ActiveSearchSession?,
        trimmedQuery: String,
        totalCount: Int,
        stepDelta: Int,
    ): Int {
        if (totalCount <= 1) return 0
        if (previous == null || !previous.query.equals(trimmedQuery, ignoreCase = true)) {
            return 0
        }
        val base = previous.activeZeroIndex.coerceIn(0, totalCount - 1)
        if (stepDelta == 0) return base
        val raw = (base + stepDelta) % totalCount
        return if (raw < 0) raw + totalCount else raw
    }

    private fun resolveSearchMatchBounds(
        browser: CefpackSession,
        nodeId: Int,
        onBounds: (NormalizedMatchBox?) -> Unit,
    ) {
        fetchViewportMetrics(browser) { metrics ->
            if (metrics == null) {
                onBounds(null)
                return@fetchViewportMetrics
            }
            val nodeParams = JSONObject().put("nodeId", nodeId).toString()
            browser.executeDevToolsMethod("DOM.getContentQuads", nodeParams) { qOk, qJson ->
                val quadBox = if (qOk && !qJson.isNullOrBlank()) {
                    runCatching {
                        val quads = JSONObject(qJson).optJSONArray("quads")
                        val firstQuad = quads?.optJSONArray(0)
                        parseQuadToMatchBox(firstQuad, metrics)
                    }.getOrNull()
                } else {
                    null
                }
                if (quadBox != null) {
                    onBounds(quadBox)
                    return@executeDevToolsMethod
                }
                browser.executeDevToolsMethod("DOM.getBoxModel", nodeParams) { bOk, bJson ->
                    val box = if (bOk && !bJson.isNullOrBlank()) {
                        runCatching {
                            val model = JSONObject(bJson).optJSONObject("model")
                            val quad = model?.optJSONArray("border") ?: model?.optJSONArray("content")
                            parseQuadToMatchBox(quad, metrics)
                        }.getOrNull()
                    } else {
                        null
                    }
                    onBounds(box)
                }
            }
        }
    }

    private fun parseQuadToMatchBox(quad: JSONArray?, metrics: ViewportMetrics): NormalizedMatchBox? {
        if (quad == null || quad.length() < 8) return null
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until 8 step 2) {
            val x = quad.optDouble(i)
            val y = quad.optDouble(i + 1)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (!minX.isFinite() || !maxX.isFinite() || maxX <= minX || maxY <= minY) return null
        val left = ((minX - metrics.pageX) / metrics.width).toFloat().coerceIn(0f, 1f)
        val top = ((minY - metrics.pageY) / metrics.height).toFloat().coerceIn(0f, 1f)
        val right = ((maxX - metrics.pageX) / metrics.width).toFloat().coerceIn(0f, 1f)
        val bottom = ((maxY - metrics.pageY) / metrics.height).toFloat().coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        return NormalizedMatchBox(left = left, top = top, right = right, bottom = bottom)
    }

    /**
     * Writes [newValue] into a live HTML `<textarea>` or `<input>` without JavaScript,
     * then re-queries `DOM.getDocument({"depth":-1,"pierce":true})` to verify that the
     * Blink UA shadow-root editor actually holds [newValue] before reporting success.
     */
    fun writeFieldValue(
        browser: CefpackSession,
        nodeId: Int,
        backendNodeId: Int = 0,
        newValue: String,
        tabId: String? = null,
        onComplete: (FieldMutationOutcome) -> Unit,
    ) {
        runOnMain {
            if (nodeId <= 0 && backendNodeId <= 0) {
                onComplete(
                    FieldMutationOutcome(
                        ok = false,
                        verified = false,
                        nodeId = nodeId,
                        backendNodeId = backendNodeId,
                        error = "Missing DOM node identifier for field write",
                    ),
                )
                return@runOnMain
            }
            attemptZeroJsWrite(
                browser = browser,
                nodeId = nodeId,
                backendNodeId = backendNodeId,
                newValue = newValue,
                attempt = 1,
            ) { outcome ->
                if (outcome.ok && tabId != null) {
                    performFullScan(tabId, browser, resolveScanUrl(tabId, browser))
                }
                runOnMain { onComplete(outcome) }
            }
        }
    }

    fun detachTab(tabId: String) {
        clearTextSearch(tabId)
        attachedBrowsers.remove(tabId)
        lastUrlByTab.remove(tabId)
        lastClassifiedByTab.remove(tabId)
        scanGenerationByTab.remove(tabId)
        val updated = _activeNodes.value.toMutableMap()
        updated.remove(tabId)
        _activeNodes.value = updated
    }

    fun clearAll() {
        activeSearchByTab.clear()
        attachedBrowsers.clear()
        lastUrlByTab.clear()
        lastClassifiedByTab.clear()
        scanGenerationByTab.clear()
        _activeNodes.value = emptyMap()
    }

    private fun configureDevToolsDomains(tabId: String, browser: CefpackSession) {
        browser.setDevToolsEventListener { method, _ ->
            when (method) {
                "DOM.documentUpdated",
                "Page.loadEventFired",
                "Page.frameStoppedLoading" -> {
                    runOnMain {
                        val activeBrowser = attachedBrowsers[tabId] ?: return@runOnMain
                        performFullScan(tabId, activeBrowser, resolveScanUrl(tabId, activeBrowser))
                    }
                }
            }
        }
        browser.executeDevToolsMethod("Page.enable", "{}", null)
        browser.executeDevToolsMethod("DOM.enable", "{}", null)
        browser.executeDevToolsMethod(
            "Emulation.setScriptExecutionDisabled",
            "{\"value\":true}",
            null,
        )
        // Override Blink's default #FFFFFF canvas backdrop with Trap obsidian (#0D0F12)
        // so unloaded, connecting, or resizing PiP viewports never flash pure white.
        browser.executeDevToolsMethod(
            "Emulation.setDefaultBackgroundColorOverride",
            """{"color":{"r":13,"g":15,"b":18,"a":255}}""",
            null,
        )
    }

    private fun resolveScanUrl(
        tabId: String,
        browser: CefpackSession,
        explicitUrl: String = "",
    ): String {
        val explicit = explicitUrl.trim()
        if (explicit.isNotEmpty() && explicit != "about:blank") {
            lastUrlByTab[tabId] = explicit
            return explicit
        }
        val live = browser.url?.trim().orEmpty()
        if (live.isNotEmpty() && live != "about:blank") {
            lastUrlByTab[tabId] = live
            return live
        }
        if (explicit == "about:blank") {
            return "about:blank"
        }
        return lastUrlByTab[tabId].orEmpty().ifEmpty { live }
    }

    private fun performFullScan(
        tabId: String,
        browser: CefpackSession,
        currentUrl: String,
    ) {
        val genCounter = scanGenerationByTab.getOrPut(tabId) { AtomicInteger(0) }
        val generation = genCounter.incrementAndGet()

        if (currentUrl.isBlank() || currentUrl == "about:blank") {
            lastClassifiedByTab.remove(tabId)
            publishTabNodes(tabId, emptyList())
            onNodesDetected(tabId, currentUrl, "", emptyList())
            return
        }

        val docParams = JSONObject()
            .put("depth", -1)
            .put("pierce", true)
            .toString()

        browser.executeDevToolsMethod("DOM.getDocument", docParams) { ok, docJson ->
            if (!ok || docJson.isNullOrBlank() || generation != currentGeneration(tabId)) {
                return@executeDevToolsMethod
            }

            val rootObj = runCatching { JSONObject(docJson).optJSONObject("root") }.getOrNull()
            val rootNodeId = rootObj?.optInt("nodeId", 0) ?: 0

            val classified = classifyDomTree(docJson)
            lastClassifiedByTab[tabId] = classified

            fetchOuterHtml(browser, rootNodeId) { domHtml ->
                if (generation != currentGeneration(tabId)) return@fetchOuterHtml
                if (classified.isEmpty()) {
                    publishTabNodes(tabId, emptyList())
                    onNodesDetected(tabId, currentUrl, domHtml, emptyList())
                    return@fetchOuterHtml
                }

                fetchViewportMetrics(browser) { metrics ->
                    if (generation != currentGeneration(tabId)) return@fetchViewportMetrics
                    if (metrics == null) {
                        publishTabNodes(tabId, emptyList())
                        onNodesDetected(tabId, currentUrl, domHtml, emptyList())
                        return@fetchViewportMetrics
                    }
                    resolveAllNodeBounds(
                        tabId = tabId,
                        browser = browser,
                        classified = classified,
                        metrics = metrics,
                        generation = generation,
                    ) { detected ->
                        if (generation != currentGeneration(tabId)) return@resolveAllNodeBounds
                        publishTabNodes(tabId, detected)
                        onNodesDetected(tabId, currentUrl, domHtml, detected)
                    }
                }
            }
        }
    }

    private fun classifyDomTree(
        rawDocJson: String,
    ): List<ClassifiedNodeEntry> {
        if (!OvNative.isLoaded) {
            return emptyList()
        }
        val classifiedJson = runCatching {
            OvNative.nativeClassifyCdpDom(rawDocJson)
        }.onFailure {
            Log.w(TAG, "nativeClassifyCdpDom failed", it)
        }.getOrDefault("[]")
        return parseClassifiedDomEntries(classifiedJson, includeSearchFields = false)
    }

    private fun fetchOuterHtml(
        browser: CefpackSession,
        rootNodeId: Int,
        onResult: (String) -> Unit,
    ) {
        if (rootNodeId <= 0) {
            onResult("")
            return
        }
        val params = JSONObject().put("nodeId", rootNodeId).toString()
        browser.executeDevToolsMethod("DOM.getOuterHTML", params) { ok, json ->
            val html = if (ok && !json.isNullOrBlank()) {
                runCatching { JSONObject(json).optString("outerHTML", "") }.getOrDefault("")
            } else {
                ""
            }
            onResult(html)
        }
    }

    private fun fetchViewportMetrics(
        browser: CefpackSession,
        onResult: (ViewportMetrics?) -> Unit,
    ) {
        browser.executeDevToolsMethod("Page.getLayoutMetrics", "{}") { ok, json ->
            if (!ok || json.isNullOrBlank()) {
                onResult(null)
                return@executeDevToolsMethod
            }
            val metrics = runCatching {
                val root = JSONObject(json)
                val visual = root.optJSONObject("cssVisualViewport")
                val layout = root.optJSONObject("cssLayoutViewport")
                val vp = visual ?: layout ?: return@runCatching null
                val width = vp.optDouble("clientWidth", 0.0)
                val height = vp.optDouble("clientHeight", 0.0)
                if (width <= 1.0 || height <= 1.0) return@runCatching null
                ViewportMetrics(
                    pageX = vp.optDouble("pageX", 0.0),
                    pageY = vp.optDouble("pageY", 0.0),
                    width = width,
                    height = height,
                )
            }.getOrNull()
            onResult(metrics)
        }
    }

    private fun resolveAllNodeBounds(
        tabId: String,
        browser: CefpackSession,
        classified: List<ClassifiedNodeEntry>,
        metrics: ViewportMetrics,
        generation: Int,
        onComplete: (List<DetectedPgpNode>) -> Unit,
    ) {
        if (classified.isEmpty()) {
            onComplete(emptyList())
            return
        }
        val results = arrayOfNulls<DetectedPgpNode>(classified.size)
        val remaining = AtomicInteger(classified.size)

        classified.forEachIndexed { index, node ->
            val params = JSONObject().apply {
                if (node.backendNodeId > 0) {
                    put("backendNodeId", node.backendNodeId)
                } else {
                    put("nodeId", node.nodeId)
                }
            }.toString()

            browser.executeDevToolsMethod("DOM.getBoxModel", params) { ok, boxJson ->
                if (ok && !boxJson.isNullOrBlank() && generation == currentGeneration(tabId)) {
                    results[index] = parseBoxModelToDetectedNode(tabId, node, boxJson, metrics)
                }
                if (remaining.decrementAndGet() == 0) {
                    val visible = results.filterNotNull()
                    runOnMain { onComplete(visible) }
                }
            }
        }
    }

    private fun parseBoxModelToDetectedNode(
        tabId: String,
        node: ClassifiedNodeEntry,
        boxJson: String,
        metrics: ViewportMetrics,
    ): DetectedPgpNode? {
        return runCatching {
            val model = JSONObject(boxJson).optJSONObject("model") ?: return null
            val quad = model.optJSONArray("border") ?: model.optJSONArray("content") ?: return null
            if (quad.length() < 8) return null

            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (i in 0 until 8 step 2) {
                val x = quad.optDouble(i)
                val y = quad.optDouble(i + 1)
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            if (!minX.isFinite() || !maxX.isFinite() || maxX - minX < 4.0 || maxY - minY < 4.0) {
                return null
            }

            val normLeft = ((minX - metrics.pageX) / metrics.width).toFloat()
            val normTop = ((minY - metrics.pageY) / metrics.height).toFloat()
            val normRight = ((maxX - metrics.pageX) / metrics.width).toFloat()
            val normBottom = ((maxY - metrics.pageY) / metrics.height).toFloat()

            // Skip off-screen elements completely outside the visible viewport.
            if (normBottom <= 0.0f || normTop >= 1.0f || normRight <= 0.0f || normLeft >= 1.0f) {
                return null
            }

            val stableId = if (node.backendNodeId > 0) node.backendNodeId else node.nodeId
            DetectedPgpNode(
                key = "$tabId:$stableId:${node.kind}",
                nodeId = node.nodeId,
                backendNodeId = node.backendNodeId,
                tag = node.tag,
                kind = node.kind,
                actionLabel = node.actionLabel,
                text = node.text,
                fingerprintHint = node.fingerprintHint,
                normalizedLeft = normLeft.coerceIn(0.0f, 1.0f),
                normalizedTop = normTop.coerceIn(0.0f, 1.0f),
                normalizedRight = normRight.coerceIn(0.0f, 1.0f),
                normalizedBottom = normBottom.coerceIn(0.0f, 1.0f),
                isSearchLike = node.isSearchLike,
            )
        }.getOrNull()
    }

    private fun attemptZeroJsWrite(
        browser: CefpackSession,
        nodeId: Int,
        backendNodeId: Int,
        newValue: String,
        attempt: Int,
        onComplete: (FieldMutationOutcome) -> Unit,
    ) {
        val focusParams = JSONObject().apply {
            if (backendNodeId > 0) {
                put("backendNodeId", backendNodeId)
            } else {
                put("nodeId", nodeId)
            }
        }.toString()

        browser.executeDevToolsMethod("DOM.focus", focusParams) { focusOk, focusResp ->
            if (!focusOk) {
                onComplete(
                    FieldMutationOutcome(
                        ok = false,
                        verified = false,
                        nodeId = nodeId,
                        backendNodeId = backendNodeId,
                        error = "DOM.focus failed: ${focusResp.orEmpty()}",
                    ),
                )
                return@executeDevToolsMethod
            }

            val selectAllDown = JSONObject()
                .put("type", "rawKeyDown")
                .put(
                    "commands",
                    if (attempt == 1) {
                        JSONArray().put("SelectAll")
                    } else {
                        JSONArray()
                            .put("MoveToBeginningOfDocument")
                            .put("MoveToEndOfDocumentAndModifySelection")
                    },
                )
                .toString()
            val keyUp = JSONObject().put("type", "keyUp").toString()
            val backspaceDown = JSONObject()
                .put("type", "rawKeyDown")
                .put("windowsVirtualKeyCode", 8)
                .put("key", "Backspace")
                .put("code", "Backspace")
                .put("commands", JSONArray().put("DeleteBackward"))
                .toString()

            browser.executeDevToolsMethod("Input.dispatchKeyEvent", selectAllDown) { _, _ ->
                browser.executeDevToolsMethod("Input.dispatchKeyEvent", keyUp) { _, _ ->
                    browser.executeDevToolsMethod("Input.dispatchKeyEvent", backspaceDown) { _, _ ->
                        browser.executeDevToolsMethod("Input.dispatchKeyEvent", keyUp) { _, _ ->
                            val insertParams = JSONObject().put("text", newValue).toString()
                            browser.executeDevToolsMethod("Input.insertText", insertParams) { insOk, insResp ->
                                if (!insOk) {
                                    onComplete(
                                        FieldMutationOutcome(
                                            ok = false,
                                            verified = false,
                                            nodeId = nodeId,
                                            backendNodeId = backendNodeId,
                                            error = "Input.insertText failed: ${insResp.orEmpty()}",
                                        ),
                                    )
                                    return@executeDevToolsMethod
                                }
                                verifyFieldValueInDom(
                                    browser = browser,
                                    nodeId = nodeId,
                                    backendNodeId = backendNodeId,
                                    expectedValue = newValue,
                                ) { verified, actual ->
                                    if (verified) {
                                        onComplete(
                                            FieldMutationOutcome(
                                                ok = true,
                                                verified = true,
                                                nodeId = nodeId,
                                                backendNodeId = backendNodeId,
                                            ),
                                        )
                                    } else if (attempt < 2) {
                                        attemptZeroJsWrite(
                                            browser = browser,
                                            nodeId = nodeId,
                                            backendNodeId = backendNodeId,
                                            newValue = newValue,
                                            attempt = attempt + 1,
                                            onComplete = onComplete,
                                        )
                                    } else {
                                        onComplete(
                                            FieldMutationOutcome(
                                                ok = false,
                                                verified = false,
                                                nodeId = nodeId,
                                                backendNodeId = backendNodeId,
                                                error = "DOM read-back mismatch after write (len=${actual?.length ?: -1})",
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun verifyFieldValueInDom(
        browser: CefpackSession,
        nodeId: Int,
        backendNodeId: Int,
        expectedValue: String,
        onVerified: (verified: Boolean, actualValue: String?) -> Unit,
    ) {
        val docParams = JSONObject()
            .put("depth", -1)
            .put("pierce", true)
            .toString()
        browser.executeDevToolsMethod("DOM.getDocument", docParams) { ok, docJson ->
            if (!ok || docJson.isNullOrBlank()) {
                onVerified(false, null)
                return@executeDevToolsMethod
            }
            val root = runCatching { JSONObject(docJson).optJSONObject("root") }.getOrNull()
            if (root == null) {
                onVerified(false, null)
                return@executeDevToolsMethod
            }
            val targetNode = findDomNode(root, nodeId, backendNodeId)
            if (targetNode == null) {
                onVerified(false, null)
                return@executeDevToolsMethod
            }
            val actual = extractNodeLiveText(targetNode)
            val normalizedActual = actual.replace("\r\n", "\n").trimEnd('\n')
            val normalizedExpected = expectedValue.replace("\r\n", "\n").trimEnd('\n')
            val matches = if (normalizedExpected.isEmpty()) {
                normalizedActual.isEmpty()
            } else {
                normalizedActual == normalizedExpected
            }
            onVerified(matches, actual)
        }
    }

    private fun findDomNode(
        node: JSONObject,
        nodeId: Int,
        backendNodeId: Int,
    ): JSONObject? {
        if (backendNodeId > 0 && node.optInt("backendNodeId", 0) == backendNodeId) {
            return node
        }
        if (backendNodeId <= 0 && nodeId > 0 && node.optInt("nodeId", 0) == nodeId) {
            return node
        }
        node.optJSONArray("children")?.let { children ->
            for (i in 0 until children.length()) {
                val child = children.optJSONObject(i) ?: continue
                findDomNode(child, nodeId, backendNodeId)?.let { return it }
            }
        }
        node.optJSONArray("shadowRoots")?.let { shadows ->
            for (i in 0 until shadows.length()) {
                val shadow = shadows.optJSONObject(i) ?: continue
                findDomNode(shadow, nodeId, backendNodeId)?.let { return it }
            }
        }
        node.optJSONObject("contentDocument")?.let { doc ->
            findDomNode(doc, nodeId, backendNodeId)?.let { return it }
        }
        return null
    }

    private fun extractNodeLiveText(node: JSONObject): String {
        val shadows = node.optJSONArray("shadowRoots")
        if (shadows != null && shadows.length() > 0) {
            for (i in 0 until shadows.length()) {
                val shadow = shadows.optJSONObject(i) ?: continue
                val innerEditor = findInnerEditor(shadow)
                if (innerEditor != null) {
                    val editorBuilder = StringBuilder()
                    appendShadowEditorText(innerEditor, editorBuilder)
                    return editorBuilder.toString()
                }
            }
            val shadowBuilder = StringBuilder()
            for (i in 0 until shadows.length()) {
                val shadow = shadows.optJSONObject(i) ?: continue
                appendShadowEditorText(shadow, shadowBuilder)
            }
            return shadowBuilder.toString()
        }
        val attrs = parseAttributesList(node.optJSONArray("attributes"))
        attrs["value"]?.let { return it }
        return extractElementVisibleText(node)
    }

    private fun findInnerEditor(node: JSONObject): JSONObject? {
        if (node.optInt("nodeType", 0) == 1) {
            val attrs = parseAttributesList(node.optJSONArray("attributes"))
            if (attrs["id"].equals("inner-editor", ignoreCase = true)) {
                return node
            }
            if (attrs["id"].equals("placeholder", ignoreCase = true) ||
                attrs["pseudo"]?.contains("placeholder", ignoreCase = true) == true
            ) {
                return null
            }
        }
        val children = node.optJSONArray("children") ?: return null
        for (i in 0 until children.length()) {
            val child = children.optJSONObject(i) ?: continue
            findInnerEditor(child)?.let { return it }
        }
        return null
    }

    private fun appendShadowEditorText(node: JSONObject, out: StringBuilder) {
        val nodeType = node.optInt("nodeType", 0)
        if (nodeType == 3) {
            out.append(node.optString("nodeValue", ""))
            return
        }
        if (nodeType == 1) {
            val attrs = parseAttributesList(node.optJSONArray("attributes"))
            if (attrs["id"].equals("placeholder", ignoreCase = true) ||
                attrs["pseudo"]?.contains("placeholder", ignoreCase = true) == true
            ) {
                return
            }
            val localName = node.optString("localName", node.optString("nodeName", "")).lowercase()
            if (localName == "br") {
                out.append('\n')
                return
            }
        }
        val children = node.optJSONArray("children") ?: return
        for (i in 0 until children.length()) {
            val child = children.optJSONObject(i) ?: continue
            appendShadowEditorText(child, out)
        }
    }

    private fun extractElementVisibleText(node: JSONObject): String {
        val out = StringBuilder()
        appendVisibleText(node, out)
        return out.toString()
    }

    private fun appendVisibleText(node: JSONObject, out: StringBuilder) {
        val nodeType = node.optInt("nodeType", 0)
        if (nodeType == 3) {
            out.append(node.optString("nodeValue", ""))
            return
        }
        if (nodeType == 1) {
            val tag = node.optString("localName", node.optString("nodeName", "")).lowercase()
            if (tag == "br") {
                out.append('\n')
                return
            }
            if (tag == "script" || tag == "style" || tag == "noscript") {
                return
            }
        }
        val children = node.optJSONArray("children") ?: return
        for (i in 0 until children.length()) {
            val child = children.optJSONObject(i) ?: continue
            appendVisibleText(child, out)
        }
    }

    private fun parseAttributesList(arr: JSONArray?): Map<String, String> {
        if (arr == null || arr.length() < 2) return emptyMap()
        return buildMap(arr.length() / 2) {
            var i = 0
            while (i + 1 < arr.length()) {
                put(arr.optString(i, "").lowercase(), arr.optString(i + 1, ""))
                i += 2
            }
        }
    }

    private fun currentGeneration(tabId: String): Int =
        scanGenerationByTab[tabId]?.get() ?: 0

    private fun publishTabNodes(tabId: String, nodes: List<DetectedPgpNode>) {
        val updated = _activeNodes.value.toMutableMap()
        updated[tabId] = nodes
        _activeNodes.value = updated
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
