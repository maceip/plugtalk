package dev.onionvault.platform

import android.Manifest
import android.app.Activity
import android.app.HandoffActivityData
import android.app.HandoffActivityParams
import android.companion.CompanionDeviceManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.ContactsPickerSessionContract
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.onionvault.ui.isBlankOrStartUrl
import java.util.ArrayList
import kotlin.math.roundToInt

/**
 * Android 17 (API 37) Browser Platform Capabilities:
 *   1. [LanAccessController]: `ACCESS_LOCAL_NETWORK` permission gate for explicit user
 *      RFC1918/loopback/mDNS navigations + strict cross-origin LAN probe isolation.
 *   2. [WebPickersBridge]: Zero-permission `Intent.ACTION_OPEN_EYE_DROPPER` color picker
 *      and `ContactsPickerSessionContract.ACTION_PICK_CONTACTS` session contact picker.
 *   3. [TouchpadPointerBridge]: Relative touchpad pointer capture
 *      (`View.POINTER_CAPTURE_MODE_RELATIVE`) and relative delta mouse dispatch.
 *   4. [CrossDeviceHandoffController]: Opt-in `HandoffActivityData` / `CompanionDeviceManager`
 *      continuity preserving URL and vertical scroll position while excluding blank & private tabs.
 */
class LanAccessController(
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
) {

    enum class LanDecision {
        NOT_LAN,
        ALLOW_LAN,
        PROMPT_OS_PERMISSION,
        BLOCKED_CROSS_ORIGIN_LAN_PROBE,
        BLOCKED_HIDDEN_SERVICE_ONLY,
    }

    private val allowedPublicOriginsToLan = LinkedHashSet<String>()

    @Synchronized
    fun allowOriginForLanSubresources(origin: String) {
        val clean = normalizeOrigin(origin)
        if (clean.isNotEmpty()) {
            allowedPublicOriginsToLan.add(clean)
        }
    }

    @Synchronized
    fun revokeOriginForLanSubresources(origin: String) {
        allowedPublicOriginsToLan.remove(normalizeOrigin(origin))
    }

    @Synchronized
    fun clearAllowedOrigins() {
        allowedPublicOriginsToLan.clear()
    }

    @Synchronized
    fun allowedOriginsSnapshot(): Set<String> = allowedPublicOriginsToLan.toSet()

    fun hasLocalNetworkPermission(context: Context): Boolean {
        if (sdkIntProvider() < 37) return true
        return ContextCompat.checkSelfPermission(
            context,
            PERMISSION_ACCESS_LOCAL_NETWORK,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Synchronized
    fun evaluateRequest(
        pageUrl: String,
        targetUrl: String,
        isMainFrame: Boolean,
        hiddenServiceOnly: Boolean,
        hasOsPermission: Boolean,
    ): LanDecision {
        if (!isLocalNetworkUrlOrHost(targetUrl)) {
            return LanDecision.NOT_LAN
        }
        if (hiddenServiceOnly) {
            return LanDecision.BLOCKED_HIDDEN_SERVICE_ONLY
        }
        if (!isMainFrame) {
            val pageIsLocal = isLocalNetworkUrlOrHost(pageUrl)
            if (!pageIsLocal) {
                val origin = normalizeOrigin(pageUrl)
                if (origin.isEmpty() || !allowedPublicOriginsToLan.contains(origin)) {
                    return LanDecision.BLOCKED_CROSS_ORIGIN_LAN_PROBE
                }
            }
            return if (sdkIntProvider() >= 37 && !hasOsPermission) {
                LanDecision.BLOCKED_CROSS_ORIGIN_LAN_PROBE
            } else {
                LanDecision.ALLOW_LAN
            }
        }
        // Main-frame explicit user navigation to RFC1918 / loopback / mDNS:
        return if (sdkIntProvider() >= 37 && !hasOsPermission) {
            LanDecision.PROMPT_OS_PERMISSION
        } else {
            LanDecision.ALLOW_LAN
        }
    }

    companion object {
        const val PERMISSION_ACCESS_LOCAL_NETWORK: String =
            Manifest.permission.ACCESS_LOCAL_NETWORK

        private val IPV4_PATTERN = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        fun normalizeOrigin(rawUrl: String): String {
            if (isBlankOrStartUrl(rawUrl)) return ""
            val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return ""
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase()?.trimEnd('.').orEmpty()
            if (scheme.isEmpty() || host.isEmpty()) return ""
            val port = uri.port
            return if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
        }

        /**
         * Returns `true` iff [rawUrlOrHost] targets an RFC1918 private IPv4 range
         * (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), loopback (`127.0.0.0/8`,
         * `localhost`, `::1`), link-local (`169.254.0.0/16`, `fe80::/10`), IPv6 ULA
         * (`fc00::/7`), or mDNS / home LAN suffix (`.local`, `.home.arpa`).
         */
        fun isLocalNetworkUrlOrHost(rawUrlOrHost: String): Boolean {
            if (isBlankOrStartUrl(rawUrlOrHost)) return false
            val trimmed = rawUrlOrHost.trim()
            val candidate = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            val rawHost = runCatching { Uri.parse(candidate).host.orEmpty() }
                .getOrDefault("")
                .trim()
                .trimEnd('.')
                .lowercase()
            if (rawHost.isEmpty()) return false
            if (rawHost.endsWith(".onion")) return false

            if (rawHost == "localhost" ||
                rawHost.endsWith(".localhost") ||
                rawHost.endsWith(".local") ||
                rawHost.endsWith(".home.arpa")
            ) {
                return true
            }

            val bareIpv6 = rawHost.removePrefix("[").removeSuffix("]")
            if (bareIpv6.contains(':')) {
                if (bareIpv6 == "::1" || bareIpv6 == "0:0:0:0:0:0:0:1") return true
                if (bareIpv6.startsWith("fe8") ||
                    bareIpv6.startsWith("fe9") ||
                    bareIpv6.startsWith("fea") ||
                    bareIpv6.startsWith("feb") ||
                    bareIpv6.startsWith("fc") ||
                    bareIpv6.startsWith("fd")
                ) {
                    return true
                }
                return false
            }

            val match = IPV4_PATTERN.matchEntire(rawHost) ?: return false
            val octets = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            val (a, b, _, _) = octets
            return when {
                a == 10 -> true
                a == 127 -> true
                a == 192 && b == 168 -> true
                a == 172 && b in 16..31 -> true
                a == 169 && b == 254 -> true
                else -> false
            }
        }
    }
}

/**
 * Android 17 (API 37) Zero-Permission EyeDropper & Contact Picker Intent Builders.
 */
object WebPickersBridge {

    const val ACTION_OPEN_EYE_DROPPER: String = Intent.ACTION_OPEN_EYE_DROPPER
    const val EXTRA_EYE_DROPPER_COLOR: String = Intent.EXTRA_COLOR
    const val ACTION_PICK_CONTACTS: String = ContactsPickerSessionContract.ACTION_PICK_CONTACTS

    fun buildEyeDropperIntent(): Intent = Intent(ACTION_OPEN_EYE_DROPPER)

    fun isEyeDropperAvailable(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean {
        if (sdkInt < 37) return false
        val intent = buildEyeDropperIntent()
        return intent.resolveActivity(context.packageManager) != null
    }

    fun extractEyeDropperColor(resultCode: Int, data: Intent?): Int? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        if (!data.hasExtra(EXTRA_EYE_DROPPER_COLOR)) return null
        return data.getIntExtra(EXTRA_EYE_DROPPER_COLOR, 0)
    }

    /**
     * Builds Android 17's single-session `ACTION_PICK_CONTACTS` intent granting
     * scoped field access without declaring `android.permission.READ_CONTACTS`.
     */
    fun buildScopedContactPickerIntent(
        requestedMimeDataFields: List<String> = listOf(
            "vnd.android.cursor.item/name",
            "vnd.android.cursor.item/email_v2",
        ),
        selectionLimit: Int = 1,
        matchAllFields: Boolean = false,
    ): Intent = Intent(ACTION_PICK_CONTACTS).apply {
        putStringArrayListExtra(
            ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS,
            ArrayList(requestedMimeDataFields),
        )
        putExtra(
            ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_SELECTION_LIMIT,
            selectionLimit.coerceAtLeast(1),
        )
        putExtra(
            ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_MATCH_ALL_DATA_FIELDS,
            matchAllFields,
        )
    }
}

/**
 * Android 17 (API 37) Relative Touchpad & Mouse Pointer-Lock Capture Bridge.
 */
object TouchpadPointerBridge {

    class VirtualPointerState(
        var x: Float = 0f,
        var y: Float = 0f,
        var initialized: Boolean = false,
    ) {
        fun reset() {
            initialized = false
        }
    }

    fun requestRelativePointerCapture(
        view: View,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean = runCatching {
        if (sdkInt >= 37) {
            Api37Impl.requestRelativeCapture(view)
        } else {
            view.requestPointerCapture()
        }
        true
    }.getOrDefault(false)

    fun releasePointerCapture(view: View) {
        runCatching { view.releasePointerCapture() }
    }

    fun isRelativePointerSource(source: Int): Boolean {
        val isTouchpad = (source and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
        val isMouseRelative =
            (source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
        return isTouchpad || isMouseRelative
    }

    /**
     * Translates relative touchpad / captured-pointer motion deltas (`AXIS_RELATIVE_X`,
     * `AXIS_RELATIVE_Y`) into bounded viewport coordinates instead of raw absolute
     * finger positions on foldables/tablets with trackpads.
     */
    fun accumulateRelativeMotion(
        event: MotionEvent,
        state: VirtualPointerState,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Pair<Int, Int>? {
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        if (!isRelativePointerSource(event.source)) return null
        if (event.actionMasked != MotionEvent.ACTION_MOVE &&
            event.actionMasked != MotionEvent.ACTION_HOVER_MOVE
        ) {
            return null
        }

        if (!state.initialized) {
            state.x = viewportWidth * 0.5f
            state.y = viewportHeight * 0.5f
            state.initialized = true
        }

        val relAxisX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        val relAxisY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        val isMouseRel =
            (event.source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE

        val dx = if (relAxisX != 0f || relAxisY != 0f) {
            relAxisX
        } else if (isMouseRel) {
            event.x
        } else {
            0f
        }
        val dy = if (relAxisX != 0f || relAxisY != 0f) {
            relAxisY
        } else if (isMouseRel) {
            event.y
        } else {
            0f
        }

        if (dx == 0f && dy == 0f) return null

        state.x = (state.x + dx).coerceIn(0f, (viewportWidth - 1).coerceAtLeast(0).toFloat())
        state.y = (state.y + dy).coerceIn(0f, (viewportHeight - 1).coerceAtLeast(0).toFloat())
        return state.x.roundToInt() to state.y.roundToInt()
    }

    @RequiresApi(37)
    private object Api37Impl {
        fun requestRelativeCapture(view: View) {
            view.requestPointerCapture(View.POINTER_CAPTURE_MODE_RELATIVE)
        }
    }
}

/**
 * Android 17 (API 37) Opt-In Cross-Device Tab Handoff (`HandoffActivityData` +
 * `CompanionDeviceManager` continuity).
 */
object CrossDeviceHandoffController {

    const val EXTRA_HANDOFF_URL = "dev.onionvault.handoff.URL"
    const val EXTRA_HANDOFF_SCROLL_Y = "dev.onionvault.handoff.SCROLL_Y"
    const val EXTRA_HANDOFF_TITLE = "dev.onionvault.handoff.TITLE"

    data class HandoffPayload(
        val url: String,
        val title: String,
        val scrollY: Int,
    )

    /**
     * Produces a continuity [HandoffPayload] only when [handoffEnabled] is opted in
     * and the active tab is a non-blank, non-private browsing tab.
     */
    fun buildPayloadIfEligible(
        handoffEnabled: Boolean,
        activeUrl: String,
        activeTitle: String,
        isPrivateTab: Boolean,
        scrollY: Int = 0,
    ): HandoffPayload? {
        if (!handoffEnabled) return null
        if (isPrivateTab) return null
        val trimmed = activeUrl.trim()
        if (isBlankOrStartUrl(trimmed)) return null
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return HandoffPayload(
            url = trimmed,
            title = activeTitle.trim(),
            scrollY = scrollY.coerceAtLeast(0),
        )
    }

    fun syncActivityHandoffEnabled(
        activity: Activity,
        enabled: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean {
        if (sdkInt < 37) return false
        return runCatching {
            Api37Impl.setHandoffEnabled(activity, enabled)
            true
        }.getOrDefault(false)
    }

    @RequiresApi(37)
    fun buildHandoffActivityData(
        componentName: ComponentName,
        payload: HandoffPayload,
    ): HandoffActivityData = Api37Impl.buildData(componentName, payload)

    fun extractIncomingHandoff(intent: Intent?): HandoffPayload? {
        if (intent == null) return null
        val extrasUrl = intent.getStringExtra(EXTRA_HANDOFF_URL)?.trim().orEmpty()
        if (extrasUrl.isEmpty() || isBlankOrStartUrl(extrasUrl)) {
            return null
        }
        val scrollY = intent.getIntExtra(EXTRA_HANDOFF_SCROLL_Y, 0).coerceAtLeast(0)
        val title = intent.getStringExtra(EXTRA_HANDOFF_TITLE)?.trim().orEmpty()
        return HandoffPayload(
            url = extrasUrl,
            title = title,
            scrollY = scrollY,
        )
    }

    fun hasCompanionAssociation(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return runCatching {
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                Api33Impl.hasAssociations(cdm)
            } else {
                @Suppress("DEPRECATION")
                cdm.associations.isNotEmpty()
            }
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33Impl {
        fun hasAssociations(cdm: CompanionDeviceManager): Boolean =
            cdm.myAssociations.isNotEmpty()
    }

    @RequiresApi(37)
    private object Api37Impl {
        fun setHandoffEnabled(activity: Activity, enabled: Boolean) {
            val params = HandoffActivityParams.Builder()
                .setAllowHandoffWithoutPackageInstalled(true)
                .build()
            activity.setHandoffEnabled(enabled, params)
        }

        fun buildData(
            componentName: ComponentName,
            payload: HandoffPayload,
        ): HandoffActivityData {
            val extras = PersistableBundle().apply {
                putString(EXTRA_HANDOFF_URL, payload.url)
                putString(EXTRA_HANDOFF_TITLE, payload.title)
                putInt(EXTRA_HANDOFF_SCROLL_Y, payload.scrollY)
            }
            return HandoffActivityData.Builder(componentName)
                .setExtras(extras)
                .setFallbackUri(Uri.parse(payload.url))
                .build()
        }
    }
}
