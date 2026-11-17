package dev.onionvault.ui.pods

import android.graphics.Paint as AndroidPaint
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.onionvault.ui.ContextMenuUiState
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.PgpOverlayState
import dev.onionvault.ui.SelectionPgpUiState
import dev.onionvault.ui.isBlankOrStartUrl

/**
 * 1997 MetaTools / Kai's Photo Soap / Street-Rail Hardware Token System.
 *
 * Strictly zero backdrop blur, zero translucent frosted glass, and zero full-width
 * container strips. Every floating hardware pod casts a hard 45-degree directional
 * drop shadow (`+8.dp`, `+14.dp`, `20.dp` blur, `rgba(0,0,0,0.85)`) with crisp
 * bead-blasted pewter, anodized aluminum, or translucent polycarbonate bevels.
 */
object MetaToolsTokens {
    // 3dp Cylindrical Brushed-Steel Perimeter Rails
    val SteelOuterEdge = Color(0xFF32373D)
    val SteelSpecularRidge = Color(0xFFC4CCD4)
    val SteelInnerShadow = Color(0xFF14171A)

    // Matte Acrylic Street Spray Overspray & Acid Load Pulse
    val StreetMagenta = Color(0xFFFF1A6C)
    val StreetCyan = Color(0xFF00E5FF)
    val AcidYellow = Color(0xFFD8FF00)

    // Bead-Blasted Cast Pewter Housing (Tor Orb Collar & CRT Capsule Collar)
    val PewterBase = Color(0xFF7D838C)
    val PewterMidDark = Color(0xFF5D636B)
    val PewterHighlightBevel = Color(0xFFB8C0C8)
    val PewterShadowBevel = Color(0xFF2B2E33)
    val PewterCrater = Color(0xFF111317)

    // Dark Anodized Aluminum Chassis (PhotoSoap Side Remote & Hardware Modal Pods)
    val AnodizedSurfaceTop = Color(0xFF282D35)
    val AnodizedSurface = Color(0xFF1E2228)
    val AnodizedSurfaceDeep = Color(0xFF14171C)
    val AnodizedRecessedWell = Color(0xFF0D0F13)
    val AnodizedBevelLight = Color(0xFF4B535E)
    val AnodizedBevelDark = Color(0xFF090A0D)
    val GrooveShadow = Color(0xFF0A0B0D)
    val GrooveHighlight = Color(0xFF343942)
    val StencilInk = Color(0xFFB8C0C8)
    val StickerPaper = Color(0xFFDFDCD2)
    val StickerInk = Color(0xFF111315)

    // Recessed CRT Black Glass & Phosphor Beams
    val CrtBlackGlass = Color(0xFF050608)
    val CrtGlassReflection = Color(0x14FFFFFF) // rgba(255,255,255,0.08)
    val PhosphorOnionGreen = Color(0xFF39FF14)
    val PhosphorLoadingAmber = Color(0xFFFFB000)
    val PhosphorClearnetGreen = Color(0xFF5CFF8A)
    val PhosphorClearnetCyan = Color(0xFF7DF9FF)
    val PhosphorPathDim = Color(0xFF4A5560)
    val PhosphorMutedHint = Color(0xFF4C7A57)

    // 3D Raytraced Tor Sphere
    val TorPurpleBright = Color(0xFFB565FF)
    val TorPurpleMid = Color(0xFF7B2CBF)
    val TorPurpleDeep = Color(0xFF240046)
    val TorAmberBright = Color(0xFFFFD000)
    val TorAmberMid = Color(0xFFB87A00)
    val TorAmberDeep = Color(0xFF7A5200)

    // OpenPGP Translucent Ruby-Orange Polycarbonate & Ribbed Latch
    val PolycarbonateRuby = Color(0xE0E63900) // #E63900 at 88% alpha
    val PolycarbonateRubyCore = Color(0xCC9E1F00)
    val PolycarbonateSeam = Color(0xFF5C1000)
    val PolycarbonateRefraction = Color(0x70FF9D78)
    val PolycarbonateBackplate = Color(0xFF181A1F)
    val AluminumLatch = Color(0xFFAEB5BD)
    val AluminumLatchLight = Color(0xFFE0E6ED)
    val AluminumLatchShadow = Color(0xFF484E56)
    val PgpInternalLedEmerald = Color(0xFF00FF66)
    val PgpInternalLedAmber = Color(0xFFFF7700)

