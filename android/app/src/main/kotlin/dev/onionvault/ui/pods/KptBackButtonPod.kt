package dev.onionvault.ui.pods

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `74.dp x 74.dp` Bottom-Left Primary Thumb Hardware Island —
 * 1997 Kai's Power Tools Cast-Pewter & Anodized Titanium Back Button (`kpt_back_pod`).
 *
 * Visual & Tactile Spec:
 * - `#7D838C` cast-pewter circular outer collar with `1.5.dp` radial knurled rim ridges,
 *   machined steel inner chamfer ring, and concave `#111317` socket crater.
 * - `58.dp` dark anodized titanium dome (`#2E343D -> #1F242B -> #14171C`) with crisp
 *   10-o'clock specular highlight, bold debossed / phosphor `◀` arrow glyph, and an `8.dp`
 *   recessed emerald glass LED pip lit `#00FF66` when back navigation is available and
 *   unlit `#121418` when on the root StartPage with no history.
 * - `3.dp` `75ms` mechanical press snap + haptic tick!
 * - **Tap**: invokes [onBack] when [canGoBack] is `true`, or [onReturnToStartPage] when
 *   [canReturnToStartPage] is `true` (so first-hop directory pages return cleanly to `StartPage`).
 * - **Long-press (`400ms`)**: invokes [onForward] when [canGoForward] is `true` (with haptic tick).
 */
