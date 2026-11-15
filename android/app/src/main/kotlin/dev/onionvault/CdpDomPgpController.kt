package dev.onionvault

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cefrium.CefriumBrowser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 100% Zero-JavaScript DOM OpenPGP scanner, highlighter, and form-field mutator
 * powered by Cefrium's in-process Chrome DevTools Protocol (`executeDevToolsMethod`).
 *
 * Operates exclusively through Chromium's C++ `InspectorDOMAgent`, `InspectorPageAgent`,
 * and `InspectorInputAgent` (`DOM.getDocument`, `DOM.getOuterHTML`, `DOM.getBoxModel`,
 * `DOM.setAttributeValue`, `DOM.focus`, `Input.dispatchKeyEvent`, `Input.insertText`)
 * while `setJavaScriptEnabled(false)` and `Emulation.setScriptExecutionDisabled(true)`
 * remain enforced on every browser instance.
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

    private companion object {
        const val TAG = "CdpDomPgp"
        const val HIGHLIGHT_MARKER = "/*trap-pgp*/"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _activeNodes = MutableStateFlow<Map<String, List<DetectedPgpNode>>>(emptyMap())
    val activeNodes: StateFlow<Map<String, List<DetectedPgpNode>>> = _activeNodes.asStateFlow()

    private val attachedBrowsers = ConcurrentHashMap<String, CefriumBrowser>()
    private val lastClassifiedByTab = ConcurrentHashMap<String, List<OvNative.ClassifiedDomNode>>()
    private val lastAttrMapByTab = ConcurrentHashMap<String, Map<Int, Map<String, String>>>()
    private val scanGenerationByTab = ConcurrentHashMap<String, AtomicInteger>()

    fun nodesForTab(tabId: String): List<DetectedPgpNode> =
        _activeNodes.value[tabId].orEmpty()

    fun attachAndScan(
        tabId: String,
        browser: CefriumBrowser,
        currentUrl: String = browser.url ?: "",
    ) {
        runOnMain {
            val alreadyAttached = attachedBrowsers.put(tabId, browser) === browser
            if (!alreadyAttached) {
                configureDevToolsDomains(tabId, browser)
            }
            performFullScan(tabId, browser, currentUrl)
        }
    }

    fun refreshViewportBounds(tabId: String, browser: CefriumBrowser) {
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
     * Writes [newValue] into a live HTML `<textarea>` or `<input>` without JavaScript,
     * then re-queries `DOM.getDocument({"depth":-1,"pierce":true})` to verify that the
     * Blink UA shadow-root editor actually holds [newValue] before reporting success.
     */
    fun writeFieldValue(
        browser: CefriumBrowser,
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
                    performFullScan(tabId, browser, browser.url ?: "")
                }
                runOnMain { onComplete(outcome) }
            }
        }
    }

    fun detachTab(tabId: String) {
        attachedBrowsers.remove(tabId)
        lastClassifiedByTab.remove(tabId)
        lastAttrMapByTab.remove(tabId)
        scanGenerationByTab.remove(tabId)
        val updated = _activeNodes.value.toMutableMap()
        updated.remove(tabId)
        _activeNodes.value = updated
    }

    fun clearAll() {
        attachedBrowsers.clear()
        lastClassifiedByTab.clear()
        lastAttrMapByTab.clear()
        scanGenerationByTab.clear()
        _activeNodes.value = emptyMap()
    }

    private fun configureDevToolsDomains(tabId: String, browser: CefriumBrowser) {
        browser.setDevToolsEventListener { method, _ ->
            when (method) {
                "DOM.documentUpdated",
                "Page.loadEventFired",
                "Page.frameStoppedLoading" -> {
                    runOnMain {
                        val activeBrowser = attachedBrowsers[tabId] ?: return@runOnMain
                        performFullScan(tabId, activeBrowser, activeBrowser.url ?: "")
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
    }

    private fun performFullScan(
        tabId: String,
        browser: CefriumBrowser,
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
            val attrMap = HashMap<Int, Map<String, String>>()
            if (rootObj != null) {
                collectAttributeMap(rootObj, attrMap)
            }
            lastAttrMapByTab[tabId] = attrMap

            val classified = classifyDomTree(docJson)
            lastClassifiedByTab[tabId] = classified

            applyZeroJsHighlights(browser, classified, attrMap)

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
    ): List<OvNative.ClassifiedDomNode> {
        if (!OvNative.isLoaded) {
            return emptyList()
        }
        val classifiedJson = runCatching {
            OvNative.nativeClassifyCdpDom(rawDocJson)
        }.onFailure {
            Log.w(TAG, "nativeClassifyCdpDom failed", it)
        }.getOrDefault("[]")
        return OvNative.parseClassifiedDom(classifiedJson)
    }

    private fun applyZeroJsHighlights(
        browser: CefriumBrowser,
        classified: List<OvNative.ClassifiedDomNode>,
        attrMap: Map<Int, Map<String, String>>,
    ) {
        for (node in classified) {
            if (node.nodeId <= 0) continue
            val existingStyle = attrMap[node.nodeId]?.get("style").orEmpty()
            if (existingStyle.contains(HIGHLIGHT_MARKER)) continue
            val outlineCss = when (node.kind) {
                "pgp_textarea" -> "outline: 1.5px solid rgba(232,163,61,0.80); outline-offset: 2px;"
                "pgp_message" -> "outline: 1.5px solid rgba(46,196,176,0.80); outline-offset: 2px;"
                "pgp_public_key" -> "outline: 1.5px solid rgba(62,207,106,0.80); outline-offset: 2px;"
                else -> continue
            }
            val mergedStyle = buildString {
                if (existingStyle.isNotBlank()) {
                    append(existingStyle.trimEnd().trimEnd(';'))
                    append("; ")
                }
                append(outlineCss)
                append(' ')
                append(HIGHLIGHT_MARKER)
            }
            val params = JSONObject()
                .put("nodeId", node.nodeId)
                .put("name", "style")
                .put("value", mergedStyle)
                .toString()
            browser.executeDevToolsMethod("DOM.setAttributeValue", params, null)
        }
    }

    private fun fetchOuterHtml(
        browser: CefriumBrowser,
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
        browser: CefriumBrowser,
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
        browser: CefriumBrowser,
        classified: List<OvNative.ClassifiedDomNode>,
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
        node: OvNative.ClassifiedDomNode,
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
            )
        }.getOrNull()
    }

    private fun attemptZeroJsWrite(
        browser: CefriumBrowser,
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
        browser: CefriumBrowser,
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
        val shadowBuilder = StringBuilder()
        val shadows = node.optJSONArray("shadowRoots")
        if (shadows != null && shadows.length() > 0) {
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

    private fun appendShadowEditorText(node: JSONObject, out: StringBuilder) {
        val nodeType = node.optInt("nodeType", 0)
        if (nodeType == 3) {
            out.append(node.optString("nodeValue", ""))
            return
        }
        val localName = node.optString("localName", node.optString("nodeName", "")).lowercase()
        if (nodeType == 1 && localName == "br") {
            out.append('\n')
            return
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

    private fun collectAttributeMap(
        node: JSONObject,
        out: MutableMap<Int, Map<String, String>>,
    ) {
        val nodeId = node.optInt("nodeId", 0)
        if (nodeId > 0) {
            val attrs = parseAttributesList(node.optJSONArray("attributes"))
            if (attrs.isNotEmpty()) {
                out[nodeId] = attrs
            }
        }
        node.optJSONArray("children")?.let { children ->
            for (i in 0 until children.length()) {
                val child = children.optJSONObject(i) ?: continue
                collectAttributeMap(child, out)
            }
        }
        node.optJSONArray("shadowRoots")?.let { shadows ->
            for (i in 0 until shadows.length()) {
                val shadow = shadows.optJSONObject(i) ?: continue
                collectAttributeMap(shadow, out)
            }
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
