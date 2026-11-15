package dev.onionvault.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Single persisted `SharedPreferences` store backing every user-configurable
 * setting in Trap (theme family, mode, hidden-service-only policy, biometric
 * unlock requirement, default zoom, and default PGP key fingerprint).
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

    companion object {
        const val PREFS_NAME = "trap_preferences"
        const val KEY_THEME_FAMILY = "theme_family"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_HIDDEN_SERVICE_ONLY = "hidden_service_only"
        const val KEY_REQUIRE_USER_AUTH = "require_user_authentication"
        const val KEY_DEFAULT_ZOOM_PERCENT = "default_zoom_percent"
        const val KEY_DEFAULT_KEY_FINGERPRINT = "default_key_fingerprint"

        const val ZOOM_MIN = 75
        const val ZOOM_MAX = 150
        val ZOOM_PRESETS = listOf(75, 90, 100, 110, 125, 150)
    }
}