    // Recessed Glass LED Beads
    val LedGreen = Color(0xFF00FF66)
    val LedAmber = Color(0xFFFFAA00)
    val LedUnlit = Color(0xFF121418)

    // Hard Directional 45-degree Shadow Parameters
    val ShadowOffsetX: Dp = 8.dp
    val ShadowOffsetY: Dp = 14.dp
    val ShadowBlurRadius: Dp = 20.dp
    val ShadowColor = Color(0xD9000000) // rgba(0,0,0,0.85)

    // Choreographed Mechanical Timing & Easing (Zero Overshoot)
    val PodSnapEasing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.0f)
    const val POD_SNAP_DURATION_MS = 110
    const val ORB_DEPRESS_DURATION_MS = 75
    const val REMOTE_SLIDE_DURATION_MS = 140
    const val TOR_BOOT_TICK_MS = 166
}

/**
 * Generates and caches a deterministic 64x64 procedural 3% monochrome grain [ShaderBrush]
 * used to give cast-pewter and anodized aluminum housings authentic bead-blasted micro-texture.
 */
@Composable
fun rememberPewterGrainBrush(): ShaderBrush {
    return remember {
        val side = 64
        val bitmap = ImageBitmap(side, side, ImageBitmapConfig.Argb8888)
        val androidBitmap = android.graphics.Bitmap.createBitmap(
            side,
            side,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        var state = 0x1997_50A9u
        val pixels = IntArray(side * side)
        for (i in pixels.indices) {
            // Deterministic xorshift32
            state = state xor (state shl 13)
            state = state xor (state shr 17)
            state = state xor (state shl 5)
            val sample = (state and 0xFFu).toInt()
            // ~3% monochrome luminance grain (+/- 8/255 alpha white/black)
            val isBright = (sample and 1) == 0
            val alpha = 4 + ((sample ushr 1) % 8) // alpha 4..11 (~1.5%..4.3%, avg ~3%)
            val rgb = if (isBright) 0xFFFFFF else 0x000000
            pixels[i] = (alpha shl 24) or rgb
        }
        androidBitmap.setPixels(pixels, 0, side, 0, 0, side, side)
        val composeBitmap = ImageBitmap(side, side, ImageBitmapConfig.Argb8888)
        val canvas = androidx.compose.ui.graphics.Canvas(composeBitmap)
        canvas.nativeCanvas.drawBitmap(androidBitmap, 0f, 0f, null)
        ShaderBrush(ImageShader(composeBitmap, TileMode.Repeated, TileMode.Repeated))
    }
}

/**
 * Casts a hard 45-degree directional hardware shadow (`offset = (+8.dp, +14.dp)`,
 * `20.dp` blur radius, `rgba(0,0,0,0.85)`) purely inside `drawBehind`.
 *
 * Attaches ZERO pointerInput or layout bounds expansion so touches on the cast shadow
 * pass straight through to the underlying webpage!
 */
fun Modifier.metaToolsDropShadow(
    cornerRadius: Dp = 18.dp,
    isCircle: Boolean = false,
    rotationDegrees: Float = 0f,
): Modifier = this.drawBehind {
    val dx = MetaToolsTokens.ShadowOffsetX.toPx()
    val dy = MetaToolsTokens.ShadowOffsetY.toPx()
    val blur = MetaToolsTokens.ShadowBlurRadius.toPx()
    val rPx = cornerRadius.toPx()

    withTransform({
        if (rotationDegrees != 0f) {
            rotate(degrees = rotationDegrees, pivot = center)
        }
    }) {
        drawIntoCanvas { canvas ->
            val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.FILL
                // Nearly-transparent fill inside pod silhouette so setShadowLayer renders
                // full 85%-alpha directional shadow outside without tinting translucent pods.
                color = android.graphics.Color.argb(4, 0, 0, 0)
                setShadowLayer(blur, dx, dy, MetaToolsTokens.ShadowColor.toArgb())
            }
            if (isCircle) {
                val radius = size.minDimension / 2f
                canvas.nativeCanvas.drawCircle(center.x, center.y, radius - 0.5f, paint)
            } else {
                canvas.nativeCanvas.drawRoundRect(
                    0.5f,
                    0.5f,
                    size.width - 0.5f,
                    size.height - 0.5f,
                    rPx,
                    rPx,
                    paint,
                )
            }
        }
    }
}

