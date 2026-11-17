package dev.onionvault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Non-exported broadcast receiver (`ContextCompat.RECEIVER_NOT_EXPORTED`) handling
 * `RemoteAction` taps inside Trap's Picture-in-Picture overlay.
 */
class PipActionReceiver(
    private val onCommand: (Command) -> Unit,
) : BroadcastReceiver() {

    enum class Command(
        val action: String,
        val requestCode: Int,
    ) {
        PLAY_PAUSE(ACTION_PIP_PLAY_PAUSE, 4101),
        TOGGLE_MUTE(ACTION_PIP_TOGGLE_MUTE, 4102),
        SEEK_FORWARD(ACTION_PIP_SEEK_FORWARD, 4103),
        SCROLL_VIEWPORT(ACTION_PIP_SCROLL, 4104),
        RELOAD_OR_STOP(ACTION_PIP_RELOAD_OR_STOP, 4105),
        SWITCH_TAB(ACTION_PIP_SWITCH_TAB, 4106),
        HISTORY_BACK(ACTION_PIP_HISTORY_BACK, 4107),
        CLOSE_PIP(ACTION_PIP_CLOSE, 4108),
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val command = Command.entries.firstOrNull { it.action == action } ?: return
        onCommand(command)
    }

    companion object {
        const val ACTION_PIP_PLAY_PAUSE = "dev.onionvault.pip.ACTION_PLAY_PAUSE"
        const val ACTION_PIP_TOGGLE_MUTE = "dev.onionvault.pip.ACTION_TOGGLE_MUTE"
        const val ACTION_PIP_SEEK_FORWARD = "dev.onionvault.pip.ACTION_SEEK_FORWARD"
        const val ACTION_PIP_SCROLL = "dev.onionvault.pip.ACTION_SCROLL"
        const val ACTION_PIP_RELOAD_OR_STOP = "dev.onionvault.pip.ACTION_RELOAD_OR_STOP"
        const val ACTION_PIP_SWITCH_TAB = "dev.onionvault.pip.ACTION_SWITCH_TAB"
        const val ACTION_PIP_HISTORY_BACK = "dev.onionvault.pip.ACTION_HISTORY_BACK"
        const val ACTION_PIP_CLOSE = "dev.onionvault.pip.ACTION_CLOSE"

        fun intentFilter(): IntentFilter = IntentFilter().apply {
            Command.entries.forEach { addAction(it.action) }
        }
    }
}
