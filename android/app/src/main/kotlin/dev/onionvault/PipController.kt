package dev.onionvault

import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Rational
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import dev.onionvault.ui.isBlankOrStartUrl
import kotlin.math.roundToInt

/**
 * State-driven Picture-in-Picture (PiP) controller and human-centric overlay action builder.
 *
 * Responsibilities:
 *   1. Evaluates runtime PiP eligibility across hardware capability
 *      ([PackageManager.FEATURE_PICTURE_IN_PICTURE]), per-UID AppOp mode
 *      ([AppOpsManager.OPSTR_PICTURE_IN_PICTURE]), `pip_on_minimize_enabled` user
 *      preference, active non-blank tab presence, and private-tab exclusion.
 *   2. Proactively pushes [PictureInPictureParams] via [Activity.setPictureInPictureParams]
 *      on API 31+ (`setAutoEnterEnabled`, `setSeamlessResizeEnabled`, `setSourceRectHint`,
 *      dynamic `setAspectRatio`, and API 33+ `setExpandedAspectRatio` on large screens).
 *   3. Restricts legacy [Activity.enterPictureInPictureMode] inside `onUserLeaveHint()`
 *      strictly to API 29–30 (`Build.VERSION.SDK_INT < 31`).
 *   4. Computes zero-flicker chrome visibility from API 35+
 *      [PictureInPictureUiState.isTransitioningToPip] and [Activity.isInPictureInPictureMode].
 *   5. Adapts PiP overlay metadata (`setTitle`, `setSubtitle`) and the 3 [RemoteAction]
 *      slots + API 33+ `setCloseAction` dynamically between Media/WebRTC mode and
 *      Document/Live-Monitor mode.
 */
