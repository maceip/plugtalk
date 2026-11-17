package dev.onionvault.ui.pods

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
 * `58.dp x 58.dp` Left Floating Hardware Island — Cast-Pewter Knurled Tor Orb (`tor_orb_pod`).
 *
 * Visual & Tactile Spec:
 * - Cast-pewter circular collar (`#7D838C`) with procedural radial `1.dp` knurled rim ridges +
 *   concave `#111317` crater + `46.dp` 3D raytraced sphere.
 * - Connected ([torReady] == `true`): ultraviolet-purple radial gradient
 *   (`#B565FF` at `(30%, 28%)` -> `#7B2CBF` -> `#240046`), crisp white 10-o'clock pinprick
 *   specular highlight, debossed concentric onion vector glyph.
 * - Bootstrapping ([torReady] == `false`): safety amber (`#FFD000` -> `#7A5200`) with specular
 *   highlight rotating in discrete `30deg` ticks at `6fps` (`166ms` step).
 * - Touch-down depresses sphere `3.dp` (`75ms` linear `tween(75, LinearEasing)` + haptic tick);
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
    val grainBrush = rememberPewterGrainBrush()
    var isPressed by remember { mutableStateOf(false) }

    val depressOffset by animateDpAsState(
        targetValue = if (isPressed) 3.dp else 0.dp,
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

    val statusDesc = if (torReady) {
        "Tor ready. Tap for circuit telemetry, hold to rotate identity"
    } else {
        "Tor bootstrapping $bootstrapPercent percent. Tap for circuit telemetry"
    }

    Box(
        modifier = modifier
            .size(58.dp)
            .metaToolsDropShadow(cornerRadius = 29.dp, isCircle = true)
            .clip(CircleShape)
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
                        // Held >= 400ms: trigger Tor circuit/identity rotation
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
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outerRadius = size.minDimension / 2f
            val collarCenter = center
            val oneDp = 1.dp.toPx()

            // 1. Cast-Pewter Circular Collar (#7D838C + directional bevel + 3% grain)
            drawCircle(
                brush = Brush.linearGradient(
                    0.0f to MetaToolsTokens.PewterHighlightBevel,
                    0.35f to MetaToolsTokens.PewterBase,
                    0.75f to MetaToolsTokens.PewterMidDark,
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

            // 2. Procedural Radial 1.dp Knurled Rim Ridges (56 machined ticks around outer bezel)
            val knurlCount = 56
            val knurlOuterR = outerRadius - 0.6.dp.toPx()
            val knurlInnerR = outerRadius - 4.4.dp.toPx()
            for (i in 0 until knurlCount) {
                val theta = (2.0 * PI * i) / knurlCount
                val cosT = cos(theta).toFloat()
                val sinT = sin(theta).toFloat()
                val ridgeColor = if (i % 2 == 0) {
                    Color(0x99D6DEE6)
                } else {
                    Color(0xAA23272D)
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
                    strokeWidth = oneDp,
                    cap = StrokeCap.Butt,
                )
            }

            // Machined inner bright steel chamfer ring
            val chamferRadius = outerRadius - 4.8.dp.toPx()
            drawCircle(
                brush = Brush.linearGradient(
                    0.0f to Color(0xFFDCE4EC),
                    0.5f to Color(0xFF6B727B),
                    1.0f to Color(0xFF2B3036),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                ),
                radius = chamferRadius,
                center = collarCenter,
                style = Stroke(width = 1.4.dp.toPx()),
            )

            // 3. Concave #111317 Crater Socket
            val craterRadius = 24.2.dp.toPx()
            drawCircle(
                color = MetaToolsTokens.PewterCrater,
                radius = craterRadius,
                center = collarCenter,
            )

            // 4. 46.dp 3D Raytraced Sphere (depresses +3.dp into crater on touch-down)
            val depressPx = depressOffset.toPx()
            val sphereRadius = (23.dp.toPx() - depressPx * 0.22f).coerceAtLeast(16f)
            val sphereCenter = Offset(
                x = collarCenter.x + depressPx * 0.38f,
                y = collarCenter.y + depressPx * 0.65f,
            )

            // Specular & gradient focal point:
            // - Connected: 10 o'clock (30%, 28% inside sphere bounding box)
            // - Bootstrapping: discrete 30deg ticks at 6fps (166ms step)
            val sphereTopLeft = Offset(sphereCenter.x - sphereRadius, sphereCenter.y - sphereRadius)
            val sphereDiameter = sphereRadius * 2f

            val highlightCenter = if (torReady) {
                Offset(
                    x = sphereTopLeft.x + sphereDiameter * 0.30f,
                    y = sphereTopLeft.y + sphereDiameter * 0.28f,
                )
            } else {
                // 10 o'clock is -135 deg (-2.356 rad); rotate in discrete +30deg ticks!
                val angleRad = ((-135.0 + discreteTickStep * 30.0) * PI) / 180.0
                val orbitR = sphereRadius * 0.54f
                Offset(
                    x = sphereCenter.x + cos(angleRad).toFloat() * orbitR,
                    y = sphereCenter.y + sin(angleRad).toFloat() * orbitR,
                )
            }

            if (torReady) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to MetaToolsTokens.TorPurpleBright,
                        0.46f to MetaToolsTokens.TorPurpleMid,
                        0.88f to MetaToolsTokens.TorPurpleDeep,
                        1.0f to Color(0xFF120024),
                        center = highlightCenter,
                        radius = sphereDiameter * 0.88f,
                    ),
                    radius = sphereRadius,
                    center = sphereCenter,
                )
                // Subtle bottom-right bounce rim reflection
                drawCircle(
                    brush = Brush.radialGradient(
                        0.65f to Color.Transparent,
                        0.93f to Color(0x40C77DFF),
                        1.0f to Color.Transparent,
                        center = Offset(
                            sphereCenter.x - sphereRadius * 0.15f,
                            sphereCenter.y - sphereRadius * 0.15f,
                        ),
                        radius = sphereRadius * 1.05f,
                    ),
                    radius = sphereRadius,
                    center = sphereCenter,
                )
            } else {
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to MetaToolsTokens.TorAmberBright,
                        0.48f to MetaToolsTokens.TorAmberMid,
                        0.88f to MetaToolsTokens.TorAmberDeep,
                        1.0f to Color(0xFF2E1B00),
                        center = highlightCenter,
                        radius = sphereDiameter * 0.88f,
                    ),
                    radius = sphereRadius,
                    center = sphereCenter,
                )
            }

            // 5. Debossed Concentric Onion Vector Glyph stamped into sphere center
            drawDebossedOnionGlyph(
                sphereCenter = sphereCenter,
                glyphRadius = sphereRadius * 0.42f,
                torReady = torReady,
            )

            // 6. Crisp White Pinprick Specular Highlight
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to Color.White,
                    0.35f to Color.White.copy(alpha = 0.72f),
                    1.0f to Color.Transparent,
                    center = highlightCenter,
                    radius = 4.2.dp.toPx(),
                ),
                radius = 4.2.dp.toPx(),
                center = highlightCenter,
            )
            drawCircle(
                color = Color.White,
                radius = 1.35.dp.toPx(),
                center = highlightCenter,
            )

            // Crater overhang shadow when depressed
            if (depressPx > 0.1f) {
                drawCircle(
                    color = Color.Black.copy(alpha = (depressPx / 3.dp.toPx()) * 0.24f),
                    radius = sphereRadius,
                    center = sphereCenter,
                )
            }
        }
    }
}

