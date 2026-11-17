package dev.onionvault.platform

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeColorFilter
import android.os.Build
import android.os.CpuHeadroomParams
import android.os.GpuHeadroomParams
import android.os.SystemClock
import android.os.health.SystemHealthManager
import android.view.Display
import android.view.View
import android.view.Window
import androidx.annotation.RequiresApi

/**
 * Surface color-filter modes rendered natively via API 36+ AGSL [RuntimeColorFilter]
 * (with API 31–35 [ColorMatrixColorFilter] [RenderEffect] fallback) — zero CSS/JS injection.
 */
enum class SurfaceFilterMode(
    val storageKey: String,
    val label: String,
) {
    None("none", "Off"),
    ReaderWarm("reader_warm", "Warm"),
    EInkGrayscale("eink_grayscale", "E-Ink"),
    DarkContrast("dark_contrast", "Invert"),
    ;

    companion object {
        fun fromKey(raw: String?): SurfaceFilterMode =
            entries.firstOrNull { it.storageKey == raw } ?: None
    }
}

/**
 * Android 16+ (API 36) CPU/GPU Headroom Throttling, Adaptive Refresh Rate (ARR),
 * and AGSL [RuntimeColorFilter] Surface Filters.
 */
class PerformanceAndRenderController(
    private val environment: Environment,
) {

    constructor(context: Context) : this(AndroidEnvironment(context))

    interface Environment {
        val sdkInt: Int
        fun nowElapsedRealtimeMs(): Long
        fun minCpuHeadroomIntervalMs(): Long
        fun minGpuHeadroomIntervalMs(): Long
        fun queryCpuHeadroom(): Float
        fun queryGpuHeadroom(): Float
        fun hasArrSupport(window: Window): Boolean
        fun suggestedFrameRate(window: Window, highCategory: Boolean): Float
    }

    private class AndroidEnvironment(
        private val context: Context,
    ) : Environment {
        override val sdkInt: Int
            get() = Build.VERSION.SDK_INT

        override fun nowElapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

        override fun minCpuHeadroomIntervalMs(): Long {
            if (Build.VERSION.SDK_INT < 36) return DEFAULT_MIN_SAMPLE_INTERVAL_MS
            val shm = context.getSystemService(SystemHealthManager::class.java)
                ?: return DEFAULT_MIN_SAMPLE_INTERVAL_MS
            return runCatching { Api36Impl.cpuMinInterval(shm) }
                .getOrDefault(DEFAULT_MIN_SAMPLE_INTERVAL_MS)
                .coerceAtLeast(DEFAULT_MIN_SAMPLE_INTERVAL_MS)
        }

        override fun minGpuHeadroomIntervalMs(): Long {
            if (Build.VERSION.SDK_INT < 36) return DEFAULT_MIN_SAMPLE_INTERVAL_MS
            val shm = context.getSystemService(SystemHealthManager::class.java)
                ?: return DEFAULT_MIN_SAMPLE_INTERVAL_MS
            return runCatching { Api36Impl.gpuMinInterval(shm) }
                .getOrDefault(DEFAULT_MIN_SAMPLE_INTERVAL_MS)
                .coerceAtLeast(DEFAULT_MIN_SAMPLE_INTERVAL_MS)
        }

        override fun queryCpuHeadroom(): Float {
            if (Build.VERSION.SDK_INT < 36) return Float.NaN
            val shm = context.getSystemService(SystemHealthManager::class.java) ?: return Float.NaN
            return runCatching { Api36Impl.cpuHeadroom(shm) }.getOrDefault(Float.NaN)
        }

        override fun queryGpuHeadroom(): Float {
            if (Build.VERSION.SDK_INT < 36) return Float.NaN
            val shm = context.getSystemService(SystemHealthManager::class.java) ?: return Float.NaN
            return runCatching { Api36Impl.gpuHeadroom(shm) }.getOrDefault(Float.NaN)
        }

        override fun hasArrSupport(window: Window): Boolean {
            if (Build.VERSION.SDK_INT < 36) return false
            val display = runCatching { window.context.display }.getOrNull() ?: return false
            return runCatching { Api36Impl.hasArrSupport(display) }.getOrDefault(false)
        }

        override fun suggestedFrameRate(window: Window, highCategory: Boolean): Float {
            if (Build.VERSION.SDK_INT < 36) return 0f
            val display = runCatching { window.context.display }.getOrNull() ?: return 0f
            return runCatching { Api36Impl.suggestedFrameRate(display, highCategory) }
                .getOrDefault(0f)
        }
    }

    data class HeadroomSnapshot(
        val cpuHeadroom: Float = Float.NaN,
        val gpuHeadroom: Float = Float.NaN,
        val shouldThrottleBackground: Boolean = false,
        val recommendedPollIntervalMs: Long = NORMAL_POLL_INTERVAL_MS,
    )

    private var lastCpuSampleAtMs: Long = -1L
    private var lastGpuSampleAtMs: Long = -1L
    private var latestCpuHeadroom: Float = Float.NaN
    private var latestGpuHeadroom: Float = Float.NaN

    fun sampleHeadroomIfDue(
        tabCount: Int,
        hasActiveMedia: Boolean,
    ): HeadroomSnapshot {
        if (environment.sdkInt < 36 || (tabCount <= 1 && !hasActiveMedia)) {
            return HeadroomSnapshot()
        }

        val now = environment.nowElapsedRealtimeMs()
        val cpuInterval = environment.minCpuHeadroomIntervalMs()
        if (lastCpuSampleAtMs < 0L || now - lastCpuSampleAtMs >= cpuInterval) {
            lastCpuSampleAtMs = now
            latestCpuHeadroom = environment.queryCpuHeadroom()
        }

        val gpuInterval = environment.minGpuHeadroomIntervalMs()
        if (lastGpuSampleAtMs < 0L || now - lastGpuSampleAtMs >= gpuInterval) {
            lastGpuSampleAtMs = now
            latestGpuHeadroom = environment.queryGpuHeadroom()
        }

        val cpuTight = !latestCpuHeadroom.isNaN() &&
            latestCpuHeadroom in 0f..LOW_HEADROOM_THRESHOLD
        val gpuTight = !latestGpuHeadroom.isNaN() &&
            latestGpuHeadroom in 0f..LOW_HEADROOM_THRESHOLD
        val throttle = cpuTight || gpuTight

        return HeadroomSnapshot(
            cpuHeadroom = latestCpuHeadroom,
            gpuHeadroom = latestGpuHeadroom,
            shouldThrottleBackground = throttle,
            recommendedPollIntervalMs = if (throttle) {
                THROTTLED_POLL_INTERVAL_MS
            } else {
                NORMAL_POLL_INTERVAL_MS
            },
        )
    }

    fun applyAdaptiveRefreshRate(
        window: Window,
        isScrollingOrAnimating: Boolean,
    ): Float {
        if (environment.sdkInt < 36) return 0f
        if (!environment.hasArrSupport(window)) return 0f
        val suggested = environment.suggestedFrameRate(window, isScrollingOrAnimating)
        if (suggested <= 0f || suggested.isNaN()) return 0f
        runCatching {
            val attrs = window.attributes
            if (attrs.preferredRefreshRate != suggested) {
                attrs.preferredRefreshRate = suggested
                window.attributes = attrs
            }
        }
        return suggested
    }

    fun applySurfaceFilter(view: View, mode: SurfaceFilterMode) {
        if (environment.sdkInt < Build.VERSION_CODES.S) return
        if (mode == SurfaceFilterMode.None) {
            Api31Impl.clearRenderEffect(view)
            return
        }
        if (environment.sdkInt >= 36) {
            val agsl = agslShaderFor(mode)
            if (agsl != null) {
                val applied = runCatching {
                    Api36Impl.applyAgslColorFilter(view, agsl)
                    true
                }.getOrDefault(false)
                if (applied) return
            }
        }
        Api31Impl.applyColorMatrixFallback(view, colorMatrixFor(mode))
    }

    companion object {
        const val LOW_HEADROOM_THRESHOLD = 0.18f
        const val DEFAULT_MIN_SAMPLE_INTERVAL_MS = 2_000L
        const val NORMAL_POLL_INTERVAL_MS = 850L
        const val THROTTLED_POLL_INTERVAL_MS = 2_400L

        /**
         * Returns the validated AGSL `RuntimeColorFilter` shader program for [mode],
         * or `null` for [SurfaceFilterMode.None].
         */
        fun agslShaderFor(mode: SurfaceFilterMode): String? = when (mode) {
            SurfaceFilterMode.None -> null

            SurfaceFilterMode.ReaderWarm -> """
                half4 main(half4 c) {
                    half3 src = unpremul(c).rgb;
                    half3 warm = clamp(half3(src.r * 1.03, src.g * 0.96, src.b * 0.82), 0.0, 1.0);
                    return half4(warm * c.a, c.a);
                }
            """.trimIndent()

            SurfaceFilterMode.EInkGrayscale -> """
                half4 main(half4 c) {
                    half3 src = unpremul(c).rgb;
                    half luma = dot(src, half3(0.2126, 0.7152, 0.0722));
                    half crisp = clamp((luma - 0.5) * 1.12 + 0.5, 0.0, 1.0);
                    return half4(half3(crisp) * c.a, c.a);
                }
            """.trimIndent()

            SurfaceFilterMode.DarkContrast -> """
                half4 main(half4 c) {
                    half3 src = unpremul(c).rgb;
                    half3 inv = clamp(half3(1.0) - src, 0.0, 1.0);
                    half3 tuned = clamp((inv - 0.5) * 1.08 + 0.5, 0.04, 0.96);
                    return half4(tuned * c.a, c.a);
                }
            """.trimIndent()
        }

        fun colorMatrixFor(mode: SurfaceFilterMode): ColorMatrix = when (mode) {
            SurfaceFilterMode.None -> ColorMatrix()

            SurfaceFilterMode.ReaderWarm -> ColorMatrix(
                floatArrayOf(
                    1.03f, 0f, 0f, 0f, 0f,
                    0f, 0.96f, 0f, 0f, 0f,
                    0f, 0f, 0.82f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )

            SurfaceFilterMode.EInkGrayscale -> ColorMatrix().apply {
                setSaturation(0f)
            }

            SurfaceFilterMode.DarkContrast -> ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31Impl {
        fun clearRenderEffect(view: View) {
            view.setRenderEffect(null)
        }

        fun applyColorMatrixFallback(view: View, matrix: ColorMatrix) {
            val effect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
            view.setRenderEffect(effect)
        }
    }

    @RequiresApi(36)
    private object Api36Impl {
        fun cpuMinInterval(shm: SystemHealthManager): Long =
            shm.cpuHeadroomMinIntervalMillis

        fun gpuMinInterval(shm: SystemHealthManager): Long =
            shm.gpuHeadroomMinIntervalMillis

        fun cpuHeadroom(shm: SystemHealthManager): Float =
            shm.getCpuHeadroom(CpuHeadroomParams.Builder().build())

        fun gpuHeadroom(shm: SystemHealthManager): Float =
            shm.getGpuHeadroom(GpuHeadroomParams.Builder().build())

        fun hasArrSupport(display: Display): Boolean =
            display.hasArrSupport()

        fun suggestedFrameRate(display: Display, highCategory: Boolean): Float {
            val category = if (highCategory) {
                Display.FRAME_RATE_CATEGORY_HIGH
            } else {
                Display.FRAME_RATE_CATEGORY_NORMAL
            }
            return display.getSuggestedFrameRate(category)
        }

        fun applyAgslColorFilter(view: View, agslShader: String) {
            val runtimeFilter = RuntimeColorFilter(agslShader)
            view.setRenderEffect(RenderEffect.createColorFilterEffect(runtimeFilter))
        }
    }
}
