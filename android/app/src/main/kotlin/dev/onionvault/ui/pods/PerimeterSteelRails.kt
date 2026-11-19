package dev.onionvault.ui.pods

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * `12.dp` Brushed-Steel Vertical Perimeter Rails + Outer Street Spray-Paint Accents (`metatools-reference.jpg` 1:1).
 *
 * Non-negotiable physical & hit-test invariants:
 * - Left and right viewport edges framed with `12.dp` total rail assembly:
 *   - Outer `4.dp` weathered concrete/steel wall strip (`#ABA5AC`) with authentic cyan, magenta,
 *     acid-yellow, and violet spray-paint mist + vertical drips matching `metatools-reference.jpg`.
 *   - Inner `8.dp` cylindrical brushed-steel vertical bar (`#50545C -> #CED5DE -> #464A52 -> #090B0E`)
 *     with vertical brushed-metal micro-striations and crisp inner shadow seam.
 * - While [isLoading] is `true`, animates a `3.dp` solid acid-yellow (`#D8FF00`) pulse traveling
 *   along the top bezel; vanishes immediately when [isLoading] is `false`.
 * - **100% Hit-Test Passthrough**: Attaches ZERO `pointerInput` and ZERO `clickable` modifiers;
 *   every touch passes straight through to the webpage surface!
 */