class PipController(
    private val environment: Environment,
    private val defaultContext: Context? = null,
) {

    constructor(context: Context) : this(AndroidEnvironment(context), context.applicationContext)

    interface Environment {
        val sdkInt: Int
        val packageName: String
        fun hasPipFeature(): Boolean
        fun hasExpandedPipFeature(): Boolean
        fun isSmallestWidthAtLeast600Dp(): Boolean
        fun isPipAppOpAllowed(): Boolean
    }

    private class AndroidEnvironment(
        private val context: Context,
    ) : Environment {
        override val sdkInt: Int
            get() = Build.VERSION.SDK_INT

        override val packageName: String
            get() = context.packageName

        override fun hasPipFeature(): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

        override fun hasExpandedPipFeature(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_EXPANDED_PICTURE_IN_PICTURE)
            } else {
                false
            }

        override fun isSmallestWidthAtLeast600Dp(): Boolean =
            context.resources.configuration.smallestScreenWidthDp >= 600

        @Suppress("DEPRECATION")
        override fun isPipAppOpAllowed(): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = runCatching {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                    Process.myUid(),
                    context.packageName,
                )
            }.getOrDefault(AppOpsManager.MODE_IGNORED)
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }

    enum class OverlayMode {
        MEDIA_WEBRTC,
        DOCUMENT_MONITOR,
    }

    data class PipActionSpec(
        val command: PipActionReceiver.Command,
        @DrawableRes val iconRes: Int,
        val title: String,
        val contentDescription: String,
        val enabled: Boolean = true,
    )

    data class OverlayModel(
        val mode: OverlayMode,
        val title: String,
        val subtitle: String,
        val actions: List<PipActionSpec>,
        val closeAction: PipActionSpec,
    )

    data class PipSnapshot(
        val pipOnMinimizeEnabled: Boolean = true,
        val pipExcludePrivateTabs: Boolean = true,
        val crashScreenVisible: Boolean = false,
        val activeUrl: String = "about:blank",
        val activeTitle: String = "",
        val isActiveTabPrivate: Boolean = false,
        val isFullscreenVideo: Boolean = false,
        val hasActiveVideo: Boolean = false,
        val isPlayingAudio: Boolean = false,
        val hasWebRtcAudio: Boolean = false,
        val isMediaPausedByUser: Boolean = false,
        val isAudioMuted: Boolean = false,
        val isLoading: Boolean = false,
        val canGoBack: Boolean = false,
        val atDocumentBottom: Boolean = false,
        val tabCount: Int = 1,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val sourceRectHint: Rect? = null,
    )

    data class Eligibility(
        val eligible: Boolean,
        val hasPipFeature: Boolean,
        val isAppOpAllowed: Boolean,
        val hasActiveNonBlankTab: Boolean,
        val excludedPrivateTab: Boolean,
    )

    fun isDevicePipSupported(): Boolean = environment.hasPipFeature()

    fun isPipPermissionGranted(): Boolean =
        environment.hasPipFeature() && environment.isPipAppOpAllowed()

    fun evaluateEligibility(snapshot: PipSnapshot): Eligibility {
        val hasFeature = environment.hasPipFeature()
        val appOpAllowed = hasFeature && environment.isPipAppOpAllowed()
        val nonBlankTab = !snapshot.crashScreenVisible && !isBlankOrStartUrl(snapshot.activeUrl)
        val excludedPrivate = snapshot.pipExcludePrivateTabs && snapshot.isActiveTabPrivate
        val eligible = hasFeature &&
            appOpAllowed &&
            snapshot.pipOnMinimizeEnabled &&
            nonBlankTab &&
            !excludedPrivate
        return Eligibility(
            eligible = eligible,
            hasPipFeature = hasFeature,
            isAppOpAllowed = appOpAllowed,
            hasActiveNonBlankTab = nonBlankTab,
            excludedPrivateTab = excludedPrivate,
        )
    }

    fun buildPipSettingsIntent(pkg: String = environment.packageName): Intent =
        Intent(
            ACTION_PICTURE_IN_PICTURE_SETTINGS,
            Uri.fromParts("package", pkg, null),
        )

    fun computeAspectRatio(
        hasActiveVideo: Boolean,
        videoWidth: Int,
        videoHeight: Int,
    ): Rational {
        if (!hasActiveVideo) {
            return DEFAULT_DOCUMENT_ASPECT_RATIO
        }
        if (videoWidth <= 0 || videoHeight <= 0) {
            return DEFAULT_VIDEO_ASPECT_RATIO
        }
        val rawRatio = videoWidth.toFloat() / videoHeight.toFloat()
        return when {
            rawRatio < MIN_ASPECT_RATIO_FLOAT -> MIN_ASPECT_RATIO
            rawRatio > MAX_ASPECT_RATIO_FLOAT -> MAX_ASPECT_RATIO
            else -> {
                val scaledWidth = (rawRatio * 1000f).roundToInt().coerceIn(419, 2390)
                Rational(scaledWidth, 1000)
            }
        }
    }

    fun computeExpandedAspectRatio(hasActiveVideo: Boolean): Rational? {
        if (environment.sdkInt < Build.VERSION_CODES.TIRAMISU) return null
        if (!environment.hasExpandedPipFeature() && !environment.isSmallestWidthAtLeast600Dp()) {
            return null
        }
        return if (hasActiveVideo) {
            EXPANDED_VIDEO_ASPECT_RATIO
        } else {
            EXPANDED_DOCUMENT_ASPECT_RATIO
        }
    }

    /**
     * Resolves window metadata (`title` + `subtitle`) and the 3 PiP [RemoteAction]
     * slot specs (`Back`, `Reload` / `Stop`, `Text Search`) + custom close action spec.
     */
    fun resolveOverlayModel(snapshot: PipSnapshot): OverlayModel {
        val subtitle = extractEtldPlusOneOrigin(snapshot.activeUrl)
        val cleanTitle = snapshot.activeTitle.trim()
            .takeIf { it.isNotEmpty() && !it.equals("about:blank", ignoreCase = true) }
            ?: subtitle.ifEmpty { "Trap" }

        val isMediaMode = snapshot.isPlayingAudio ||
            snapshot.hasActiveVideo ||
            snapshot.isFullscreenVideo ||
            snapshot.hasWebRtcAudio ||
            snapshot.isMediaPausedByUser

        val backAction = PipActionSpec(
            command = PipActionReceiver.Command.HISTORY_BACK,
            iconRes = R.drawable.ic_ov_back,
            title = "Back",
            contentDescription = "Navigate back",
            enabled = snapshot.canGoBack,
        )

        val reloadOrStopAction = if (snapshot.isLoading) {
            PipActionSpec(
                command = PipActionReceiver.Command.RELOAD_OR_STOP,
                iconRes = R.drawable.ic_ov_pip_stop,
                title = "Stop",
                contentDescription = "Abort current page load",
                enabled = true,
            )
        } else {
            PipActionSpec(
                command = PipActionReceiver.Command.RELOAD_OR_STOP,
                iconRes = R.drawable.ic_ov_reload,
                title = "Reload",
                contentDescription = "Reload current tab",
                enabled = true,
            )
        }

        val textSearchAction = PipActionSpec(
            command = PipActionReceiver.Command.TEXT_SEARCH,
            iconRes = R.drawable.ic_ov_search,
            title = "Text Search",
            contentDescription = "Search text on page",
            enabled = true,
        )

        val actions = listOf(backAction, reloadOrStopAction, textSearchAction)

        val closeSpec = PipActionSpec(
            command = PipActionReceiver.Command.CLOSE_PIP,
            iconRes = R.drawable.ic_ov_close,
            title = "Close",
            contentDescription = "Pause active media and close Picture-in-Picture",
        )

        return OverlayModel(
            mode = if (isMediaMode) OverlayMode.MEDIA_WEBRTC else OverlayMode.DOCUMENT_MONITOR,
            title = cleanTitle,
            subtitle = subtitle,
            actions = actions,
            closeAction = closeSpec,
        )
    }

    fun buildRemoteActions(
        context: Context,
        specs: List<PipActionSpec>,
    ): List<RemoteAction> = specs.take(MAX_PIP_REMOTE_ACTIONS).map { toRemoteAction(context, it) }

    fun toRemoteAction(
        context: Context,
        spec: PipActionSpec,
    ): RemoteAction {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = if (spec.command == PipActionReceiver.Command.TEXT_SEARCH) {
            val activityIntent = Intent(context, MainActivity::class.java)
                .setAction(spec.command.action)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_OPEN_TEXT_SEARCH, true)
            PendingIntent.getActivity(context, spec.command.requestCode, activityIntent, flags)
        } else {
            val broadcastIntent = Intent(spec.command.action).setPackage(context.packageName)
            PendingIntent.getBroadcast(context, spec.command.requestCode, broadcastIntent, flags)
        }
        val icon = Icon.createWithResource(context, spec.iconRes)
        return RemoteAction(icon, spec.title, spec.contentDescription, pendingIntent).apply {
            isEnabled = spec.enabled
        }
    }

    fun buildPictureInPictureParams(
        snapshot: PipSnapshot,
        actionContext: Context? = defaultContext,
    ): PictureInPictureParams {
        val eligibility = evaluateEligibility(snapshot)
        val overlay = resolveOverlayModel(snapshot)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(
                computeAspectRatio(
                    hasActiveVideo = snapshot.hasActiveVideo || snapshot.isFullscreenVideo,
                    videoWidth = snapshot.videoWidth,
                    videoHeight = snapshot.videoHeight,
                ),
            )

        snapshot.sourceRectHint
            ?.takeIf { it.width() > 0 && it.height() > 0 }
            ?.let { rect -> builder.setSourceRectHint(Rect(rect)) }

        if (actionContext != null) {
            builder.setActions(buildRemoteActions(actionContext, overlay.actions))
        }

        if (environment.sdkInt >= Build.VERSION_CODES.S) {
            // Enable seamless resize for all eligible PiP sessions so Android 12–17
            // PipTaskOrganizer keeps rendering the live Chromium SurfaceView instead of
            // masking non-video PiP transitions with a blank white splash overlay.
            Api31Impl.applyAutoEnterAndSeamlessResize(
                builder = builder,
                autoEnterEnabled = eligibility.eligible,
                seamlessResizeEnabled = eligibility.eligible,
            )
        }

        if (environment.sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            computeExpandedAspectRatio(snapshot.hasActiveVideo || snapshot.isFullscreenVideo)
                ?.let { expanded ->
                    Api33Impl.applyExpandedAspectRatio(builder, expanded)
                }
            Api33Impl.applyWindowMetadataAndCloseAction(
                builder = builder,
                title = overlay.title,
                subtitle = overlay.subtitle,
                closeAction = actionContext?.let { toRemoteAction(it, overlay.closeAction) },
            )
        }

        return builder.build()
    }

    /**
     * Proactively pushes [PictureInPictureParams] on API 31+ so gesture-navigation home
     * swipes transition smoothly without waiting on `onUserLeaveHint()`.
     */
    fun syncPictureInPictureParams(
        activity: Activity,
        snapshot: PipSnapshot,
        sink: ((PictureInPictureParams) -> Unit)? = null,
    ): Boolean {
        if (!environment.hasPipFeature()) return false
        if (environment.sdkInt < Build.VERSION_CODES.S) return false
        val params = buildPictureInPictureParams(snapshot, activity)
        return runCatching {
            if (sink != null) {
                sink(params)
            } else {
                activity.setPictureInPictureParams(params)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Legacy fallback strictly for API 29–30 (`Build.VERSION.SDK_INT < 31`).
     * Never invokes [Activity.enterPictureInPictureMode] on API 31+.
     */
    fun handleUserLeaveHint(
        activity: Activity,
        snapshot: PipSnapshot,
        enterPipInvoker: ((PictureInPictureParams) -> Boolean)? = null,
    ): Boolean {
        if (environment.sdkInt >= Build.VERSION_CODES.S) {
            return false
        }
        val eligibility = evaluateEligibility(snapshot)
        if (!eligibility.eligible) {
            return false
        }
        val params = buildPictureInPictureParams(snapshot, activity)
        return runCatching {
            enterPipInvoker?.invoke(params) ?: activity.enterPictureInPictureMode(params)
        }.getOrDefault(false)
    }

    @RequiresApi(35)
    fun isTransitioningToPipApi35(uiState: PictureInPictureUiState): Boolean =
        Api35Impl.isTransitioningToPip(uiState)

    companion object {
        const val ACTION_PICTURE_IN_PICTURE_SETTINGS =
            "android.settings.PICTURE_IN_PICTURE_SETTINGS"
        const val EXTRA_OPEN_TEXT_SEARCH = "dev.onionvault.extra.OPEN_TEXT_SEARCH"
        const val MAX_PIP_REMOTE_ACTIONS = 3

        /** Subtle window/surface translucency applied while in Picture-in-Picture mode. */
        const val PIP_WINDOW_ALPHA = 0.88f
        const val FULLSCREEN_WINDOW_ALPHA = 1.0f
        const val MIN_PIP_SCALE_FACTOR = 0.25f

        val MIN_ASPECT_RATIO = Rational(100, 239)
        val MAX_ASPECT_RATIO = Rational(239, 100)
        val DEFAULT_VIDEO_ASPECT_RATIO = Rational(16, 9)
        val DEFAULT_DOCUMENT_ASPECT_RATIO = Rational(3, 4)

        /** Expanded PiP ratios outside `[1/2.39, 2.39]` for API 33+ large screens/tablets. */
        val EXPANDED_VIDEO_ASPECT_RATIO = Rational(5, 2)
        val EXPANDED_DOCUMENT_ASPECT_RATIO = Rational(2, 5)

        private const val MIN_ASPECT_RATIO_FLOAT = 100f / 239f
        private const val MAX_ASPECT_RATIO_FLOAT = 239f / 100f

        private val TWO_PART_PUBLIC_SUFFIXES = setOf(
            "co.uk", "org.uk", "ac.uk", "gov.uk",
            "co.jp", "ne.jp", "or.jp",
            "com.au", "net.au", "org.au", "edu.au", "gov.au",
            "com.br", "org.br",
            "co.in", "org.in",
            "co.kr", "or.kr",
            "co.nz", "org.nz",
            "co.za", "org.za",
            "com.mx", "com.sg", "com.hk", "com.tw",
        )

        private val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        fun resolveChromeHidden(
            isInPictureInPictureMode: Boolean,
            isTransitioningToPip: Boolean,
        ): Boolean = isInPictureInPictureMode || isTransitioningToPip

        fun resolveWindowAlpha(isInPictureInPictureMode: Boolean): Float =
            if (isInPictureInPictureMode) PIP_WINDOW_ALPHA else FULLSCREEN_WINDOW_ALPHA

        /**
         * Computes the proportional zoom scale factor (`pipWidth / fullViewportWidth`) so the
         * full desktop/mobile webpage width fits inside the miniaturized PiP window without
         * clipping or horizontal overflow.
         */
        fun computePipScaleFactor(
            viewportWidthPx: Int,
            fullViewportWidthPx: Int,
        ): Float {
            if (viewportWidthPx <= 0 || fullViewportWidthPx <= 0) return 1.0f
            if (viewportWidthPx >= fullViewportWidthPx) return 1.0f
            return (viewportWidthPx.toFloat() / fullViewportWidthPx.toFloat())
                .coerceIn(MIN_PIP_SCALE_FACTOR, 1.0f)
        }

        /**
         * Extracts a clean `eTLD+1` origin display string (including `.onion` v3 apex
         * domains, multi-part public suffixes like `.co.uk`, and IP/local hosts) for
         * API 33+ PiP window subtitles.
         */
        fun extractEtldPlusOneOrigin(rawUrl: String): String {
            if (isBlankOrStartUrl(rawUrl)) return ""
            val trimmed = rawUrl.trim()
            val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val rawHost = runCatching { Uri.parse(candidate).host.orEmpty() }
                .getOrDefault("")
                .trim()
                .trimEnd('.')
                .lowercase()
            if (rawHost.isEmpty()) return ""
            if (rawHost.startsWith("[") && rawHost.endsWith("]")) {
                return rawHost
            }
            if (IPV4_REGEX.matches(rawHost)) {
                return rawHost
            }
            val labels = rawHost.split('.').filter { it.isNotEmpty() }
            if (labels.size <= 2) {
                return labels.joinToString(".")
            }
            val lastTwo = "${labels[labels.size - 2]}.${labels.last()}"
            if (TWO_PART_PUBLIC_SUFFIXES.contains(lastTwo) && labels.size >= 3) {
                return "${labels[labels.size - 3]}.$lastTwo"
            }
            return lastTwo
        }

        fun computeViewportWindowRect(view: View): Rect? {
            if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
                return null
            }
            val location = IntArray(2)
            view.getLocationInWindow(location)
            val left = location[0].coerceAtLeast(0)
            val top = location[1].coerceAtLeast(0)
            val right = location[0] + view.width
            val bottom = location[1] + view.height
            if (right <= left || bottom <= top) {
                return null
            }
            return Rect(left, top, right, bottom)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31Impl {
        fun applyAutoEnterAndSeamlessResize(
            builder: PictureInPictureParams.Builder,
            autoEnterEnabled: Boolean,
            seamlessResizeEnabled: Boolean,
        ) {
            builder.setAutoEnterEnabled(autoEnterEnabled)
            builder.setSeamlessResizeEnabled(seamlessResizeEnabled)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33Impl {
        fun applyExpandedAspectRatio(
            builder: PictureInPictureParams.Builder,
            expandedRatio: Rational,
        ) {
            builder.setExpandedAspectRatio(expandedRatio)
        }

        fun applyWindowMetadataAndCloseAction(
            builder: PictureInPictureParams.Builder,
            title: String,
            subtitle: String,
            closeAction: RemoteAction?,
        ) {
            if (title.isNotBlank()) {
                builder.setTitle(title)
            }
            if (subtitle.isNotBlank()) {
                builder.setSubtitle(subtitle)
            }
            if (closeAction != null) {
                builder.setCloseAction(closeAction)
            }
        }
    }

    @RequiresApi(35)
    private object Api35Impl {
        fun isTransitioningToPip(state: PictureInPictureUiState): Boolean =
            state.isTransitioningToPip
    }
}
