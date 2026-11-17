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
 * `3.dp` Cylindrical Brushed-Steel Viewport Frame + Matte Street Spray Overspray.
 *
 * Non-negotiable physical & hit-test invariants:
 * - Left, right, and top viewport edges framed with `3.dp` steel rails flush to screen bezel:
 *   `#32373D` outer `1dp`, `#C4CCD4` specular ridge `1dp`, `#14171A` inner `1dp`.
 * - While [isLoading] is `true`, animates a `2.dp` solid acid-yellow (`#D8FF00`) pulse/line
 *   traveling along the top steel ridge; vanishes immediately when [isLoading] is `false`.
 * - Keeps ~74% of rail bare brushed steel, rendering 4 deterministic asymmetric matte acrylic
 *   splatter clusters (`#FF1A6C`, `#00E5FF`, `#D8FF00` hard-edged `0.5dp–2.5dp` stipple dots,
 *   zero blur) + two vertical drips (`2dp` wide, `12dp–18dp` long with rounded droplet) on
 *   left/right rails, strictly clipped `<= 1.5dp` inward (`<= 4.5dp` total from bezel) so
 *   webpage text is NEVER obscured.
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
        val oneDp = 1.dp.toPx()
        val twoDp = 2.dp.toPx()
        val threeDp = 3.dp.toPx()
        val topRailYPx = topInset.toPx().coerceAtLeast(0f)
        val maxInwardClipPx = 4.5.dp.toPx() // 3dp rail + <= 1.5dp inward overspray clip

        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // 1. Left Cylindrical Brushed-Steel Rail (x = 0..3dp, full bezel height)
        drawRect(
            color = MetaToolsTokens.SteelOuterEdge,
            topLeft = Offset(0f, 0f),
            size = Size(oneDp, h),
        )
        drawRect(
            color = MetaToolsTokens.SteelSpecularRidge,
            topLeft = Offset(oneDp, 0f),
            size = Size(oneDp, h),
        )
        drawRect(
            color = MetaToolsTokens.SteelInnerShadow,
            topLeft = Offset(twoDp, 0f),
            size = Size(oneDp, h),
        )

        // 2. Right Cylindrical Brushed-Steel Rail (x = w-3dp..w, full bezel height)
        drawRect(
            color = MetaToolsTokens.SteelOuterEdge,
            topLeft = Offset((w - oneDp).coerceAtLeast(0f), 0f),
            size = Size(oneDp, h),
        )
        drawRect(
            color = MetaToolsTokens.SteelSpecularRidge,
            topLeft = Offset((w - twoDp).coerceAtLeast(0f), 0f),
            size = Size(oneDp, h),
        )
        drawRect(
            color = MetaToolsTokens.SteelInnerShadow,
            topLeft = Offset((w - threeDp).coerceAtLeast(0f), 0f),
            size = Size(oneDp, h),
        )

        // 3. Top Cylindrical Brushed-Steel Rail (y = topRailYPx..topRailYPx+3dp)
        drawRect(
            color = MetaToolsTokens.SteelOuterEdge,
            topLeft = Offset(0f, topRailYPx),
            size = Size(w, oneDp),
        )
        drawRect(
            color = MetaToolsTokens.SteelSpecularRidge,
            topLeft = Offset(oneDp, topRailYPx + oneDp),
            size = Size((w - twoDp).coerceAtLeast(0f), oneDp),
        )
        drawRect(
            color = MetaToolsTokens.SteelInnerShadow,
            topLeft = Offset(twoDp, topRailYPx + twoDp),
            size = Size((w - twoDp * 2f).coerceAtLeast(0f), oneDp),
        )

        // Subtle machined brush ticks along bare steel ridge
        val brushSpacing = 18.dp.toPx()
        var yTick = brushSpacing
        while (yTick < h - brushSpacing) {
            drawRect(
                color = Color(0x33FFFFFF),
                topLeft = Offset(oneDp, yTick),
                size = Size(oneDp, oneDp * 2f),
            )
            drawRect(
                color = Color(0x33FFFFFF),
                topLeft = Offset((w - twoDp).coerceAtLeast(0f), yTick + brushSpacing * 0.5f),
                size = Size(oneDp, oneDp * 2f),
            )
            yTick += brushSpacing
        }

        // 4. Deterministic Asymmetric Matte Acrylic Splatter Clusters + 2 Vertical Drips
        // Left rail overspray strictly clipped to [0, 4.5dp] (<= 1.5dp inward of 3dp steel rail)
        clipRect(left = 0f, top = 0f, right = maxInwardClipPx.coerceAtMost(w), bottom = h) {
            drawLeftRailGraffiti(splatters.leftDots, h)
            // Left vertical drip: Street Magenta (#FF1A6C), 2dp wide, 16dp long + rounded droplet
            drawAcrylicDrip(
                xPx = 1.1.dp.toPx(),
                startYPx = h * 0.275f,
                widthPx = twoDp,
                lengthPx = 16.dp.toPx(),
                color = MetaToolsTokens.StreetMagenta,
            )
        }

        // Right rail overspray strictly clipped to [w - 4.5dp, w] (<= 1.5dp inward of 3dp steel rail)
        clipRect(left = (w - maxInwardClipPx).coerceAtLeast(0f), top = 0f, right = w, bottom = h) {
            drawRightRailGraffiti(splatters.rightDots, w, h)
            // Right vertical drip: Street Cyan (#00E5FF), 2dp wide, 14dp long + rounded droplet
            drawAcrylicDrip(
                xPx = (w - 3.1.dp.toPx()).coerceAtLeast(0f),
                startYPx = h * 0.435f,
                widthPx = twoDp,
                lengthPx = 14.dp.toPx(),
                color = MetaToolsTokens.StreetCyan,
            )
        }

        // 5. Top Ridge Solid 2dp Acid-Yellow (#D8FF00) Traveling Load Pulse
        if (isLoading) {
            val pulseY = topRailYPx + 0.5.dp.toPx()
            val clampedProgress = (loadProgress.coerceIn(0, 100)) / 100f
            if (clampedProgress > 0f) {
                drawRect(
                    color = MetaToolsTokens.AcidYellow.copy(alpha = 0.45f),
                    topLeft = Offset(0f, pulseY),
                    size = Size(w * clampedProgress, twoDp),
                )
            }
            val segmentWidth = (w * 0.28f).coerceAtLeast(48.dp.toPx())
            val travelSpan = w + segmentWidth
            val pulseStart = (pulsePhase * travelSpan) - segmentWidth
            val clippedStart = pulseStart.coerceIn(0f, w)
            val clippedEnd = (pulseStart + segmentWidth).coerceIn(0f, w)
            if (clippedEnd > clippedStart) {
                drawRect(
                    color = MetaToolsTokens.AcidYellow,
                    topLeft = Offset(clippedStart, pulseY),
                    size = Size(clippedEnd - clippedStart, twoDp),
                )
            }
        }
    }
}

