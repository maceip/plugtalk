package dev.onionvault.platform

import android.content.Context
import android.media.AudioManager
import android.media.LoudnessCodecController
import android.os.Build
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.annotation.RequiresApi

/**
 * Android 15+ (API 35) Mixed SDR/HDR Page Headroom & CTA-2075 Loudness Normalization.
 *
 *   1. Caps [Window.setDesiredHdrHeadroom] (and child [SurfaceView.setDesiredHdrHeadroom])
 *      when rendering mixed SDR text/UI alongside embedded HDR media so a single HDR
 *      thumbnail or video reel never washes out or dims page text, while releasing full
 *      display HDR headroom (`0f`) when an HTML5 fullscreen video owns the viewport.
 *   2. Attaches [LoudnessCodecController] (`CTA-2075`) to active media audio sessions
 *      so switching tabs or sites with uneven AAC mastering levels never spikes volume.
 */
class MediaDisplayNormalizer(
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val controllerFactory: ((Int) -> AutoCloseable?)? = null,
) {

    private val activeControllers = LinkedHashMap<Int, AutoCloseable>()
    private var defaultBrowserAudioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE
    private var lastAppliedHdrHeadroom: Float = Float.NaN

    val activeSessionIds: Set<Int>
        @Synchronized get() = activeControllers.keys.toSet()

    /**
     * Computes target HDR headroom ratio:
     * - Fullscreen video (`isFullscreenVideo == true`): `0f` (system default full HDR range).
     * - Mixed SDR document + active/embedded media: [MIXED_PAGE_HDR_HEADROOM_CAP] (`1.25f`).
     * - Pure SDR document without active media: [SDR_PAGE_HEADROOM] (`1.0f`).
     */
    fun resolveDesiredHdrHeadroom(
        hasEmbeddedMedia: Boolean,
        isFullscreenVideo: Boolean,
    ): Float = when {
        isFullscreenVideo -> FULLSCREEN_VIDEO_HDR_HEADROOM
        hasEmbeddedMedia -> MIXED_PAGE_HDR_HEADROOM_CAP
        else -> SDR_PAGE_HEADROOM
    }

    fun applyWindowHdrHeadroom(
        window: Window,
        rootSurfaceContainer: View? = null,
        hasEmbeddedMedia: Boolean,
        isFullscreenVideo: Boolean,
    ): Float {
        val target = resolveDesiredHdrHeadroom(
            hasEmbeddedMedia = hasEmbeddedMedia,
            isFullscreenVideo = isFullscreenVideo,
        )
        if (sdkIntProvider() < 35) {
            return target
        }
        if (lastAppliedHdrHeadroom != target) {
            lastAppliedHdrHeadroom = target
            runCatching { Api35Impl.setWindowHdrHeadroom(window, target) }
        }
        if (rootSurfaceContainer != null) {
            runCatching { Api35Impl.applySurfaceTreeHdrHeadroom(rootSurfaceContainer, target) }
        }
        return target
    }

    @Synchronized
    fun ensureAudioSessionNormalized(context: Context): Int {
        if (defaultBrowserAudioSessionId <= 0) {
            val audioManager = context.getSystemService(AudioManager::class.java)
            defaultBrowserAudioSessionId = audioManager?.generateAudioSessionId()
                ?: FALLBACK_AUDIO_SESSION_ID
        }
        attachAudioSession(defaultBrowserAudioSessionId)
        return defaultBrowserAudioSessionId
    }

    @Synchronized
    fun attachAudioSession(audioSessionId: Int): Boolean {
        if (audioSessionId <= 0) return false
        if (sdkIntProvider() < 35) return false
        if (activeControllers.containsKey(audioSessionId)) return true
        val created = runCatching {
            if (controllerFactory != null) {
                controllerFactory.invoke(audioSessionId)
            } else {
                Api35Impl.createLoudnessController(audioSessionId)
            }
        }.getOrNull() ?: return false
        activeControllers[audioSessionId] = created
        return true
    }

    @Synchronized
    fun releaseAudioSession(audioSessionId: Int) {
        val removed = activeControllers.remove(audioSessionId) ?: return
        runCatching { removed.close() }
    }

    @Synchronized
    fun releaseAll() {
        val closing = activeControllers.values.toList()
        activeControllers.clear()
        closing.forEach { controller ->
            runCatching { controller.close() }
        }
    }

    companion object {
        const val FULLSCREEN_VIDEO_HDR_HEADROOM = 0f
        const val MIXED_PAGE_HDR_HEADROOM_CAP = 1.25f
        const val SDR_PAGE_HEADROOM = 1.0f
        private const val FALLBACK_AUDIO_SESSION_ID = 1001
    }

    @RequiresApi(35)
    private object Api35Impl {
        fun setWindowHdrHeadroom(window: Window, headroom: Float) {
            window.desiredHdrHeadroom = headroom
        }

        fun applySurfaceTreeHdrHeadroom(view: View, headroom: Float) {
            if (view is SurfaceView) {
                view.setDesiredHdrHeadroom(headroom)
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    applySurfaceTreeHdrHeadroom(view.getChildAt(i), headroom)
                }
            }
        }

        fun createLoudnessController(audioSessionId: Int): AutoCloseable =
            LoudnessCodecController.create(audioSessionId)
    }
}