@Composable
fun KptBackButtonPod(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
    canReturnToStartPage: Boolean = false,
    onReturnToStartPage: () -> Unit = onBack,
    isRetracted: Boolean = false,
    onRestoreRetracted: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val grainBrush = rememberPewterGrainBrush()
    var isPressed by remember { mutableStateOf(false) }

    val effectiveCanGoBack = canGoBack || canReturnToStartPage

    val depressOffset by animateDpAsState(
        targetValue = if (isPressed) 3.dp else 0.dp,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.ORB_DEPRESS_DURATION_MS,
            easing = LinearEasing,
        ),
        label = "kpt_back_dome_depress",
    )

    val a11yDescription = buildString {
        if (effectiveCanGoBack) {
            append("Back")
        } else {
            append("Back (at start page)")
        }
        if (canGoForward) {
            append(". Hold to go forward")
        }
    }

    val dispatchTap: () -> Unit = {
        if (isRetracted && onRestoreRetracted != null) {
            onRestoreRetracted()
        } else if (canGoBack) {
            onBack()
        } else if (canReturnToStartPage) {
            onReturnToStartPage()
        }
    }

    Box(
        modifier = modifier
            .size(74.dp)
            .metaToolsDropShadow(cornerRadius = 37.dp, isCircle = true)
            .clip(CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = a11yDescription
                onClick {
                    if (effectiveCanGoBack || (isRetracted && onRestoreRetracted != null)) {
                        dispatchTap()
                        true
                    } else {
                        false
                    }
                }
                if (canGoForward) {
                    onLongClick {
                        onForward()
                        true
                    }
                }
            }
            .pointerInput(
                canGoBack,
                canGoForward,
                canReturnToStartPage,
                isRetracted,
                onBack,
                onForward,
                onReturnToStartPage,
                onRestoreRetracted,
            ) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    isPressed = true
                    if (effectiveCanGoBack || canGoForward || isRetracted) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }

                    var upOccurred = false
                    var cancelled = false
                    val completedBeforeLongPress = withTimeoutOrNull(400L) {
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            up.consume()
                            upOccurred = true
                        } else {
                            cancelled = true
                        }
                        true
                    }

                    if (completedBeforeLongPress != null) {
                        isPressed = false
                        if (upOccurred && !cancelled) {
                            dispatchTap()
                        }
                    } else {
                        // Held >= 400ms: trigger Forward navigation when available
                        if (canGoForward) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onForward()
                        } else if (isRetracted && onRestoreRetracted != null) {
                            onRestoreRetracted()
                        }
                        val trailingUp = waitForUpOrCancellation()
                        trailingUp?.consume()
                        isPressed = false
                    }
                }
            }
            .testTag("kpt_back_pod"),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outerRadius = size.minDimension / 2f
            val collarCenter = center
            val ridgeStrokePx = 1.4.dp.toPx()

            // 1. Cast-Pewter Circular Outer Collar (#7D838C + directional bevel + 3% grain)
            drawCircle(
                brush = Brush.linearGradient(
                    0.0f to MetaToolsTokens.PewterHighlightBevel,
                    0.34f to MetaToolsTokens.PewterBase,
                    0.74f to MetaToolsTokens.PewterMidDark,
                    1.0f to MetaToolsTokens.PewterShadowBevel,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
                radius = outerRadius,
                center = collarCenter,
            )
            drawCircle(
                brush = grainBrush,
                radius = outerRadius,
                center = collarCenter,
            )

            // 2. Machined Radial Knurled Rim Ridges (60 deep ticks around cast-pewter collar)
            val knurlCount = 60
            val knurlOuterR = outerRadius - 0.7.dp.toPx()
            val knurlInnerR = outerRadius - 5.4.dp.toPx()
            for (i in 0 until knurlCount) {
                val theta = (2.0 * PI * i) / knurlCount
                val cosT = cos(theta).toFloat()
                val sinT = sin(theta).toFloat()
                val ridgeColor = if (i % 2 == 0) {
                    Color(0xA6DCE4EC)
                } else {
                    Color(0xB31F2329)
                }
                drawLine(
                    color = ridgeColor,
                    start = Offset(
                        collarCenter.x + cosT * knurlInnerR,
                        collarCenter.y + sinT * knurlInnerR,
                    ),
                    end = Offset(
                        collarCenter.x + cosT * knurlOuterR,
                        collarCenter.y + sinT * knurlOuterR,
                    ),
                    strokeWidth = ridgeStrokePx,
                    cap = StrokeCap.Butt,
                )
            }

            // Machined inner bright steel chamfer ring
            val chamferRadius = outerRadius - 6.0.dp.toPx()
            drawCircle(
                brush = Brush.linearGradient(
                    0.0f to Color(0xFFE0E8F0),
                    0.5f to Color(0xFF6B727B),
                    1.0f to Color(0xFF23272D),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
                radius = chamferRadius,
                center = collarCenter,
                style = Stroke(width = 1.8.dp.toPx()),
            )

            // 3. Concave #111317 Socket Crater (61.dp diameter)
            val craterRadius = 30.5.dp.toPx()
            drawCircle(
                color = MetaToolsTokens.PewterCrater,
                radius = craterRadius,
                center = collarCenter,
            )

            // 4. 58.dp Dark Anodized Titanium Dome (#2E343D -> #14171C, depresses +3.dp on press)
            val depressPx = depressOffset.toPx()
            val domeRadius = (29.dp.toPx() - depressPx * 0.22f).coerceAtLeast(20f)
            val domeCenter = Offset(
                x = collarCenter.x + depressPx * 0.36f,
                y = collarCenter.y + depressPx * 0.64f,
            )
            val domeDiameter = domeRadius * 2f
            val domeTopLeft = Offset(domeCenter.x - domeRadius, domeCenter.y - domeRadius)
            val highlightCenter = Offset(
                x = domeTopLeft.x + domeDiameter * 0.30f,
                y = domeTopLeft.y + domeDiameter * 0.28f,
            )

            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to Color(0xFF454E5B),
                    0.28f to MetaToolsTokens.TitaniumDomeTop,
                    0.68f to MetaToolsTokens.TitaniumDomeMid,
                    0.94f to MetaToolsTokens.TitaniumDomeDeep,
                    1.0f to Color(0xFF0B0D10),
                    center = highlightCenter,
                    radius = domeDiameter * 0.92f,
                ),
                radius = domeRadius,
                center = domeCenter,
            )
            drawCircle(
                brush = grainBrush,
                radius = domeRadius,
                center = domeCenter,
            )

            // Subtle machined titanium rim bevel
            drawCircle(
                brush = Brush.linearGradient(
                    0.0f to Color(0x889BA5B2),
                    0.5f to Color(0x3338404B),
                    1.0f to Color(0xAA090B0E),
                    start = domeTopLeft,
                    end = Offset(domeTopLeft.x + domeDiameter, domeTopLeft.y + domeDiameter),
                ),
                radius = domeRadius - 0.8.dp.toPx(),
                center = domeCenter,
                style = Stroke(width = 1.4.dp.toPx()),
            )

            // 5. 8.dp Recessed Emerald Glass LED Pip at 12-o'clock inside Titanium Dome
            val ledCenter = Offset(
                x = domeCenter.x,
                y = domeCenter.y - 16.5.dp.toPx(),
            )
            drawRecessedGlassLedBead(
                center = ledCenter,
                beadDiameterPx = 8.dp.toPx(),
                litColor = if (effectiveCanGoBack) MetaToolsTokens.LedGreen else null,
            )

            // 6. Bold Debossed / Phosphor `◀` Hardware Arrow Glyph
            drawKptBackArrowGlyph(
                arrowCenter = Offset(
                    x = domeCenter.x - 1.5.dp.toPx(),
                    y = domeCenter.y + 4.2.dp.toPx(),
                ),
                enabled = effectiveCanGoBack,
            )

            // 7. Crisp 10-o'clock Specular Pinprick Highlight on Titanium Dome
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to Color.White.copy(alpha = 0.62f),
                    0.4f to Color.White.copy(alpha = 0.22f),
                    1.0f to Color.Transparent,
                    center = highlightCenter,
                    radius = 5.4.dp.toPx(),
                ),
                radius = 5.4.dp.toPx(),
                center = highlightCenter,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = 1.5.dp.toPx(),
                center = highlightCenter,
            )

            // Crater overhang shadow when depressed
            if (depressPx > 0.1f) {
                drawCircle(
                    color = Color.Black.copy(alpha = (depressPx / 3.dp.toPx()) * 0.28f),
                    radius = domeRadius,
                    center = domeCenter,
                )
            }
        }
    }
}

