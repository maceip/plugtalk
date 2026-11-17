package dev.onionvault.ui

import android.content.Context
import android.content.SharedPreferences
import dev.onionvault.platform.SurfaceFilterMode

/**
 * Single persisted `SharedPreferences` store backing every user-configurable
 * setting in Trap (theme family, mode, hidden-service-only policy, biometric
 * unlock requirement, default zoom, default PGP key fingerprint, PiP preferences,
 * AGSL surface filter mode, and opt-in Android 17 tab handoff).
 */
class TrapPreferences(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    var themeFamily: TrapThemeFamily
        get() = TrapThemeFamily.fromKey(prefs.getString(KEY_THEME_FAMILY, null))
        set(value) {
            prefs.edit().putString(KEY_THEME_FAMILY, value.storageKey).apply()
        }

    var themeMode: TrapThemeMode
        get() = TrapThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.storageKey).apply()
        }

    var hiddenServiceOnly: Boolean
        get() = prefs.getBoolean(KEY_HIDDEN_SERVICE_ONLY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HIDDEN_SERVICE_ONLY, value).apply()
        }

    var requireBiometric: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_USER_AUTH, false)
        set(value) {
            prefs.edit().putBoolean(KEY_REQUIRE_USER_AUTH, value).apply()
        }

    var defaultZoomPercent: Int
        get() = prefs.getInt(KEY_DEFAULT_ZOOM_PERCENT, 100).coerceIn(ZOOM_MIN, ZOOM_MAX)
        set(value) {
            prefs.edit().putInt(KEY_DEFAULT_ZOOM_PERCENT, value.coerceIn(ZOOM_MIN, ZOOM_MAX)).apply()
        }

    var defaultKeyFingerprint: String
        get() = prefs.getString(KEY_DEFAULT_KEY_FINGERPRINT, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_DEFAULT_KEY_FINGERPRINT, value).apply()
        }

    var pipOnMinimizeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PIP_ON_MINIMIZE_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PIP_ON_MINIMIZE_ENABLED, value).apply()
        }

    var pipExcludePrivateTabs: Boolean
        get() = prefs.getBoolean(KEY_PIP_EXCLUDE_PRIVATE_TABS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PIP_EXCLUDE_PRIVATE_TABS, value).apply()
        }

    var surfaceFilterMode: SurfaceFilterMode
        get() = SurfaceFilterMode.fromKey(prefs.getString(KEY_SURFACE_FILTER_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_SURFACE_FILTER_MODE, value.storageKey).apply()
        }

    var handoffEnabled: Boolean
        get() = prefs.getBoolean(KEY_HANDOFF_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HANDOFF_ENABLED, value).apply()
        }

    companion object {
        const val PREFS_NAME = "trap_preferences"
        const val KEY_THEME_FAMILY = "theme_family"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_HIDDEN_SERVICE_ONLY = "hidden_service_only"
        const val KEY_REQUIRE_USER_AUTH = "require_user_authentication"
        const val KEY_DEFAULT_ZOOM_PERCENT = "default_zoom_percent"
        const val KEY_DEFAULT_KEY_FINGERPRINT = "default_key_fingerprint"
        const val KEY_PIP_ON_MINIMIZE_ENABLED = "pip_on_minimize_enabled"
        const val KEY_PIP_EXCLUDE_PRIVATE_TABS = "pip_exclude_private_tabs"
        const val KEY_SURFACE_FILTER_MODE = "surface_filter_mode"
        const val KEY_HANDOFF_ENABLED = "handoff_enabled"

        const val ZOOM_MIN = 75
        const val ZOOM_MAX = 150
        val ZOOM_PRESETS = listOf(75, 90, 100, 110, 125, 150)
    }
}