@Composable
fun PerimeterSteelRails(
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    loadProgress: Int = 0,
    topInset: Dp = 0.dp,
) {
    val splatters = remember { buildDeterministicRailSplatters() }

    val pulsePhase = if (isLoading) {
        val transition = rememberInfiniteTransition(label = "steel_rail_load_pulse")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 850, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "steel_rail_pulse_phase",
        )
        phase
    } else {
        0f
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("perimeter_steel_rails"),
    ) {
        val outerSprayStripPx = 4.dp.toPx()
        val steelBarWidthPx = 8.dp.toPx()
        val totalRailWidthPx = outerSprayStripPx + steelBarWidthPx // 12.dp
        val seamWidthPx = 1.2.dp.toPx()
        val threeDp = 3.dp.toPx()
        val topRailYPx = topInset.toPx().coerceAtLeast(0f)

        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // 1. Left Outer Spray-Painted Wall Margin (x = 0..4dp) + Left 8dp Brushed-Steel Bar (x = 4..12dp)
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color(0xFFB4AEB5),
                0.45f to Color(0xFFA6A0A8),
                1.0f to Color(0xFF7D7880),
            ),
            topLeft = Offset(0f, 0f),
            size = Size(outerSprayStripPx, h),
        )
        drawRect(
            brush = Brush.horizontalGradient(
                0.0f to Color(0xFF4B4F57),
                0.18f to Color(0xFF9EA5B0),
                0.42f to Color(0xFFCFD6DF),
                0.68f to Color(0xFF8C939E),
                0.88f to Color(0xFF545962),
                1.0f to Color(0xFF0A0C0F),
                startX = outerSprayStripPx,
                endX = totalRailWidthPx,
            ),
            topLeft = Offset(outerSprayStripPx, 0f),
            size = Size(steelBarWidthPx, h),
        )
        drawRect(
            color = Color(0xFF2E3137),
            topLeft = Offset(outerSprayStripPx - seamWidthPx * 0.5f, 0f),
            size = Size(seamWidthPx, h),
        )

        // 2. Right 8dp Brushed-Steel Bar (x = w-12..w-4dp) + Right Outer Spray-Painted Wall Margin (x = w-4..w)
        val rightRailLeft = (w - totalRailWidthPx).coerceAtLeast(0f)
        val rightOuterLeft = (w - outerSprayStripPx).coerceAtLeast(0f)
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color(0xFFB2ACB2),
                0.50f to Color(0xFF9D98A0),
                1.0f to Color(0xFF78747C),
            ),
            topLeft = Offset(rightOuterLeft, 0f),
            size = Size(outerSprayStripPx, h),
        )
        drawRect(
            brush = Brush.horizontalGradient(
                0.0f to Color(0xFF0A0C0F),
                0.12f to Color(0xFF545962),
                0.34f to Color(0xFF8C939E),
                0.58f to Color(0xFFCFD6DF),
                0.82f to Color(0xFF9EA5B0),
                1.0f to Color(0xFF4B4F57),
                startX = rightRailLeft,
                endX = rightOuterLeft,
            ),
            topLeft = Offset(rightRailLeft, 0f),
            size = Size(steelBarWidthPx, h),
        )
        drawRect(
            color = Color(0xFF2E3137),
            topLeft = Offset(rightOuterLeft - seamWidthPx * 0.5f, 0f),
            size = Size(seamWidthPx, h),
        )

        // Vertical brushed-steel grain striations along left & right steel bars
        val grainStepX = 1.6.dp.toPx()
        var gx = outerSprayStripPx + 1.2.dp.toPx()
        var stripeIdx = 0
        while (gx < totalRailWidthPx - 1.2.dp.toPx()) {
            val tint = if (stripeIdx % 2 == 0) Color(0x24FFFFFF) else Color(0x22000000)
            drawLine(
                color = tint,
                start = Offset(gx, 0f),
                end = Offset(gx, h),
                strokeWidth = 0.7.dp.toPx(),
            )
            val rx = rightRailLeft + (gx - outerSprayStripPx)
            drawLine(
                color = tint,
                start = Offset(rx, 0f),
                end = Offset(rx, h),
                strokeWidth = 0.7.dp.toPx(),
            )
            gx += grainStepX
            stripeIdx += 1
        }

        // Subtle horizontal dark top bezel seam
        drawRect(
            color = MetaToolsTokens.SteelInnerShadow,
            topLeft = Offset(0f, topRailYPx),
            size = Size(w, 2.dp.toPx()),
        )

        // 3. Left Outer Spray-Paint Bands, Stipples & Signature Violet Drip (metatools-reference.jpg 1:1)
        clipRect(left = 0f, top = 0f, right = totalRailWidthPx.coerceAtMost(w), bottom = h) {
            drawLeftRailGraffiti(splatters.leftDots, outerSprayStripPx, h)
            drawAcrylicDrip(
                xPx = 1.6.dp.toPx(),
                startYPx = h * 0.445f,
                widthPx = 2.6.dp.toPx(),
                lengthPx = h * 0.145f,
                color = Color(0xFF6B2CBF),
            )
        }

        // 4. Right Outer Spray-Paint Bands, Stipples & Dark Charcoal Drip (metatools-reference.jpg 1:1)
        clipRect(left = rightRailLeft, top = 0f, right = w, bottom = h) {
            drawRightRailGraffiti(splatters.rightDots, w, outerSprayStripPx, h)
            drawAcrylicDrip(
                xPx = (w - 3.6.dp.toPx()).coerceAtLeast(0f),
                startYPx = h * 0.195f,
                widthPx = 2.8.dp.toPx(),
                lengthPx = h * 0.175f,
                color = Color(0xFF20252C),
            )
        }

        // 5. Top Ridge Solid 3dp Acid-Yellow (#D8FF00) Traveling Load Pulse
        if (isLoading) {
            val pulseY = topRailYPx
            val clampedProgress = (loadProgress.coerceIn(0, 100)) / 100f
            if (clampedProgress > 0f) {
                drawRect(
                    color = MetaToolsTokens.AcidYellow.copy(alpha = 0.48f),
                    topLeft = Offset(0f, pulseY),
                    size = Size(w * clampedProgress, threeDp),
                )
            }
            val segmentWidth = (w * 0.28f).coerceAtLeast(64.dp.toPx())
            val travelSpan = w + segmentWidth
            val pulseStart = (pulsePhase * travelSpan) - segmentWidth
            val clippedStart = pulseStart.coerceIn(0f, w)
            val clippedEnd = (pulseStart + segmentWidth).coerceIn(0f, w)
            if (clippedEnd > clippedStart) {
                drawRect(
                    color = MetaToolsTokens.AcidYellow,
                    topLeft = Offset(clippedStart, pulseY),
                    size = Size(clippedEnd - clippedStart, threeDp),
                )
            }
        }
    }
}

private fun DrawScope.drawLeftRailGraffiti(
    dots: List<StippleSpec>,
    outerStripPx: Float,
    viewportHeight: Float,
) {
    // Cyan + Magenta outer spray wash matching left edge of metatools-reference.jpg
    drawRoundRect(
        brush = Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.25f to MetaToolsTokens.StreetCyan.copy(alpha = 0.85f),
            0.65f to MetaToolsTokens.StreetMagenta.copy(alpha = 0.88f),
            1.0f to Color.Transparent,
            startY = viewportHeight * 0.26f,
            endY = viewportHeight * 0.50f,
        ),
        topLeft = Offset(0f, viewportHeight * 0.26f),
        size = Size(outerStripPx, viewportHeight * 0.24f),
        cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
    )
    dots.forEach { dot ->
        drawCircle(
            color = dot.color,
            radius = dot.radiusDp.dp.toPx(),
            center = Offset(
                x = dot.edgeOffsetDp.dp.toPx(),
                y = viewportHeight * dot.yFraction,
            ),
        )
    }
}

