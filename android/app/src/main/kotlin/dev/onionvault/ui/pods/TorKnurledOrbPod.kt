package dev.onionvault.ui.pods

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.onionvault.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `104.dp x 104.dp` Bottom-Left Primary Hardware Island (`metatools-reference.jpg` 1:1) —
 * 3D Perspective Purple Knurled Cylinder-and-Dome Tor Orb (`tor_orb_pod`).
 *
 * **THE ONLY ROUND BALL IN THE ENTIRE UI.**
 *
 * - Renders `R.drawable.pod_tor_purple_orb` (`pod_tor_purple_orb.webp`) tucked bottom-left just
 *   below-left of the chunky pewter CRT TV.
 * - Connected ([torReady] == `true`): full ultraviolet-purple 3D dome & chrome collar.
 * - Bootstrapping ([torReady] == `false`): warm amber hue-matrix shift + discrete `30deg` `6fps`
 *   rotating specular orbital beacon.
 * - Touch-down depresses `2.5.dp` (`75ms` linear tween + haptic tick);
 *   **Tap** toggles `PhotoSoapSideRemote` into Tor Circuit Mode;
 *   **Long-press (`400ms`)** rotates Tor circuit/identity!
 */
@Composable
fun TorKnurledOrbPod(
    torReady: Boolean,
    onToggleTorCircuitRemote: () -> Unit,
    onRotateTorIdentity: () -> Unit,
    modifier: Modifier = Modifier,
    bootstrapPercent: Int = 0,
    overrideBootTickStep: Int? = null,
) {
    val haptics = LocalHapticFeedback.current
    val spritePainter = painterResource(R.drawable.pod_tor_purple_orb)
    var isPressed by remember { mutableStateOf(false) }

    val depressOffset by animateDpAsState(
        targetValue = if (isPressed) 2.5.dp else 0.dp,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.ORB_DEPRESS_DURATION_MS,
            easing = LinearEasing,
        ),
        label = "tor_sphere_depress",
    )

    val discreteTickStep = when {
        overrideBootTickStep != null -> overrideBootTickStep.mod(12)
        !torReady -> {
            val transition = rememberInfiniteTransition(label = "tor_orb_6fps_stepper")
            val rawStep by transition.animateFloat(
                initialValue = 0f,
                targetValue = 12f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 12 * MetaToolsTokens.TOR_BOOT_TICK_MS,
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "tor_orb_discrete_step",
            )
            rawStep.toInt().mod(12)
        }
        else -> 0
    }

    val amberBootColorFilter = remember(torReady) {
        if (torReady) {
            null
        } else {
            ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        1.15f, 0.25f, 0.15f, 0f, 18f,
                        0.45f, 0.65f, 0.10f, 0f, 8f,
                        0.05f, 0.08f, 0.22f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
    }

    val statusDesc = if (torReady) {
        "Tor ready. Tap for circuit telemetry, hold to rotate identity"
    } else {
        "Tor bootstrapping $bootstrapPercent percent. Tap for circuit telemetry"
    }

    Box(
        modifier = modifier
            .size(104.dp)
            .metaToolsDropShadow(cornerRadius = 52.dp, isCircle = true, insetHorizontal = 5.dp, insetVertical = 5.dp)
            .semantics {
                role = Role.Button
                contentDescription = statusDesc
                onClick {
                    onToggleTorCircuitRemote()
                    true
                }
                onLongClick {
                    onRotateTorIdentity()
                    true
                }
            }
            .pointerInput(onToggleTorCircuitRemote, onRotateTorIdentity) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    isPressed = true
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)

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
                            onToggleTorCircuitRemote()
                        }
                    } else {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRotateTorIdentity()
                        val trailingUp = waitForUpOrCancellation()
                        trailingUp?.consume()
                        isPressed = false
                    }
                }
            }
            .testTag("tor_orb_pod"),
    ) {
        val pressScale = if (isPressed) 0.965f else 1.0f
        Image(
            painter = spritePainter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = amberBootColorFilter,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                    translationX = depressOffset.toPx() * 0.42f
                    translationY = depressOffset.toPx() * 0.68f
                },
        )
        if (!torReady) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val orbitR = size.minDimension * 0.30f
                val angleRad = ((-135.0 + discreteTickStep * 30.0) * PI) / 180.0
                val dotCenter = Offset(
                    x = center.x + cos(angleRad).toFloat() * orbitR,
                    y = center.y + sin(angleRad).toFloat() * orbitR,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to Color.White,
                        0.45f to MetaToolsTokens.TorAmberBright,
                        1.0f to Color.Transparent,
                        center = dotCenter,
                        radius = 7.dp.toPx(),
                    ),
                    radius = 7.dp.toPx(),
                    center = dotCenter,
                )
            }
        }
    }
}

