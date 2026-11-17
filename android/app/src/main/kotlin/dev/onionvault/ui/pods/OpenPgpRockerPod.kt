package dev.onionvault.ui.pods

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * `54.dp x 62.dp` Tilted (`-8deg`) Right Floating Hardware Island —
 * Translucent Ruby-Orange Polycarbonate OpenPGP Rocker Switch (`pgp_rocker_pod`).
 *
 * Visual & Tactile Spec:
 * - Translucent ruby-orange polycarbonate casing (`#E63900` at `88%` alpha over dark metallic
 *   plate, `2.dp` `#5C1000` seam, crisp internal refraction highlights, center channel steel
 *   coil spring + ribbed `#AEB5BD` aluminum slider latch matching `metatools-reference.jpg`).
 * - **Contextual LED Glow**: Bright internal orange/emerald LED illuminates behind the translucent
 *   block whenever [hasPgpContent] is `true` (page DOM, selection, or clipboard contains
 *   `-----BEGIN PGP...` or an editable PGP field)!
 * - **Tap** invokes [onOpenPgpTools] (Keyring / PGP Tools Pod).
 * - **Dragging the aluminum latch upward** (`dy < -18.dp`) triggers [onQuickVerifyOrDecrypt] +
 *   mechanical spring-return!
 */