/**
 * Draws a crisp directional `1.5.dp` top-left highlight bevel and bottom-right shadow bevel
 * around a rounded-rect hardware housing.
 */
fun DrawScope.drawMetallicSquircleBevel(
    cornerRadiusPx: Float,
    strokeWidthPx: Float,
    highlightColor: Color = MetaToolsTokens.PewterHighlightBevel,
    shadowColor: Color = MetaToolsTokens.PewterShadowBevel,
) {
    val half = strokeWidthPx / 2f
    val insetSize = Size(
        width = (size.width - strokeWidthPx).coerceAtLeast(1f),
        height = (size.height - strokeWidthPx).coerceAtLeast(1f),
    )
    val bevelBrush = Brush.linearGradient(
        0.0f to highlightColor,
        0.42f to highlightColor.copy(alpha = 0.55f),
        0.58f to shadowColor.copy(alpha = 0.65f),
        1.0f to shadowColor,
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    drawRoundRect(
        brush = bevelBrush,
        topLeft = Offset(half, half),
        size = insetSize,
        cornerRadius = CornerRadius(
            (cornerRadiusPx - half).coerceAtLeast(1f),
            (cornerRadiusPx - half).coerceAtLeast(1f),
        ),
        style = Stroke(width = strokeWidthPx),
    )
}

/**
 * Draws a `2.dp` horizontal engraved hardware groove (`1.dp #0A0B0D` over `1.dp #343942`).
 */
fun DrawScope.drawEngravedGroove(yPx: Float, startXPx: Float = 0f, endXPx: Float = size.width) {
    val oneDp = 1.dp.toPx()
    drawLine(
        color = MetaToolsTokens.GrooveShadow,
        start = Offset(startXPx, yPx),
        end = Offset(endXPx, yPx),
        strokeWidth = oneDp,
    )
    drawLine(
        color = MetaToolsTokens.GrooveHighlight,
        start = Offset(startXPx, yPx + oneDp),
        end = Offset(endXPx, yPx + oneDp),
        strokeWidth = oneDp,
    )
}

/**
 * Draws a `7.dp` recessed round glass LED bead inside a dark sunken socket cup.
 */
fun DrawScope.drawRecessedGlassLedBead(
    center: Offset,
    beadDiameterPx: Float,
    litColor: Color?,
) {
    val radius = beadDiameterPx / 2f
    val socketRadius = radius + 1.2.dp.toPx()

    // Sunken metallic socket cup
    drawCircle(
        color = MetaToolsTokens.GrooveHighlight.copy(alpha = 0.65f),
        radius = socketRadius + 0.6.dp.toPx(),
        center = Offset(center.x + 0.4.dp.toPx(), center.y + 0.5.dp.toPx()),
    )
    drawCircle(
        color = Color(0xFF07080A),
        radius = socketRadius,
        center = center,
    )

    if (litColor != null) {
        // Outer halation glow
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to litColor.copy(alpha = 0.55f),
                0.65f to litColor.copy(alpha = 0.18f),
                1.0f to Color.Transparent,
                center = center,
                radius = radius * 2.3f,
            ),
            radius = radius * 2.3f,
            center = center,
        )
        // Lit dome body
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color.White.copy(alpha = 0.92f),
                0.35f to litColor,
                0.85f to litColor.copy(alpha = 0.78f),
                1.0f to Color(0xFF091A0F),
                center = Offset(center.x - radius * 0.28f, center.y - radius * 0.28f),
                radius = radius * 1.25f,
            ),
            radius = radius,
            center = center,
        )
    } else {
        // Unlit dark smoked glass bead (#121418)
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color(0xFF232830),
                0.6f to MetaToolsTokens.LedUnlit,
                1.0f to Color(0xFF08090C),
                center = Offset(center.x - radius * 0.25f, center.y - radius * 0.25f),
                radius = radius * 1.2f,
            ),
            radius = radius,
            center = center,
        )
        // Tiny glass pinprick specular on unlit dome
        drawCircle(
            color = Color.White.copy(alpha = 0.22f),
            radius = (radius * 0.22f).coerceAtLeast(0.8f),
            center = Offset(center.x - radius * 0.32f, center.y - radius * 0.32f),
        )
    }
}