private fun DrawScope.drawDebossedOnionGlyph(
    sphereCenter: Offset,
    glyphRadius: Float,
    torReady: Boolean,
) {
    val shadowStroke = if (torReady) Color(0xAA140028) else Color(0xAA3B2200)
    val catchlightStroke = if (torReady) Color(0x77D89EFF) else Color(0x77FFE680)
    val strokePx = 1.35.dp.toPx()

    fun buildOnionPath(scale: Float, shift: Offset): Path {
        val r = glyphRadius * scale
        val cx = sphereCenter.x + shift.x
        val cy = sphereCenter.y + shift.y + r * 0.14f
        return Path().apply {
            // Pointed onion tip at top -> bulbous lower belly -> base -> up left belly
            moveTo(cx, cy - r * 1.18f)
            cubicTo(
                cx + r * 0.22f, cy - r * 0.65f,
                cx + r * 0.96f, cy - r * 0.35f,
                cx + r * 0.88f, cy + r * 0.28f,
            )
            cubicTo(
                cx + r * 0.80f, cy + r * 0.82f,
                cx + r * 0.35f, cy + r * 0.98f,
                cx, cy + r * 0.98f,
            )
            cubicTo(
                cx - r * 0.35f, cy + r * 0.98f,
                cx - r * 0.80f, cy + r * 0.82f,
                cx - r * 0.88f, cy + r * 0.28f,
            )
            cubicTo(
                cx - r * 0.96f, cy - r * 0.35f,
                cx - r * 0.22f, cy - r * 0.65f,
                cx, cy - r * 1.18f,
            )
            close()
        }
    }

    val catchShift = Offset(0.7.dp.toPx(), 0.8.dp.toPx())
    val scales = floatArrayOf(1.0f, 0.64f, 0.32f)
    for (s in scales) {
        drawPath(
            path = buildOnionPath(s, catchShift),
            color = catchlightStroke,
            style = Stroke(width = strokePx),
        )
        drawPath(
            path = buildOnionPath(s, Offset.Zero),
            color = shadowStroke,
            style = Stroke(width = strokePx),
        )
    }
}