private fun DrawScope.drawRightRailGraffiti(
    dots: List<StippleSpec>,
    viewportWidth: Float,
    outerStripPx: Float,
    viewportHeight: Float,
) {
    // Acid yellow + magenta + cyan outer spray washes matching right edge of metatools-reference.jpg
    drawRoundRect(
        brush = Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.35f to Color(0xFFFFEA00),
            0.75f to MetaToolsTokens.StreetMagenta.copy(alpha = 0.90f),
            1.0f to Color.Transparent,
            startY = viewportHeight * 0.05f,
            endY = viewportHeight * 0.25f,
        ),
        topLeft = Offset(viewportWidth - outerStripPx, viewportHeight * 0.05f),
        size = Size(outerStripPx, viewportHeight * 0.20f),
        cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            0.0f to Color.Transparent,
            0.35f to MetaToolsTokens.StreetCyan.copy(alpha = 0.90f),
            0.80f to MetaToolsTokens.StreetMagenta.copy(alpha = 0.72f),
            1.0f to Color.Transparent,
            startY = viewportHeight * 0.28f,
            endY = viewportHeight * 0.56f,
        ),
        topLeft = Offset(viewportWidth - outerStripPx, viewportHeight * 0.28f),
        size = Size(outerStripPx, viewportHeight * 0.28f),
        cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
    )
    dots.forEach { dot ->
        drawCircle(
            color = dot.color,
            radius = dot.radiusDp.dp.toPx(),
            center = Offset(
                x = viewportWidth - dot.edgeOffsetDp.dp.toPx(),
                y = viewportHeight * dot.yFraction,
            ),
        )
    }
}

private fun DrawScope.drawAcrylicDrip(
    xPx: Float,
    startYPx: Float,
    widthPx: Float,
    lengthPx: Float,
    color: Color,
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(xPx, startYPx),
        size = Size(widthPx, lengthPx),
        cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f),
    )
    val dropletRadius = widthPx * 0.72f
    drawCircle(
        color = color,
        radius = dropletRadius,
        center = Offset(xPx + widthPx / 2f, startYPx + lengthPx),
    )
}

@Immutable
private data class StippleSpec(
    val yFraction: Float,
    val edgeOffsetDp: Float,
    val radiusDp: Float,
    val color: Color,
)

@Immutable
private data class RailSplatterSet(
    val leftDots: List<StippleSpec>,
    val rightDots: List<StippleSpec>,
)

/**
 * Generates deterministic asymmetric nozzle overspray stipple dots across the outer edge & steel shoulder.
 */
private fun buildDeterministicRailSplatters(): RailSplatterSet {
    val left = mutableListOf<StippleSpec>()
    val right = mutableListOf<StippleSpec>()

    fun seedCluster(
        target: MutableList<StippleSpec>,
        seedInit: UInt,
        yStart: Float,
        ySpan: Float,
        count: Int,
        primary: Color,
        secondary: Color,
    ) {
        var s = seedInit
        repeat(count) { idx ->
            s = s * 1664525u + 1013904223u
            val u1 = ((s shr 8) and 0xFFFFu).toFloat() / 65535f
            s = s * 1664525u + 1013904223u
            val u2 = ((s shr 8) and 0xFFFFu).toFloat() / 65535f
            s = s * 1664525u + 1013904223u
            val u3 = ((s shr 8) and 0xFFFFu).toFloat() / 65535f

            val radiusDp = 0.55f + u3 * 1.45f
            val edgeDp = 0.5f + u2 * 7.2f
            target += StippleSpec(
                yFraction = yStart + u1 * ySpan,
                edgeOffsetDp = edgeDp,
                radiusDp = radiusDp,
                color = if (idx % 3 == 0) secondary else primary,
            )
        }
    }

    seedCluster(
        target = left,
        seedInit = 0x51A7_0001u,
        yStart = 0.255f,
        ySpan = 0.235f,
        count = 32,
        primary = MetaToolsTokens.StreetMagenta,
        secondary = MetaToolsTokens.StreetCyan,
    )
    seedCluster(
        target = left,
        seedInit = 0x51A7_0002u,
        yStart = 0.555f,
        ySpan = 0.085f,
        count = 16,
        primary = Color(0xFF7B2CBF),
        secondary = MetaToolsTokens.StreetCyan,
    )
    seedCluster(
        target = right,
        seedInit = 0x51A7_0003u,
        yStart = 0.075f,
        ySpan = 0.165f,
        count = 26,
        primary = MetaToolsTokens.AcidYellow,
        secondary = MetaToolsTokens.StreetMagenta,
    )
    seedCluster(
        target = right,
        seedInit = 0.395f.toBits().toUInt() xor 0x51A7_0004u,
        yStart = 0.295f,
        ySpan = 0.245f,
        count = 28,
        primary = MetaToolsTokens.StreetCyan,
        secondary = MetaToolsTokens.StreetMagenta,
    )

    return RailSplatterSet(leftDots = left, rightDots = right)
}