private fun DrawScope.drawLeftRailGraffiti(dots: List<StippleSpec>, viewportHeight: Float) {
    // Solid matte acrylic body patch behind cluster 1 (y = 23%..28%)
    drawRoundRect(
        color = MetaToolsTokens.StreetMagenta,
        topLeft = Offset(0f, viewportHeight * 0.235f),
        size = Size(2.8.dp.toPx(), viewportHeight * 0.042f),
        cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
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
    // Solid matte acid-yellow & magenta cap bands on right rail (y = 13%..17.5%)
    drawRoundRect(
        color = MetaToolsTokens.AcidYellow,
        topLeft = Offset(viewportWidth - 2.8.dp.toPx(), viewportHeight * 0.130f),
        size = Size(2.8.dp.toPx(), viewportHeight * 0.032f),
        cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
    )
    drawRoundRect(
        color = MetaToolsTokens.StreetCyan,
        topLeft = Offset(viewportWidth - 2.6.dp.toPx(), viewportHeight * 0.405f),
        size = Size(2.6.dp.toPx(), viewportHeight * 0.034f),
        cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
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
    // Rounded droplet bead at tip of vertical acrylic run
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
    val radiusDp: Float, // 0.5dp..2.5dp radius -> 0.5dp..2.5dp circle size
    val color: Color,
)

@Immutable
private data class RailSplatterSet(
    val leftDots: List<StippleSpec>,
    val rightDots: List<StippleSpec>,
)

/**
 * Generates 4 deterministic asymmetric nozzle stipple clusters leaving >72% of rails bare steel:
 * - Cluster 1 (Left upper-mid, `0.21..0.30`): Street Magenta + Cyan
 * - Cluster 2 (Left lower-mid, `0.55..0.61`): Street Cyan + Acid Yellow
 * - Cluster 3 (Right upper, `0.11..0.19`): Acid Yellow + Street Magenta
 * - Cluster 4 (Right mid, `0.39..0.47`): Street Cyan + Street Magenta
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

            // Circle diameter between 0.5dp and 2.5dp -> radius 0.35dp..1.25dp
            val radiusDp = 0.35f + u3 * 0.90f
            val edgeDp = 0.4f + u2 * 3.4f
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
        count = 22,
        primary = MetaToolsTokens.StreetMagenta,
        secondary = MetaToolsTokens.StreetCyan,
    )
    seedCluster(
        target = left,
        seedInit = 0x51A7_0002u,
        yStart = 0.555f,
        ySpan = 0.055f,
        count = 14,
        primary = MetaToolsTokens.StreetCyan,
        secondary = MetaToolsTokens.AcidYellow,
    )
    seedCluster(
        target = right,
        seedInit = 0x51A7_0003u,
        yStart = 0.115f,
        ySpan = 0.075f,
        count = 20,
        primary = MetaToolsTokens.AcidYellow,
        secondary = MetaToolsTokens.StreetMagenta,
    )
    seedCluster(
        target = right,
        seedInit = 0.395f.toBits().toUInt() xor 0x51A7_0004u,
        yStart = 0.395f,
        ySpan = 0.075f,
        count = 18,
        primary = MetaToolsTokens.StreetCyan,
        secondary = MetaToolsTokens.StreetMagenta,
    )

    return RailSplatterSet(leftDots = left, rightDots = right)
}