private fun DrawScope.drawProceduralOrbFallback(
    grainBrush: androidx.compose.ui.graphics.ShaderBrush,
    torReady: Boolean,
    depressPx: Float,
    discreteTickStep: Int,
) {
    val outerRadius = size.minDimension / 2f
    val collarCenter = center
    val ridgeStrokePx = 1.6.dp.toPx()

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
    drawCircle(brush = grainBrush, radius = outerRadius, center = collarCenter)

    val knurlCount = 72
    val knurlOuterR = outerRadius - 0.8.dp.toPx()
    val knurlInnerR = outerRadius - 7.5.dp.toPx()
    for (i in 0 until knurlCount) {
        val theta = (2.0 * PI * i) / knurlCount
        val cosT = cos(theta).toFloat()
        val sinT = sin(theta).toFloat()
        val ridgeColor = if (i % 2 == 0) Color(0xA8DCE5EE) else Color(0xB81E2228)
        drawLine(
            color = ridgeColor,
            start = Offset(collarCenter.x + cosT * knurlInnerR, collarCenter.y + sinT * knurlInnerR),
            end = Offset(collarCenter.x + cosT * knurlOuterR, collarCenter.y + sinT * knurlOuterR),
            strokeWidth = ridgeStrokePx,
            cap = StrokeCap.Butt,
        )
    }

    val chamferRadius = outerRadius - 8.4.dp.toPx()
    drawCircle(
        brush = Brush.linearGradient(
            0.0f to Color(0xFFE4ECF4),
            0.5f to Color(0xFF6B727B),
            1.0f to Color(0xFF262B31),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        radius = chamferRadius,
        center = collarCenter,
        style = Stroke(width = 2.4.dp.toPx()),
    )

    val craterRadius = outerRadius * 0.82f
    drawCircle(color = MetaToolsTokens.PewterCrater, radius = craterRadius, center = collarCenter)

    val sphereRadius = (outerRadius * 0.77f - depressPx * 0.22f).coerceAtLeast(28f)
    val sphereCenter = Offset(
        x = collarCenter.x + depressPx * 0.38f,
        y = collarCenter.y + depressPx * 0.65f,
    )
    val sphereTopLeft = Offset(sphereCenter.x - sphereRadius, sphereCenter.y - sphereRadius)
    val sphereDiameter = sphereRadius * 2f

    val highlightCenter = if (torReady) {
        Offset(
            x = sphereTopLeft.x + sphereDiameter * 0.30f,
            y = sphereTopLeft.y + sphereDiameter * 0.28f,
        )
    } else {
        val angleRad = ((-135.0 + discreteTickStep * 30.0) * PI) / 180.0
        val orbitR = sphereRadius * 0.54f
        Offset(
            x = sphereCenter.x + cos(angleRad).toFloat() * orbitR,
            y = sphereCenter.y + sin(angleRad).toFloat() * orbitR,
        )
    }

    drawCircle(
        brush = Brush.radialGradient(
            0.0f to if (torReady) Color(0xFFD69EFF) else Color(0xFFFFEA70),
            0.16f to if (torReady) MetaToolsTokens.TorPurpleBright else MetaToolsTokens.TorAmberBright,
            0.50f to if (torReady) MetaToolsTokens.TorPurpleMid else MetaToolsTokens.TorAmberMid,
            0.88f to if (torReady) MetaToolsTokens.TorPurpleDeep else MetaToolsTokens.TorAmberDeep,
            1.0f to if (torReady) Color(0xFF100022) else Color(0xFF2B1800),
            center = highlightCenter,
            radius = sphereDiameter * 0.90f,
        ),
        radius = sphereRadius,
        center = sphereCenter,
    )

    drawDebossedOnionGlyph(
        sphereCenter = sphereCenter,
        glyphRadius = sphereRadius * 0.43f,
        torReady = torReady,
    )

    drawCircle(
        brush = Brush.radialGradient(
            0.0f to Color.White,
            0.36f to Color.White.copy(alpha = 0.76f),
            1.0f to Color.Transparent,
            center = highlightCenter,
            radius = 7.dp.toPx(),
        ),
        radius = 7.dp.toPx(),
        center = highlightCenter,
    )
}

private fun DrawScope.drawDebossedOnionGlyph(
    sphereCenter: Offset,
    glyphRadius: Float,
    torReady: Boolean,
) {
    val shadowStroke = if (torReady) Color(0xBA140028) else Color(0xBA3B2200)
    val catchlightStroke = if (torReady) Color(0x88E0B0FF) else Color(0x88FFE680)
    val strokePx = 2.2.dp.toPx()

    fun buildOnionPath(scale: Float, shift: Offset): Path {
        val r = glyphRadius * scale
        val cx = sphereCenter.x + shift.x
        val cy = sphereCenter.y + shift.y + r * 0.14f
        return Path().apply {
            moveTo(cx, cy - r * 1.18f)
            cubicTo(cx + r * 0.22f, cy - r * 0.65f, cx + r * 0.96f, cy - r * 0.35f, cx + r * 0.88f, cy + r * 0.28f)
            cubicTo(cx + r * 0.80f, cy + r * 0.82f, cx + r * 0.35f, cy + r * 0.98f, cx, cy + r * 0.98f)
            cubicTo(cx - r * 0.35f, cy + r * 0.98f, cx - r * 0.80f, cy + r * 0.82f, cx - r * 0.88f, cy + r * 0.28f)
            cubicTo(cx - r * 0.96f, cy - r * 0.35f, cx - r * 0.22f, cy - r * 0.65f, cx, cy - r * 1.18f)
            close()
        }
    }

    val catchShift = Offset(1.0.dp.toPx(), 1.1.dp.toPx())
    for (s in floatArrayOf(1.0f, 0.64f, 0.32f)) {
        drawPath(path = buildOnionPath(s, catchShift), color = catchlightStroke, style = Stroke(width = strokePx))
        drawPath(path = buildOnionPath(s, Offset.Zero), color = shadowStroke, style = Stroke(width = strokePx))
    }
}
