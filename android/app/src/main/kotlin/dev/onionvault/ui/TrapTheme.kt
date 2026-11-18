package dev.onionvault.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.onionvault.R

/**
 * The two selectable visual families of Trap.
 */
enum class TrapThemeFamily(val storageKey: String, val displayName: String) {
    SecureDrop("securedrop", "SecureDrop"),
    NeonGraffiti("neon_graffiti", "Neon Graffiti"),
    ;

    companion object {
        fun fromKey(key: String?): TrapThemeFamily =
            entries.firstOrNull { it.storageKey == key } ?: SecureDrop
    }
}

/**
 * Luminance mode of Trap's chrome.
 */
enum class TrapThemeMode(val storageKey: String, val displayName: String) {
    Dark("dark", "Dark"),
    Light("light", "Light"),
    ;

    companion object {
        fun fromKey(key: String?): TrapThemeMode =
            entries.firstOrNull { it.storageKey == key } ?: Dark
    }
}

/**
 * Semantic color tokens resolved for a `(TrapThemeFamily, TrapThemeMode)` pair.
 */
@Immutable
data class TrapColors(
    val family: TrapThemeFamily,
    val mode: TrapThemeMode,
    val background: Color,
    val surface: Color,
    val surface2: Color,
    val control: Color,
    val border: Color,
    val cardBorder: Color,
    val amber: Color,
    val goFill: Color,
    val onGoFill: Color,
    val teal: Color,
    val tealContainer: Color,
    val onTealFill: Color,
    val green: Color,
    val greenContainer: Color,
    val greenBorder: Color,
    val danger: Color,
    val dangerContainer: Color,
    val purple: Color,
    val cream: Color,
    val textPrimary: Color,
    val textBody: Color,
    val textMuted: Color,
    val textSubtle: Color,
    val pgpEncryptFill: Color = Color(0xFFE8A33D),
    val pgpEncryptInk: Color = Color(0xFF1A1510),
    val pgpDecryptFill: Color = Color(0xFF2EC4B0),
    val pgpDecryptInk: Color = Color(0xFF062A26),
    val pgpPubKeyFill: Color = Color(0xFF3ECF6A),
    val pgpPubKeyInk: Color = Color(0xFF0B2A16),
) {
    val isDark: Boolean get() = mode == TrapThemeMode.Dark

    /**
     * RGBA neon sprites from the Neon Graffiti Board carry alpha-blended dark-surface glow.
     * They are rendered untinted via [Image] in Neon Graffiti Dark mode, and replaced by
     * crisp 2dp vectors in SecureDrop and Light modes so glow alpha is never destroyed.
     */
    val useNeonSprites: Boolean
        get() = family == TrapThemeFamily.NeonGraffiti && mode == TrapThemeMode.Dark
}

