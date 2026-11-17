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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * `4.5.dp` (`1.5x` Scaled) Cylindrical Brushed-Steel Viewport Frame + Matte Street Spray Overspray.
 *
 * Non-negotiable physical & hit-test invariants:
 * - Left, right, and top viewport edges framed with `4.5.dp` steel rails flush to screen bezel:
 *   `#32373D` outer `1.5dp`, `#C4CCD4` specular ridge `1.5dp`, `#14171A` inner `1.5dp`.
 * - While [isLoading] is `true`, animates a `3.dp` solid acid-yellow (`#D8FF00`) pulse/line
 *   traveling along the top steel ridge; vanishes immediately when [isLoading] is `false`.
 * - Keeps ~72% of rail bare brushed steel, rendering 4 deterministic asymmetric matte acrylic
 *   splatter clusters (`#FF1A6C`, `#00E5FF`, `#D8FF00` hard-edged `1.5x` stipple dots, zero blur) +
 *   two vertical drips (`3.dp` wide, `21dp–24dp` long with rounded droplet) on left/right rails,
 *   strictly clipped `<= 2.dp` inward (`<= 6.5dp` total from bezel) so webpage text is NEVER obscured.
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
        val bandPx = 1.5.dp.toPx()
        val railWidthPx = 4.5.dp.toPx()
        val threeDp = 3.dp.toPx()
        val topRailYPx = topInset.toPx().coerceAtLeast(0f)
        val maxInwardClipPx = 6.5.dp.toPx() // 4.5dp rail + <= 2.0dp inward overspray clip

        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // 1. Left Cylindrical Brushed-Steel Rail (x = 0..4.5dp, full bezel height)
        drawRect(
            color = MetaToolsTokens.SteelOuterEdge,
            topLeft = Offset(0f, 0f),
            size = Size(bandPx, h),
        )
        drawRect(
            color = MetaToolsTokens.SteelSpecularRidge,
            topLeft = Offset(bandPx, 0f),
            size = Size(bandPx, h),
        )
        drawRect(
            color = MetaToolsTokens.SteelInnerShadow,
            topLeft = Offset(bandPx * 2f, 0f),
            size = Size(bandPx, h),
        )

        // 2. Right Cylindrical Brushed-Steel Rail (x = w-4.5dp..w, full bezel height)
        drawRect(
            color = MetaToolsTokens.SteelOuterEdge,
            topLeft = Offset((w - bandPx).coerceAtLeast(0f), 0f),
            size = Size(bandPx, h),
        )
        drawRect(
            color = MetaToolsTokens.SteelSpecularRidge,
            topLeft = Offset((w - bandPx * 2f).coerceAtLeast(0f), 0f),
            size = Size(bandPx, h),
        )
        drawRect(
            color = MetaToolsTokens.SteelInnerShadow,
            topLeft = Offset((w - railWidthPx).coerceAtLeast(0f), 0f),
            size = Size(bandPx, h),
        )

        // 3. Top Cylindrical Brushed-Steel Rail (y = topRailYPx..topRailYPx+4.5dp)
        drawRect(
            color = MetaToolsTokens.SteelOuterEdge,
            topLeft = Offset(0f, topRailYPx),
            size = Size(w, bandPx),
        )
        drawRect(
            color = MetaToolsTokens.SteelSpecularRidge,
            topLeft = Offset(bandPx, topRailYPx + bandPx),
            size = Size((w - bandPx * 2f).coerceAtLeast(0f), bandPx),
        )
        drawRect(
            color = MetaToolsTokens.SteelInnerShadow,
            topLeft = Offset(bandPx * 2f, topRailYPx + bandPx * 2f),
            size = Size((w - bandPx * 4f).coerceAtLeast(0f), bandPx),
        )

        // Machined brush ticks along bare steel ridge
        val brushSpacing = 22.dp.toPx()
        var yTick = brushSpacing
        while (yTick < h - brushSpacing) {
            drawRect(
                color = Color(0x38FFFFFF),
                topLeft = Offset(bandPx, yTick),
                size = Size(bandPx, bandPx * 1.8f),
            )
            drawRect(
                color = Color(0x38FFFFFF),
                topLeft = Offset((w - bandPx * 2f).coerceAtLeast(0f), yTick + brushSpacing * 0.5f),
                size = Size(bandPx, bandPx * 1.8f),
            )
            yTick += brushSpacing
        }

        // 4. 1.5x Deterministic Asymmetric Matte Acrylic Splatter Clusters + 2 Vertical Drips
        clipRect(left = 0f, top = 0f, right = maxInwardClipPx.coerceAtMost(w), bottom = h) {
            drawLeftRailGraffiti(splatters.leftDots, h)
            drawAcrylicDrip(
                xPx = 1.4.dp.toPx(),
                startYPx = h * 0.275f,
                widthPx = threeDp,
                lengthPx = 24.dp.toPx(),
                color = MetaToolsTokens.StreetMagenta,
            )
        }

        clipRect(left = (w - maxInwardClipPx).coerceAtLeast(0f), top = 0f, right = w, bottom = h) {
            drawRightRailGraffiti(splatters.rightDots, w, h)
            drawAcrylicDrip(
                xPx = (w - 4.4.dp.toPx()).coerceAtLeast(0f),
                startYPx = h * 0.435f,
                widthPx = threeDp,
                lengthPx = 21.dp.toPx(),
                color = MetaToolsTokens.StreetCyan,
            )
        }

        // 5. Top Ridge Solid 3dp Acid-Yellow (#D8FF00) Traveling Load Pulse
        if (isLoading) {
            val pulseY = topRailYPx + 0.75.dp.toPx()
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

private fun DrawScope.drawLeftRailGraffiti(dots: List<StippleSpec>, viewportHeight: Float) {
    drawRoundRect(
        color = MetaToolsTokens.StreetMagenta,
        topLeft = Offset(0f, viewportHeight * 0.235f),
        size = Size(4.2.dp.toPx(), viewportHeight * 0.046f),
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
    viewportHeight: Float,
) {
    drawRoundRect(
        color = MetaToolsTokens.AcidYellow,
        topLeft = Offset(viewportWidth - 4.2.dp.toPx(), viewportHeight * 0.130f),
        size = Size(4.2.dp.toPx(), viewportHeight * 0.036f),
        cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
    )
    drawRoundRect(
        color = MetaToolsTokens.StreetCyan,
        topLeft = Offset(viewportWidth - 3.9.dp.toPx(), viewportHeight * 0.405f),
        size = Size(3.9.dp.toPx(), viewportHeight * 0.038f),
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
    val dropletRadius = widthPx * 0.68f
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
 * Generates 4 deterministic `1.5x`-scaled asymmetric nozzle stipple clusters leaving >70% bare steel.
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

            val radiusDp = 0.52f + u3 * 1.35f
            val edgeDp = 0.6f + u2 * 4.8f
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
        yStart = 0.215f,
        ySpan = 0.085f,
        count = 24,
        primary = MetaToolsTokens.StreetMagenta,
        secondary = MetaToolsTokens.StreetCyan,
    )
    seedCluster(
        target = left,
        seedInit = 0x51A7_0002u,
        yStart = 0.555f,
        ySpan = 0.055f,
        count = 16,
        primary = MetaToolsTokens.StreetCyan,
        secondary = MetaToolsTokens.AcidYellow,
    )
    seedCluster(
        target = right,
        seedInit = 0x51A7_0003u,
        yStart = 0.115f,
        ySpan = 0.075f,
        count = 22,
        primary = MetaToolsTokens.AcidYellow,
        secondary = MetaToolsTokens.StreetMagenta,
    )
    seedCluster(
        target = right,
        seedInit = 0.395f.toBits().toUInt() xor 0x51A7_0004u,
        yStart = 0.395f,
        ySpan = 0.075f,
        count = 20,
        primary = MetaToolsTokens.StreetCyan,
        secondary = MetaToolsTokens.StreetMagenta,
    )

    return RailSplatterSet(leftDots = left, rightDots = right)
}
