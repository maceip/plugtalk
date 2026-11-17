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
 * `86.dp x 86.dp` (`1.5x` Scaled) Bottom-Right Primary Hardware Island —
 * Cast-Pewter Knurled Tor Orb (`tor_orb_pod`) with `68.dp` 3D Raytraced Acrylic Sphere.
 *
 * Visual & Tactile Spec:
 * - Cast-pewter circular collar (`#7D838C`) with deep `1.5.dp` radial knurled rim ridges +
 *   bright machined steel inner chamfer ring + concave `#111317` crater + `68.dp` 3D sphere.
 * - Connected ([torReady] == `true`): ultraviolet-purple radial gradient
 *   (`#B565FF` at `(30%, 28%)` -> `#7B2CBF` -> `#240046`), crisp white 10-o'clock pinprick
 *   specular highlight, debossed concentric onion vector emblem.
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
            .size(86.dp)
            .metaToolsDropShadow(cornerRadius = 43.dp, isCircle = true)
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
            val ridgeStrokePx = 1.5.dp.toPx()

            // 1. Cast-Pewter Circular Collar (#7D838C + directional bevel + 3% grain)
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

            // 2. Deep 1.5.dp Radial Knurled Rim Ridges (72 machined ticks around 86.dp outer bezel)
            val knurlCount = 72
            val knurlOuterR = outerRadius - 0.8.dp.toPx()
            val knurlInnerR = outerRadius - 6.6.dp.toPx()
            for (i in 0 until knurlCount) {
                val theta = (2.0 * PI * i) / knurlCount
                val cosT = cos(theta).toFloat()
                val sinT = sin(theta).toFloat()
                val ridgeColor = if (i % 2 == 0) {
                    Color(0xA8DCE5EE)
                } else {
                    Color(0xB81E2228)
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
            val chamferRadius = outerRadius - 7.3.dp.toPx()
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
                style = Stroke(width = 2.0.dp.toPx()),
            )

            // 3. Concave #111317 Crater Socket (72.dp diameter)
            val craterRadius = 36.dp.toPx()
            drawCircle(
                color = MetaToolsTokens.PewterCrater,
                radius = craterRadius,
                center = collarCenter,
            )

            // 4. 68.dp (34.dp radius) 3D Raytraced Sphere (depresses +3.dp into crater on touch-down)
            val depressPx = depressOffset.toPx()
            val sphereRadius = (34.dp.toPx() - depressPx * 0.22f).coerceAtLeast(24f)
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

            if (torReady) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to Color(0xFFD69EFF),
                        0.16f to MetaToolsTokens.TorPurpleBright,
                        0.50f to MetaToolsTokens.TorPurpleMid,
                        0.88f to MetaToolsTokens.TorPurpleDeep,
                        1.0f to Color(0xFF100022),
                        center = highlightCenter,
                        radius = sphereDiameter * 0.90f,
                    ),
                    radius = sphereRadius,
                    center = sphereCenter,
                )
                // Subsurface acrylic rim reflection on lower-right hemisphere
                drawCircle(
                    brush = Brush.radialGradient(
                        0.64f to Color.Transparent,
                        0.92f to Color(0x52C77DFF),
                        1.0f to Color.Transparent,
                        center = Offset(
                            sphereCenter.x - sphereRadius * 0.16f,
                            sphereCenter.y - sphereRadius * 0.16f,
                        ),
                        radius = sphereRadius * 1.06f,
                    ),
                    radius = sphereRadius,
                    center = sphereCenter,
                )
            } else {
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to Color(0xFFFFEA70),
                        0.16f to MetaToolsTokens.TorAmberBright,
                        0.50f to MetaToolsTokens.TorAmberMid,
                        0.88f to MetaToolsTokens.TorAmberDeep,
                        1.0f to Color(0xFF2B1800),
                        center = highlightCenter,
                        radius = sphereDiameter * 0.90f,
                    ),
                    radius = sphereRadius,
                    center = sphereCenter,
                )
            }

            // 5. Debossed Concentric Onion Vector Emblem stamped into 68.dp sphere center
            drawDebossedOnionGlyph(
                sphereCenter = sphereCenter,
                glyphRadius = sphereRadius * 0.43f,
                torReady = torReady,
            )

            // 6. Crisp 10-o'clock White Pinprick Specular Highlight
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to Color.White,
                    0.36f to Color.White.copy(alpha = 0.76f),
                    1.0f to Color.Transparent,
                    center = highlightCenter,
                    radius = 6.4.dp.toPx(),
                ),
                radius = 6.4.dp.toPx(),
                center = highlightCenter,
            )
            drawCircle(
                color = Color.White,
                radius = 2.1.dp.toPx(),
                center = highlightCenter,
            )

            // Crater overhang shadow when depressed
            if (depressPx > 0.1f) {
                drawCircle(
                    color = Color.Black.copy(alpha = (depressPx / 3.dp.toPx()) * 0.26f),
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
    val shadowStroke = if (torReady) Color(0xBA140028) else Color(0xBA3B2200)
    val catchlightStroke = if (torReady) Color(0x88E0B0FF) else Color(0x88FFE680)
    val strokePx = 2.0.dp.toPx()

    fun buildOnionPath(scale: Float, shift: Offset): Path {
        val r = glyphRadius * scale
        val cx = sphereCenter.x + shift.x
        val cy = sphereCenter.y + shift.y + r * 0.14f
        return Path().apply {
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

    val catchShift = Offset(1.0.dp.toPx(), 1.1.dp.toPx())
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