private fun DrawScope.drawKptBackArrowGlyph(
    arrowCenter: Offset,
    enabled: Boolean,
) {
    val halfW = 8.6.dp.toPx()
    val halfH = 8.8.dp.toPx()

    fun trianglePath(shift: Offset): Path = Path().apply {
        // Sculptural left-pointing KPT arrow with slight concave tail notch
        moveTo(arrowCenter.x - halfW + shift.x, arrowCenter.y + shift.y)
        lineTo(arrowCenter.x + halfW * 0.88f + shift.x, arrowCenter.y - halfH + shift.y)
        lineTo(arrowCenter.x + halfW * 0.56f + shift.x, arrowCenter.y + shift.y)
        lineTo(arrowCenter.x + halfW * 0.88f + shift.x, arrowCenter.y + halfH + shift.y)
        close()
    }

    // Bottom-right machined catchlight bevel
    drawPath(
        path = trianglePath(Offset(1.dp.toPx(), 1.1.dp.toPx())),
        color = Color(0x885C6674),
        style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round),
    )

    // Deep sunken debossed cavity
    drawPath(
        path = trianglePath(Offset(-0.6.dp.toPx(), -0.7.dp.toPx())),
        color = Color(0xDD06080B),
    )

    if (enabled) {
        // Subtle phosphor halation glow when Back is active
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to MetaToolsTokens.LedGreen.copy(alpha = 0.26f),
                1.0f to Color.Transparent,
                center = arrowCenter,
                radius = halfH * 1.65f,
            ),
            radius = halfH * 1.65f,
            center = arrowCenter,
        )
        drawPath(
            path = trianglePath(Offset.Zero),
            brush = Brush.linearGradient(
                0.0f to Color(0xFFD9FFE8),
                0.45f to Color(0xFF4DFF94),
                1.0f to Color(0xFF00C853),
                start = Offset(arrowCenter.x - halfW, arrowCenter.y - halfH),
                end = Offset(arrowCenter.x + halfW, arrowCenter.y + halfH),
            ),
        )
    } else {
        // Unlit debossed gunmetal arrow when on root StartPage
        drawPath(
            path = trianglePath(Offset.Zero),
            brush = Brush.linearGradient(
                0.0f to Color(0xFF3E4652),
                1.0f to Color(0xFF232830),
                start = Offset(arrowCenter.x - halfW, arrowCenter.y - halfH),
                end = Offset(arrowCenter.x + halfW, arrowCenter.y + halfH),
            ),
        )
    }
}