/**
 * Formatted CRT readout model for [RecessedCrtAddressPod] resting state.
 */
@Immutable
data class CrtFormattedReadout(
    val text: AnnotatedString,
    val isOnion: Boolean,
    val isStartPage: Boolean,
    val halationShadow: Shadow?,
)

/**
 * Formats [url] for the resting CRT capsule:
 * - StartPage (`about:blank` / blank): muted phosphor `"search / .onion"`.
 * - `.onion` host: `#39FF14` (`#FFB000` while loading) with `6px` phosphor halation shadow
 *   and tail-preserving `.onion` suffix (e.g. `duckduck…zczad.onion`).
 * - Clearnet HTTPS/HTTP URL: host in `#5CFF8A` / `#7DF9FF`, path in `#4A5560`.
 */
fun formatCrtDisplayAddress(
    url: String,
    isLoading: Boolean,
): CrtFormattedReadout {
    if (isBlankOrStartUrl(url)) {
        return CrtFormattedReadout(
            text = AnnotatedString(
                text = "search / .onion",
                spanStyle = SpanStyle(color = MetaToolsTokens.PhosphorMutedHint),
            ),
            isOnion = false,
            isStartPage = true,
            halationShadow = null,
        )
    }

    val trimmed = url.trim()
    val lower = trimmed.lowercase()
    val isHttps = lower.startsWith("https://")
    val withoutScheme = trimmed
        .removePrefix("https://")
        .removePrefix("HTTPS://")
        .removePrefix("http://")
        .removePrefix("HTTP://")

    val slashIdx = withoutScheme.indexOf('/')
    val rawAuthority = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
    val rawPath = if (slashIdx >= 0) withoutScheme.substring(slashIdx) else ""
    val hostOnly = rawAuthority.substringBefore(':').trimEnd('.')

    if (hostOnly.lowercase().endsWith(".onion")) {
        val truncatedOnion = truncateOnionPreservingTail(hostOnly.lowercase())
        val phosphorColor = if (isLoading) {
            MetaToolsTokens.PhosphorLoadingAmber
        } else {
            MetaToolsTokens.PhosphorOnionGreen
        }
        val shadow = Shadow(
            color = phosphorColor.copy(alpha = 0.72f),
            offset = Offset.Zero,
            blurRadius = 6f,
        )
        val styled = buildAnnotatedString {
            withStyle(SpanStyle(color = phosphorColor, shadow = shadow)) {
                append(truncatedOnion)
            }
        }
        return CrtFormattedReadout(
            text = styled,
            isOnion = true,
            isStartPage = false,
            halationShadow = shadow,
        )
    }

    val hostColor = if (isHttps) {
        MetaToolsTokens.PhosphorClearnetGreen
    } else {
        MetaToolsTokens.PhosphorClearnetCyan
    }
    val compactHost = hostOnly.removePrefix("www.").ifEmpty { trimmed }
    val compactPath = rawPath.takeIf { it.isNotEmpty() && it != "/" }?.let { path ->
        if (path.length > 10) path.take(9) + "…" else path
    }.orEmpty()

    val styled = buildAnnotatedString {
        withStyle(SpanStyle(color = hostColor)) {
            append(compactHost)
        }
        if (compactPath.isNotEmpty()) {
            withStyle(SpanStyle(color = MetaToolsTokens.PhosphorPathDim)) {
                append(compactPath)
            }
        }
    }
    return CrtFormattedReadout(
        text = styled,
        isOnion = false,
        isStartPage = false,
        halationShadow = null,
    )
}

