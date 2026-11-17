package dev.onionvault.ui

import androidx.compose.ui.graphics.Color

/**
 * Brand token palettes for Trap's two theme families:
 * 1. [SecureDrop]: Warm espresso, amber, teal, and green tokens (`App.tsx` / `index.css`).
 * 2. [OnionPalette] (`NeonGraffiti`): Neon Graffiti Browser Board swatches.
 */
object OnionPalette {
    // V3 Cutout & Neon Graffiti Swatches
    val IconBackdrop = Color(0xFF0D080B)
    val IconExtrude = Color(0xFF361821)
    val Teal = Color(0xFF35DCF0)
    val TealDeep = Color(0xFF009DB5)
    val Mint = Color(0xFF5BCEF1)
    val Pink = Color(0xFFF27AF3)
    val PinkDeep = Color(0xFFB338B8)
    val Amber = Color(0xFFE8933E)
    val Cream = Color(0xFFFBD4A9)
    val Purple = Color(0xFFB565E8)
    val PurpleDeep = Color(0xFF6B2F96)

    // V3 Cutout Obsidian-Plum Dark Surfaces & Ink
    val Surface = Color(0xFF120E14)
    val SurfaceRaised = Color(0xFF1B151F)
    val SurfaceCanvas = Color(0xFF0D080B)
    val SurfaceHigh = Color(0xFF281F2E)
    val Hairline = Color(0xFF3E3046)
    val CardBorder = Color(0xFF32263A)
    val TextPrimary = Color(0xFFF5EEF4)
    val TextBody = Color(0xFFE7DFEA)
    val TextMuted = Color(0xFFB6A8BF)
    val TextSubtle = Color(0xFF8B7B96)
    val OnAmber = Color(0xFF2A1608)
    val OnTeal = Color(0xFF042026)
    val Good = Color(0xFF5BCEF1)
    val Warn = Color(0xFFE8933E)
    val Bad = Color(0xFFF27AF3)
}

object SecureDropPalette {
    // SecureDrop Browser Chrome Fixed (`index.css` / `App.tsx`)
    val Bg = Color(0xFF0F0C09)
    val Surface = Color(0xFF1A1510)
    val Surface2 = Color(0xFF14100C)
    val PageCanvas = Color(0xFF17120D)
    val Control = Color(0xFF2A211A)
    val Line = Color(0xFF4A3A2A)
    val CardBorder = Color(0xFF3A2D1F)

    val Amber = Color(0xFFE8A33D)
    val AmberActive = Color(0xFFF5B95A)
    val OnAmber = Color(0xFF1A1510)

    val Teal = Color(0xFF2EC4B0)
    val TealDim = Color(0xFF1F8F7F)
    val TealSurface = Color(0xFF0F2B28)
    val OnTeal = Color(0xFF062A26)

    val Green = Color(0xFF3ECF6A)
    val GreenSurface = Color(0xFF123326)
    val GreenBorder = Color(0xFF1F5A3A)
    val OnGreen = Color(0xFF0B2A16)

    val Text = Color(0xFFFFFFFF)
    val TextBody = Color(0xFFE8DDD0)
    val Muted = Color(0xFFB9AEA0)
    val Muted2 = Color(0xFF8D8172)
    val Danger = Color(0xFFE65A5A)
    val DangerSurface = Color(0xFF361818)

    // Warm Archival Paper Light Counterpart
    val LightBg = Color(0xFFF7F3EB)
    val LightSurface = Color(0xFFEFE8DC)
    val LightSurface2 = Color(0xFFFDFBF7)
    val LightControl = Color(0xFFE4DAC9)
    val LightLine = Color(0xFFC4B49C)
    val LightCardBorder = Color(0xFFD3C5AF)
    val LightAmber = Color(0xFFB8700B)
    val LightTeal = Color(0xFF0D7669)
    val LightTealSurface = Color(0xFFDCF2EE)
    val LightGreen = Color(0xFF187938)
    val LightGreenSurface = Color(0xFFDCF5E4)
    val LightGreenBorder = Color(0xFF8ED6A4)
    val LightText = Color(0xFF1A1510)
    val LightTextBody = Color(0xFF2A211A)
    val LightMuted = Color(0xFF5C4F40)
    val LightMuted2 = Color(0xFF786958)
    val LightDanger = Color(0xFFB82E2E)
    val LightDangerSurface = Color(0xFFFCE8E8)
}