@Composable
fun OpenPgpRockerPod(
    hasPgpContent: Boolean,
    onOpenPgpTools: () -> Unit,
    onQuickVerifyOrDecrypt: () -> Unit,
    modifier: Modifier = Modifier,
    hasArmoredCiphertextOrKey: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val latchOffsetPx = remember { Animatable(0f) }

    val maxUpwardTravelPx = with(density) { 21.dp.toPx() }
    val triggerThresholdPx = with(density) { 18.dp.toPx() }

    val internalLedPulse = if (hasPgpContent) {
        val transition = rememberInfiniteTransition(label = "pgp_internal_led_pulse")
        val pulse by transition.animateFloat(
            initialValue = 0.78f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pgp_led_intensity",
        )
        pulse
    } else {
        0f
    }

    val cornerRadius = 11.dp
    val podShape = RoundedCornerShape(cornerRadius)
    val a11yDescription = if (hasPgpContent) {
        "OpenPGP active on page. Tap for keyring, slide latch up to decrypt or verify"
    } else {
        "OpenPGP Keyring and tools"
    }

    Box(
        modifier = modifier
            .size(width = 54.dp, height = 62.dp)
            .metaToolsDropShadow(cornerRadius = cornerRadius, rotationDegrees = -8f)
            .graphicsLayer {
                rotationZ = -8f
            }
            .clip(podShape)
            .semantics {
                role = Role.Button
                contentDescription = a11yDescription
                onClick {
                    onOpenPgpTools()
                    true
                }
            }
            .pointerInput(onOpenPgpTools, onQuickVerifyOrDecrypt) {
                val tapSlopPx = viewConfiguration.touchSlop * 1.5f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var totalDx = 0f
                    var totalDy = 0f
                    var latchFired = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                            ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val delta = change.positionChange()
                        totalDx += delta.x
                        totalDy += delta.y
                        change.consume()

                        val clampedUpward = totalDy.coerceIn(-maxUpwardTravelPx, 0f)
                        scope.launch {
                            latchOffsetPx.snapTo(clampedUpward)
                        }

                        if (!latchFired && totalDy <= -triggerThresholdPx) {
                            latchFired = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onQuickVerifyOrDecrypt()
                        }
                    }

                    // Mechanical coil spring snap-back to resting stop
                    scope.launch {
                        latchOffsetPx.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessHigh,
                            ),
                        )
                    }

                    if (!latchFired && abs(totalDx) <= tapSlopPx && abs(totalDy) <= tapSlopPx) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpenPgpTools()
                    }
                }
            }
            .testTag("pgp_rocker_pod"),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rPx = cornerRadius.toPx()
            val corner = CornerRadius(rPx, rPx)
            val twoDp = 2.dp.toPx()

            // 1. Dark Machined Backplate (#181A1F) beneath Translucent Polycarbonate
            drawRoundRect(
                color = MetaToolsTokens.PolycarbonateBackplate,
                cornerRadius = corner,
            )

            // 2. Contextual Internal LED Glow (ONLY when real PGP content is detected!)
            if (hasPgpContent && internalLedPulse > 0f) {
                val ledPrimary = if (hasArmoredCiphertextOrKey) {
                    MetaToolsTokens.PgpInternalLedEmerald
                } else {
                    MetaToolsTokens.PgpInternalLedAmber
                }
                drawRoundRect(
                    brush = Brush.radialGradient(
                        0.0f to Color.White.copy(alpha = 0.82f * internalLedPulse),
                        0.28f to ledPrimary.copy(alpha = 0.85f * internalLedPulse),
                        0.68f to MetaToolsTokens.PgpInternalLedAmber.copy(alpha = 0.55f * internalLedPulse),
                        1.0f to Color.Transparent,
                        center = Offset(size.width * 0.5f, size.height * 0.34f),
                        radius = size.maxDimension * 0.62f,
                    ),
                    cornerRadius = corner,
                )
            }

            // 3. Internal Molded Corner Bosses & Structural Ribs (visible through 88% Ruby Plastic)
            val bossOffset = 8.dp.toPx()
            val bossRadius = 2.6.dp.toPx()
            val bosses = listOf(
                Offset(bossOffset, bossOffset),
                Offset(size.width - bossOffset, bossOffset),
                Offset(bossOffset, size.height - bossOffset),
                Offset(size.width - bossOffset, size.height - bossOffset),
            )
            bosses.forEach { boss ->
                drawCircle(
                    color = Color(0x55290800),
                    radius = bossRadius,
                    center = boss,
                )
                drawCircle(
                    color = Color(0x44FF9068),
                    radius = bossRadius,
                    center = boss,
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }

            // 4. Translucent Ruby-Orange Polycarbonate Block (#E63900 at 88% alpha)
            val rubyTop = if (hasPgpContent) Color(0xE0FF5C1A) else Color(0xE0D43200)
            val rubyMid = if (hasPgpContent) MetaToolsTokens.PolycarbonateRuby else Color(0xE0A82200)
            val rubyDeep = if (hasPgpContent) Color(0xD88F1D00) else Color(0xE05E0F00)
            drawRoundRect(
                brush = Brush.linearGradient(
                    0.0f to rubyTop,
                    0.48f to rubyMid,
                    1.0f to rubyDeep,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
                cornerRadius = corner,
            )

            // 2.dp #5C1000 Outer Mold Seam
            drawRoundRect(
                color = MetaToolsTokens.PolycarbonateSeam,
                topLeft = Offset(twoDp / 2f, twoDp / 2f),
                size = Size(size.width - twoDp, size.height - twoDp),
                cornerRadius = CornerRadius(rPx - twoDp / 2f, rPx - twoDp / 2f),
                style = Stroke(width = twoDp),
            )

            // Crisp internal polycarbonate refraction highlight bevel
            val refractInset = 2.6.dp.toPx()
            drawRoundRect(
                brush = Brush.linearGradient(
                    0.0f to MetaToolsTokens.PolycarbonateRefraction,
                    0.4f to Color(0x28FF8A5C),
                    1.0f to Color(0x55360700),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
                topLeft = Offset(refractInset, refractInset),
                size = Size(size.width - refractInset * 2f, size.height - refractInset * 2f),
                cornerRadius = CornerRadius(
                    (rPx - refractInset).coerceAtLeast(2f),
                    (rPx - refractInset).coerceAtLeast(2f),
                ),
                style = Stroke(width = 1.2.dp.toPx()),
            )

            // 5. Recessed Center Metallic Channel + Helical Coil Spring + Ribbed Aluminum Slider Latch
            drawSpringAndAluminumLatch(
                latchUpwardShiftPx = latchOffsetPx.value,
                maxTravelPx = maxUpwardTravelPx.coerceAtLeast(1f),
            )
        }
    }
}

