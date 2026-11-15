package dev.onionvault

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.cefrium.CefriumBrowser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bounded per-tab visual & DOM snapshot history powering Trap's predictive-back
 * gesture (`PredictiveBackHandler`).
 *
 * Snapshots are captured from live Chromium compositor frames via
 * [CefriumBrowser.captureBitmap] (`PixelCopy` on Android's `SurfaceView`) and
 * retained strictly in RAM (capped at [maxEntriesPerTab] per tab; wiped
 * immediately on tab close or `New Identity`).
 */
class OnionBackStackManager(
    private val maxEntriesPerTab: Int = MAX_ENTRIES_PER_TAB,
) {

    companion object {
        const val MAX_ENTRIES_PER_TAB: Int = 12
    }

    data class BackSnapshotEntry(
        val tabId: String,
        val url: String,
        val title: String,
        val scrollOffsetY: Int,
        val domHtml: String,
        val nodes: List<CdpDomPgpController.DetectedPgpNode>,
        val bitmap: Bitmap?,
        val imageBitmap: ImageBitmap?,
        val capturedAtMillis: Long,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val stacksByTab = LinkedHashMap<String, ArrayDeque<BackSnapshotEntry>>()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun capture(
        tabId: String,
        browser: CefriumBrowser,
        domHtml: String = "",
        nodes: List<CdpDomPgpController.DetectedPgpNode> = emptyList(),
        scrollOffsetY: Int = 0,
        onComplete: (BackSnapshotEntry?) -> Unit = {},
    ) {
        val invokeCapture = Runnable {
            val currentUrl = browser.url.orEmpty()
            if (currentUrl.isBlank()) {
                onComplete(null)
                return@Runnable
            }
            val currentTitle = resolveCurrentTitle(browser, currentUrl)
            browser.captureBitmap { capturedBitmap ->
                val safeCopy = capturedBitmap?.let { bmp ->
                    runCatching {
                        if (bmp.isRecycled) null else bmp.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                }
                val imgBitmap = safeCopy?.let { runCatching { it.asImageBitmap() }.getOrNull() }
                val entry = BackSnapshotEntry(
                    tabId = tabId,
                    url = currentUrl,
                    title = currentTitle,
                    scrollOffsetY = scrollOffsetY,
                    domHtml = domHtml,
                    nodes = nodes,
                    bitmap = safeCopy,
                    imageBitmap = imgBitmap,
                    capturedAtMillis = System.currentTimeMillis(),
                )
                recordSnapshot(entry)
                onComplete(entry)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            invokeCapture.run()
        } else {
            mainHandler.post(invokeCapture)
        }
    }

    fun recordSnapshot(entry: BackSnapshotEntry) {
        synchronized(lock) {
            val deque = stacksByTab.getOrPut(entry.tabId) { ArrayDeque() }
            val newest = deque.lastOrNull()
            if (newest != null && newest.url == entry.url) {
                deque.removeLast()
            }
            deque.addLast(entry)
            while (deque.size > maxEntriesPerTab.coerceAtLeast(1)) {
                deque.removeFirst()
            }
            _revision.value = _revision.value + 1L
        }
    }

    fun entries(tabId: String): List<BackSnapshotEntry> = synchronized(lock) {
        stacksByTab[tabId]?.toList().orEmpty()
    }

    fun latest(tabId: String): BackSnapshotEntry? = synchronized(lock) {
        stacksByTab[tabId]?.lastOrNull()
    }

    fun previous(tabId: String): BackSnapshotEntry? = synchronized(lock) {
        val deque = stacksByTab[tabId] ?: return null
        when {
            deque.size >= 2 -> deque[deque.size - 2]
            deque.size == 1 -> deque.first()
            else -> null
        }
    }

    fun previousForUrl(tabId: String, currentUrl: String): BackSnapshotEntry? = synchronized(lock) {
        val deque = stacksByTab[tabId] ?: return null
        if (deque.isEmpty()) return null
        val newest = deque.last()
        if (currentUrl.isNotBlank() && newest.url != currentUrl) {
            return newest
        }
        if (deque.size >= 2) {
            return deque[deque.size - 2]
        }
        return null
    }

    fun canPreviewBack(tabId: String, currentUrl: String = ""): Boolean =
        if (currentUrl.isNotBlank()) {
            previousForUrl(tabId, currentUrl) != null
        } else {
            previous(tabId) != null
        }

    fun popNewest(tabId: String): BackSnapshotEntry? = synchronized(lock) {
        val deque = stacksByTab[tabId] ?: return null
        if (deque.isEmpty()) return null
        val removed = deque.removeLast()
        _revision.value = _revision.value + 1L
        removed
    }

    fun clearTab(tabId: String) {
        synchronized(lock) {
            if (stacksByTab.remove(tabId) != null) {
                _revision.value = _revision.value + 1L
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            if (stacksByTab.isNotEmpty()) {
                stacksByTab.clear()
                _revision.value = _revision.value + 1L
            }
        }
    }

    private fun resolveCurrentTitle(browser: CefriumBrowser, fallbackUrl: String): String {
        val entries = runCatching { browser.navigationEntries }.getOrNull()
        val current = entries?.firstOrNull { it.current }
        val title = current?.title?.trim().orEmpty()
        return title.ifEmpty { fallbackUrl }
    }
}