fun trapColorsFor(
    family: TrapThemeFamily,
    mode: TrapThemeMode,
): TrapColors = when (family) {
    TrapThemeFamily.SecureDrop -> when (mode) {
        TrapThemeMode.Dark -> TrapColors(
            family = family,
            mode = mode,
            background = SecureDropPalette.Bg,
            surface = SecureDropPalette.Surface,
            surface2 = SecureDropPalette.Surface2,
            control = SecureDropPalette.Control,
            border = SecureDropPalette.Line,
            cardBorder = SecureDropPalette.CardBorder,
            amber = SecureDropPalette.Amber,
            goFill = SecureDropPalette.Amber,
            onGoFill = SecureDropPalette.OnAmber,
            teal = SecureDropPalette.Teal,
            tealContainer = SecureDropPalette.TealSurface,
            onTealFill = SecureDropPalette.OnTeal,
            green = SecureDropPalette.Green,
            greenContainer = SecureDropPalette.GreenSurface,
            greenBorder = SecureDropPalette.GreenBorder,
            danger = SecureDropPalette.Danger,
            dangerContainer = SecureDropPalette.DangerSurface,
            purple = OnionPalette.Purple,
            cream = OnionPalette.Cream,
            textPrimary = SecureDropPalette.Text,
            textBody = SecureDropPalette.TextBody,
            textMuted = SecureDropPalette.Muted,
            textSubtle = SecureDropPalette.Muted2,
        )

        TrapThemeMode.Light -> TrapColors(
            family = family,
            mode = mode,
            background = SecureDropPalette.LightBg,
            surface = SecureDropPalette.LightSurface,
            surface2 = SecureDropPalette.LightSurface2,
            control = SecureDropPalette.LightControl,
            border = SecureDropPalette.LightLine,
            cardBorder = SecureDropPalette.LightCardBorder,
            amber = SecureDropPalette.LightAmber,
            goFill = SecureDropPalette.Amber,
            onGoFill = SecureDropPalette.OnAmber,
            teal = SecureDropPalette.LightTeal,
            tealContainer = SecureDropPalette.LightTealSurface,
            onTealFill = SecureDropPalette.OnTeal,
            green = SecureDropPalette.LightGreen,
            greenContainer = SecureDropPalette.LightGreenSurface,
            greenBorder = SecureDropPalette.LightGreenBorder,
            danger = SecureDropPalette.LightDanger,
            dangerContainer = SecureDropPalette.LightDangerSurface,
            purple = OnionPalette.PurpleDeep,
            cream = OnionPalette.Cream,
            textPrimary = SecureDropPalette.LightText,
            textBody = SecureDropPalette.LightTextBody,
            textMuted = SecureDropPalette.LightMuted,
            textSubtle = SecureDropPalette.LightMuted2,
        )
    }

    TrapThemeFamily.NeonGraffiti -> when (mode) {
        TrapThemeMode.Dark -> TrapColors(
            family = family,
            mode = mode,
            background = OnionPalette.Surface,
            surface = OnionPalette.SurfaceRaised,
            surface2 = OnionPalette.SurfaceCanvas,
            control = OnionPalette.SurfaceHigh,
            border = OnionPalette.Hairline,
            cardBorder = OnionPalette.CardBorder,
            amber = OnionPalette.Amber,
            goFill = SecureDropPalette.Amber,
            onGoFill = OnionPalette.OnAmber,
            teal = OnionPalette.Teal,
            tealContainer = Color(0xFF0D2B29),
            onTealFill = OnionPalette.OnTeal,
            green = OnionPalette.Mint,
            greenContainer = Color(0xFF0E2E26),
            greenBorder = Color(0xFF1B6B52),
            danger = OnionPalette.Pink,
            dangerContainer = Color(0xFF3A1E2E),
            purple = OnionPalette.Purple,
            cream = OnionPalette.Cream,
            textPrimary = OnionPalette.TextPrimary,
            textBody = OnionPalette.TextBody,
            textMuted = OnionPalette.TextMuted,
            textSubtle = OnionPalette.TextSubtle,
        )

        TrapThemeMode.Light -> TrapColors(
            family = family,
            mode = mode,
            background = Color(0xFFF3F4F8),
            surface = Color(0xFFFFFFFF),
            surface2 = Color(0xFFEAECEF),
            control = Color(0xFFE4E7EE),
            border = Color(0xFFC5CAD6),
            cardBorder = Color(0xFFD4D8E2),
            amber = Color(0xFFC46812),
            goFill = SecureDropPalette.Amber,
            onGoFill = OnionPalette.OnAmber,
            teal = Color(0xFF007B68),
            tealContainer = Color(0xFFD9F5F0),
            onTealFill = OnionPalette.OnTeal,
            green = Color(0xFF157A3E),
            greenContainer = Color(0xFFDCF5E6),
            greenBorder = Color(0xFF8AD4A8),
            danger = Color(0xFFBF2667),
            dangerContainer = Color(0xFFFCE4EE),
            purple = OnionPalette.PurpleDeep,
            cream = OnionPalette.Cream,
            textPrimary = OnionPalette.Surface,
            textBody = Color(0xFF232834),
            textMuted = Color(0xFF525866),
            textSubtle = Color(0xFF6E7585),
        )
    }
}

private val LocalTrapColors = staticCompositionLocalOf {
    trapColorsFor(TrapThemeFamily.SecureDrop, TrapThemeMode.Dark)
}

object TrapTheme {
    val colors: TrapColors
        @Composable
        @ReadOnlyComposable
        get() = LocalTrapColors.current
}

/**
 * Wraps content in [TrapTheme] and synchronizes Material3 dialog/control colors.
 */
