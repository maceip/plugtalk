package dev.onionvault.platform

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * Android 15+ (API 35) Screen Recording Detection & Per-View Sensitive Masking.
 *
 *   1. Registers [WindowManager.addScreenRecordingCallback] in `onStart()` (inspecting
 *      its immediate integer return value so pre-existing recordings are caught on
 *      launch) and unregisters in `onStop()`.
 *   2. Applies [View.CONTENT_SENSITIVITY_SENSITIVE] selectively to Incognito browser
 *      surfaces, PGP/keyring modals, and credential/passphrase sheets so `MediaProjection`
 *      redacts only sensitive views without forcing a blanket `FLAG_SECURE` block.
 */
class ScreenRecordingGuard(
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val registrar: CallbackRegistrar? = null,
    private val onStateChanged: (Boolean) -> Unit = {},
) {

    interface CallbackRegistrar {
        fun register(executor: Executor, callback: Consumer<Int>): Int
        fun unregister(callback: Consumer<Int>)
    }

    private class WindowManagerRegistrar(
        private val context: Context,
    ) : CallbackRegistrar {
        private val windowManager: WindowManager?
            get() = context.getSystemService(WindowManager::class.java)

        @RequiresApi(35)
        override fun register(executor: Executor, callback: Consumer<Int>): Int {
            val wm = windowManager ?: return STATE_NOT_VISIBLE
            return Api35Impl.addCallback(wm, executor, callback)
        }

        @RequiresApi(35)
        override fun unregister(callback: Consumer<Int>) {
            val wm = windowManager ?: return
            Api35Impl.removeCallback(wm, callback)
        }
    }

    constructor(
        context: Context,
        onStateChanged: (Boolean) -> Unit,
    ) : this(
        sdkIntProvider = { Build.VERSION.SDK_INT },
        registrar = WindowManagerRegistrar(context),
        onStateChanged = onStateChanged,
    )

    var isRecordingVisible: Boolean = false
        private set

    private var activeCallback: Consumer<Int>? = null

    fun onStart(context: Context) {
        if (sdkIntProvider() < 35) return
        if (activeCallback != null) return
        val effectiveRegistrar = registrar ?: WindowManagerRegistrar(context)
        val callback = Consumer<Int> { state ->
            updateState(state == STATE_VISIBLE)
        }
        val initialState = runCatching {
            effectiveRegistrar.register(ContextCompat.getMainExecutor(context), callback)
        }.getOrElse {
            return
        }
        activeCallback = callback
        updateState(initialState == STATE_VISIBLE)
    }

    fun onStop(context: Context) {
        if (sdkIntProvider() < 35) return
        val callback = activeCallback ?: return
        activeCallback = null
        val effectiveRegistrar = registrar ?: WindowManagerRegistrar(context)
        runCatching { effectiveRegistrar.unregister(callback) }
        updateState(false)
    }

    private fun updateState(recording: Boolean) {
        if (isRecordingVisible != recording) {
            isRecordingVisible = recording
            onStateChanged(recording)
        } else {
            onStateChanged(recording)
        }
    }

    companion object {
        /** Matches [WindowManager.SCREEN_RECORDING_STATE_NOT_VISIBLE] (0). */
        const val STATE_NOT_VISIBLE = 0

        /** Matches [WindowManager.SCREEN_RECORDING_STATE_VISIBLE] (1). */
        const val STATE_VISIBLE = 1

        fun applyContentSensitivity(
            view: View,
            sensitive: Boolean = true,
            sdkInt: Int = Build.VERSION.SDK_INT,
        ) {
            if (sdkInt >= 35) {
                Api35Impl.setContentSensitivity(view, sensitive)
            }
        }
    }

    @RequiresApi(35)
    private object Api35Impl {
        fun addCallback(
            wm: WindowManager,
            executor: Executor,
            callback: Consumer<Int>,
        ): Int = wm.addScreenRecordingCallback(executor, callback)

        fun removeCallback(
            wm: WindowManager,
            callback: Consumer<Int>,
        ) {
            wm.removeScreenRecordingCallback(callback)
        }

        fun setContentSensitivity(view: View, sensitive: Boolean) {
            view.contentSensitivity = if (sensitive) {
                View.CONTENT_SENSITIVITY_SENSITIVE
            } else {
                View.CONTENT_SENSITIVITY_AUTO
            }
        }
    }
}

/**
 * Marks the enclosing Compose window/sheet [View] as [View.CONTENT_SENSITIVITY_SENSITIVE]
 * on Android 15+ (API 35+) while mounted, restoring `AUTO` on disposal.
 */
@Composable
fun SensitiveContentEffect(sensitive: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(view, sensitive) {
        ScreenRecordingGuard.applyContentSensitivity(view, sensitive = sensitive)
        onDispose {
            if (sensitive) {
                ScreenRecordingGuard.applyContentSensitivity(view, sensitive = false)
            }
        }
    }
}