private fun DrawScope.drawSpringAndAluminumLatch(
    latchUpwardShiftPx: Float, // 0f at rest, negative when dragged upward
    maxTravelPx: Float,
) {
    val channelW = 20.dp.toPx()
    val channelH = 40.dp.toPx()
    val channelLeft = (size.width - channelW) / 2f
    val channelTop = (size.height - channelH) / 2f
    val channelRadius = 3.5.dp.toPx()

    // Brushed steel outer bezel frame around center channel
    drawRoundRect(
        brush = Brush.linearGradient(
            0.0f to Color(0xFFC8D0D8),
            0.5f to Color(0xFF707780),
            1.0f to Color(0xFF2D3138),
            start = Offset(channelLeft, channelTop),
            end = Offset(channelLeft + channelW, channelTop + channelH),
        ),
        topLeft = Offset(channelLeft - 1.8.dp.toPx(), channelTop - 1.8.dp.toPx()),
        size = Size(channelW + 3.6.dp.toPx(), channelH + 3.6.dp.toPx()),
        cornerRadius = CornerRadius(channelRadius + 1.5.dp.toPx(), channelRadius + 1.5.dp.toPx()),
    )

    // Deep dark recessed channel slot
    drawRoundRect(
        color = Color(0xFF0B0D10),
        topLeft = Offset(channelLeft, channelTop),
        size = Size(channelW, channelH),
        cornerRadius = CornerRadius(channelRadius, channelRadius),
    )

    // Ribbed #AEB5BD Aluminum Slider Latch (resting near bottom of channel, sliding up on drag)
    val latchW = 17.5.dp.toPx()
    val latchH = 15.dp.toPx()
    val latchLeft = (size.width - latchW) / 2f
    val restingLatchTop = channelTop + channelH - latchH - 1.8.dp.toPx()
    val currentLatchTop = (restingLatchTop + latchUpwardShiftPx)
        .coerceIn(channelTop + 5.dp.toPx(), restingLatchTop)

    // Steel Coil Spring occupying space between channelTop + 3dp and currentLatchTop - 2.2dp
    val springTop = channelTop + 3.dp.toPx()
    val springBottom = (currentLatchTop - 2.2.dp.toPx()).coerceAtLeast(springTop + 3.dp.toPx())
    val springHeight = springBottom - springTop
    val coilCount = 5
    val coilLeft = channelLeft + 4.4.dp.toPx()
    val coilRight = channelLeft + channelW - 4.4.dp.toPx()
    val stepY = springHeight / coilCount

    for (i in 0 until coilCount) {
        val y0 = springTop + i * stepY
        val y1 = y0 + stepY * 0.52f
        // Back dark wire turn
        drawLine(
            color = Color(0xFF2E333B),
            start = Offset(coilRight, y0 + stepY * 0.18f),
            end = Offset(coilLeft, y1 + stepY * 0.22f),
            strokeWidth = 1.1.dp.toPx(),
            cap = StrokeCap.Round,
        )
        // Front specular steel wire turn
        drawLine(
            color = Color(0xFFD8E0E8),
            start = Offset(coilLeft, y0),
            end = Offset(coilRight, y1),
            strokeWidth = 1.35.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }

    // Cast shadow immediately beneath aluminum latch inside channel
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.72f),
        topLeft = Offset(latchLeft + 0.8.dp.toPx(), currentLatchTop + 1.8.dp.toPx()),
        size = Size(latchW, latchH),
        cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
    )

    // Machined #AEB5BD Aluminum Slider Latch Body
    val compressionRatio = (-latchUpwardShiftPx / maxTravelPx).coerceIn(0f, 1f)
    drawRoundRect(
        brush = Brush.verticalGradient(
            0.0f to MetaToolsTokens.AluminumLatchLight,
            0.45f to MetaToolsTokens.AluminumLatch,
            1.0f to MetaToolsTokens.AluminumLatchShadow,
            startY = currentLatchTop,
            endY = currentLatchTop + latchH,
        ),
        topLeft = Offset(latchLeft, currentLatchTop),
        size = Size(latchW, latchH),
        cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
    )
    drawRoundRect(
        color = if (compressionRatio > 0.7f) {
            MetaToolsTokens.PgpInternalLedEmerald.copy(alpha = 0.75f)
        } else {
            MetaToolsTokens.AluminumLatchLight
        },
        topLeft = Offset(latchLeft + 0.5.dp.toPx(), currentLatchTop + 0.5.dp.toPx()),
        size = Size(latchW - 1.dp.toPx(), latchH - 1.dp.toPx()),
        cornerRadius = CornerRadius(2.2.dp.toPx(), 2.2.dp.toPx()),
        style = Stroke(width = 0.9.dp.toPx()),
    )

    // 4 Horizontal Machined Grip Ridges on Aluminum Slider Latch
    val ridgeMarginX = 2.6.dp.toPx()
    for (r in 0 until 4) {
        val ry = currentLatchTop + 3.4.dp.toPx() + r * 2.9.dp.toPx()
        drawLine(
            color = Color(0xFF424850),
            start = Offset(latchLeft + ridgeMarginX, ry),
            end = Offset(latchLeft + latchW - ridgeMarginX, ry),
            strokeWidth = 0.9.dp.toPx(),
        )
        drawLine(
            color = Color(0xFFE8EEF5),
            start = Offset(latchLeft + ridgeMarginX, ry + 0.9.dp.toPx()),
            end = Offset(latchLeft + latchW - ridgeMarginX, ry + 0.9.dp.toPx()),
            strokeWidth = 0.8.dp.toPx(),
        )
    }
}
