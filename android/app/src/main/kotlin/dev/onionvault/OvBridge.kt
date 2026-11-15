package dev.onionvault

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Static callback target invoked from Rust via JNI (`dev/onionvault/OvBridge`).
 *
 * All callbacks are marshaled onto the Android main looper before reaching the
 * foreground [Listener]. Every method on [Listener] carries a default no-op
 * implementation so callers only override the events they consume.
 */
object OvBridge {

    private const val TAG = "OvBridge"
    private val mainHandler = Handler(Looper.getMainLooper())

    interface Listener {
        fun onTorProgress(percent: Int, stage: String) {}
        fun showError(message: String) {}
        fun onDomScan(json: String) {}
        fun onNavigation(json: String) {}
        fun showPlaintext(nodeId: String, title: String, body: String, verified: Boolean) {}
        fun onMutationResult(json: String) {}
        fun onPageSnapshot(url: String) {}
    }

    @Volatile
    private var listener: Listener? = null

    fun setListener(value: Listener?) {
        listener = value
    }

    @JvmStatic
    fun onTorProgress(percent: Int, stage: String) {
        dispatch { it.onTorProgress(percent, stage) }
    }

    @JvmStatic
    fun showError(message: String) {
        Log.w(TAG, message)
        dispatch { it.showError(message) }
    }

    @JvmStatic
    fun onDomScan(json: String) {
        dispatch { it.onDomScan(json) }
    }

    @JvmStatic
    fun onNavigation(json: String) {
        dispatch { it.onNavigation(json) }
    }

    @JvmStatic
    fun showPlaintext(nodeId: String, title: String, body: String, verified: Boolean) {
        dispatch { it.showPlaintext(nodeId, title, body, verified) }
    }

    @JvmStatic
    fun onMutationResult(json: String) {
        dispatch { it.onMutationResult(json) }
    }

    @JvmStatic
    fun onPageSnapshot(url: String) {
        dispatch { it.onPageSnapshot(url) }
    }

    private fun dispatch(action: (Listener) -> Unit) {
        val target = listener ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                action(target)
            } catch (error: Throwable) {
                Log.e(TAG, "OvBridge listener callback failed", error)
            }
        } else {
            mainHandler.post {
                try {
                    action(target)
                } catch (error: Throwable) {
                    Log.e(TAG, "OvBridge listener callback failed", error)
                }
            }
        }
    }
}