@Composable
fun TrapTheme(
    family: TrapThemeFamily = TrapThemeFamily.SecureDrop,
    mode: TrapThemeMode = TrapThemeMode.Dark,
    content: @Composable () -> Unit,
) {
    val trapColors = trapColorsFor(family, mode)
    val materialScheme = if (trapColors.isDark) {
        darkColorScheme(
            primary = trapColors.amber,
            onPrimary = trapColors.onGoFill,
            secondary = trapColors.teal,
            onSecondary = trapColors.onTealFill,
            tertiary = trapColors.green,
            background = trapColors.background,
            onBackground = trapColors.textPrimary,
            surface = trapColors.surface,
            onSurface = trapColors.textPrimary,
            surfaceVariant = trapColors.control,
            onSurfaceVariant = trapColors.textMuted,
            outline = trapColors.border,
            error = trapColors.danger,
        )
    } else {
        lightColorScheme(
            primary = trapColors.amber,
            onPrimary = Color.White,
            secondary = trapColors.teal,
            onSecondary = Color.White,
            tertiary = trapColors.green,
            background = trapColors.background,
            onBackground = trapColors.textPrimary,
            surface = trapColors.surface,
            onSurface = trapColors.textPrimary,
            surfaceVariant = trapColors.control,
            onSurfaceVariant = trapColors.textMuted,
            outline = trapColors.border,
            error = trapColors.danger,
        )
    }

    CompositionLocalProvider(LocalTrapColors provides trapColors) {
        MaterialTheme(
            colorScheme = materialScheme,
            content = content,
        )
    }
}

/**
 * Semantic iconography for Trap.
 * Never includes unbacked glyphs (wallet, monero, usd, microphone, autofill).
 */
enum class TrapGlyph(
    val vectorRes: Int,
    val neonRes: Int? = null,
) {
    Back(R.drawable.ic_ov_back, R.drawable.ic_neon_back),
    Forward(R.drawable.ic_ov_forward, R.drawable.ic_neon_forward),
    Reload(R.drawable.ic_ov_reload, R.drawable.ic_neon_reload),
    NewTab(R.drawable.ic_ov_new_tab, R.drawable.ic_neon_new_tab),
    Tabs(R.drawable.ic_ov_tabs, R.drawable.ic_neon_new_partial_tab),
    Menu(R.drawable.ic_ov_menu, R.drawable.ic_neon_menu),
    Close(R.drawable.ic_ov_close, R.drawable.ic_neon_cancel),
    Shield(R.drawable.ic_ov_shield, R.drawable.ic_neon_shield),
    Home(R.drawable.ic_ov_home, R.drawable.ic_neon_home),
    Key(R.drawable.ic_ov_keys, R.drawable.ic_neon_key),
    NewKey(R.drawable.ic_ov_new_key, null),
    Encrypt(R.drawable.ic_ov_encrypt, R.drawable.ic_neon_sign_pgp),
    Decrypt(R.drawable.ic_ov_decrypt, R.drawable.ic_neon_decrypt_pgp),
    Sign(R.drawable.ic_ov_sign, R.drawable.ic_neon_sign_pgp),
    Import(R.drawable.ic_ov_import, null),
    Insert(R.drawable.ic_ov_insert, null),
    Copy(R.drawable.ic_ov_copy, null),
    Delete(R.drawable.ic_ov_delete, null),
    Warning(R.drawable.ic_ov_warning, null),
    Settings(R.drawable.ic_ov_settings, null),
    Legal(R.drawable.ic_ov_legal, null),
    ZoomIn(R.drawable.ic_ov_zoom_in, R.drawable.ic_neon_zoom_in),
    ZoomOut(R.drawable.ic_ov_zoom_out, R.drawable.ic_neon_zoom_out),
    Onion(R.drawable.ic_ov_onion, R.drawable.ic_neon_onion),
    RefreshIdentity(R.drawable.ic_ov_refresh_id, R.drawable.ic_neon_update),
    Search(R.drawable.ic_ov_search, null),
}

/**
 * Renders [glyph] as an untinted RGBA [Image] in Neon Graffiti Dark mode (preserving glow alpha),
 * and as a crisp 2dp tinted vector [Icon] in SecureDrop and Light modes.
 */
@Composable
fun TrapIcon(
    glyph: TrapGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    contentDescription: String? = null,
    enabled: Boolean = true,
    forceVector: Boolean = false,
) {
    val colors = TrapTheme.colors
    val alpha = if (enabled) 1f else 0.38f
    if (!forceVector && colors.useNeonSprites && glyph.neonRes != null) {
        Image(
            painter = painterResource(glyph.neonRes),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .alpha(alpha),
        )
    } else {
        Icon(
            painter = painterResource(glyph.vectorRes),
            contentDescription = contentDescription,
            tint = tint.copy(alpha = tint.alpha * alpha),
            modifier = modifier.size(size),
        )
    }
}