/**
 * Preserves recognizable prefix + trailing 5-char public-key suffix + `.onion`
 * (e.g. `duckduckgogg42xj...zczad.onion` -> `duckduck…zczad.onion`), while keeping
 * short `.onion` labels (`dread7x.onion`) intact.
 */
fun truncateOnionPreservingTail(host: String): String {
    val clean = host.trim().lowercase().removePrefix("www.")
    if (!clean.endsWith(".onion")) return clean
    val stem = clean.removeSuffix(".onion")
    if (stem.length <= 15) {
        return "$stem.onion"
    }
    val prefix = stem.take(8)
    val tail = stem.takeLast(5)
    return "$prefix…$tail.onion"
}

fun isOnionHostUrl(url: String): Boolean {
    if (isBlankOrStartUrl(url)) return false
    val withoutScheme = url.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore(':')
        .trimEnd('.')
        .lowercase()
    return withoutScheme.endsWith(".onion")
}

/**
 * Pure, truthful PGP presence detector across live CDP DOM fields, active selection,
 * context menu payload, and optional clipboard text. Zero fabricated states.
 */
fun detectContextualPgpPresence(
    pgp: PgpOverlayState,
    selectionPgp: SelectionPgpUiState? = null,
    contextMenu: ContextMenuUiState? = null,
    clipboardText: String? = null,
): Boolean {
    val domHasPgp = pgp.fields.any { field -> field.isContextualPgpField() }
    val selectionHasPgp = selectionPgp != null && (
        selectionPgp.kind == "pgp_message" ||
            selectionPgp.kind == "pgp_public_key" ||
            containsArmoredPgpHeader(selectionPgp.selectedText) ||
            containsArmoredPgpHeader(selectionPgp.armored)
        )
    val contextHasPgp = contextMenu != null && (
        contextMenu.classificationKind == "pgp_message" ||
            contextMenu.classificationKind == "pgp_public_key" ||
            containsArmoredPgpHeader(contextMenu.armoredPayload) ||
            containsArmoredPgpHeader(contextMenu.selectedText)
        )
    val clipHasPgp = containsArmoredPgpHeader(clipboardText)
    return domHasPgp ||
        selectionHasPgp ||
        contextHasPgp ||
        clipHasPgp ||
        pgp.plaintext != null ||
        pgp.encryptRequest != null ||
        pgp.decryptPassphraseRequest != null
}

fun detectArmoredBlockPresent(
    pgp: PgpOverlayState,
    selectionPgp: SelectionPgpUiState? = null,
    contextMenu: ContextMenuUiState? = null,
    clipboardText: String? = null,
): Boolean {
    return pgp.fields.any { it.kind == "pgp_message" || it.kind == "pgp_public_key" } ||
        selectionPgp?.kind == "pgp_message" ||
        contextMenu?.classificationKind == "pgp_message" ||
        containsArmoredPgpHeader(clipboardText)
}

fun containsArmoredPgpHeader(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    return text.contains("-----BEGIN PGP MESSAGE-----") ||
        text.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----") ||
        text.contains("-----BEGIN PGP SIGNED MESSAGE-----") ||
        text.contains("-----BEGIN PGP PRIVATE KEY BLOCK-----")
}

private fun PgpFieldState.isContextualPgpField(): Boolean {
    return when (kind) {
        "pgp_message", "pgp_public_key", "pgp_textarea", "textarea", "editable" -> true
        "text_input" -> label.contains("PubKey", ignoreCase = true) ||
            label.contains("Encrypt", ignoreCase = true) ||
            containsArmoredPgpHeader(armored) ||
            containsArmoredPgpHeader(preview)
        else -> containsArmoredPgpHeader(armored) || containsArmoredPgpHeader(preview)
    }
}
