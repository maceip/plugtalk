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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.onionvault.R
import kotlin.math.abs
import kotlinx.coroutines.launch

private const val DOUBLE_TAP_WINDOW_MS = 260L
private const val SWITCHBLADE_ROTATION_DEG = 20f

/**
 * `88.dp x 114.dp` Mid-Right `+20 deg` Clockwise Translucent Red Switchblade (`pgp_rocker_pod`) —
 * `metatools-reference.jpg` 1:1.
 *
 * Visual & Tactile Spec:
 * - Renders `R.drawable.pod_red_switchblade` (`pod_red_switchblade.webp`) tilted `+20f` clockwise
 *   above `PhotoSoapSideRemote`.
 * - **Contextual LED Glow**: Internal emerald/amber LED illuminates behind the translucent red
 *   polycarbonate housing whenever [hasPgpContent] is `true`.
 * - **1 Click** invokes [onNewTab] (New Tab).
 * - **2 Clicks** (double-tap within `260ms`) invokes [onOpenPgpTools] (`KeyringHardwarePod` with
 *   instant 1-tap page OpenPGP actions + full Keyring).
 * - **Dragging the spring latch upward** (`dy <= -18.dp`) triggers [onQuickVerifyOrDecrypt] +
 *   mechanical spring-return!
 */
@Composable
fun OpenPgpRockerPod(
    hasPgpContent: Boolean,
    onOpenPgpTools: () -> Unit,
    onQuickVerifyOrDecrypt: () -> Unit,
    modifier: Modifier = Modifier,
    onNewTab: () -> Unit = {},
    hasArmoredCiphertextOrKey: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val latchOffsetPx = remember { Animatable(0f) }
    val spritePainter = painterResource(R.drawable.pod_red_switchblade)

    val maxUpwardTravelPx = with(density) { 30.dp.toPx() }
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

    val cornerRadius = 14.dp
    val a11yDescription = if (hasPgpContent) {
        "New tab on single tap, OpenPGP keyring and page actions on double tap, slide latch up to decrypt or verify"
    } else {
        "New tab on single tap, OpenPGP keyring on double tap"
    }

    Box(
        modifier = modifier
            .size(width = 82.dp, height = 124.dp)
            .metaToolsDropShadow(
                cornerRadius = cornerRadius,
                rotationDegrees = SWITCHBLADE_ROTATION_DEG,
                insetHorizontal = 9.dp,
                insetVertical = 10.dp,
            )
            .graphicsLayer {
                rotationZ = SWITCHBLADE_ROTATION_DEG
            }
            .semantics {
                role = Role.Button
                contentDescription = a11yDescription
                onClick {
                    onNewTab()
                    true
                }
            }
            .pointerInput(onNewTab, onOpenPgpTools, onQuickVerifyOrDecrypt) {
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
                        val secondDown = withTimeoutOrNull(DOUBLE_TAP_WINDOW_MS) {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                        if (secondDown == null) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNewTab()
                        } else {
                            secondDown.consume()
                            var secondDx = 0f
                            var secondDy = 0f
                            var secondLatchFired = false
                            while (true) {
                                val event2 = awaitPointerEvent()
                                val change2 = event2.changes.firstOrNull { it.id == secondDown.id }
                                    ?: event2.changes.firstOrNull()
                                    ?: break
                                if (!change2.pressed) {
                                    change2.consume()
                                    break
                                }
                                val delta2 = change2.positionChange()
                                secondDx += delta2.x
                                secondDy += delta2.y
                                change2.consume()

                                val clamped2 = secondDy.coerceIn(-maxUpwardTravelPx, 0f)
                                scope.launch {
                                    latchOffsetPx.snapTo(clamped2)
                                }
                                if (!secondLatchFired && secondDy <= -triggerThresholdPx) {
                                    secondLatchFired = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onQuickVerifyOrDecrypt()
                                }
                            }
                            scope.launch {
                                latchOffsetPx.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessHigh,
                                    ),
                                )
                            }
                            if (!secondLatchFired && abs(secondDx) <= tapSlopPx && abs(secondDy) <= tapSlopPx) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenPgpTools()
                            }
                        }
                    }
                }
            }
            .testTag("pgp_rocker_pod"),
    ) {
        if (hasPgpContent && internalLedPulse > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val ledPrimary = if (hasArmoredCiphertextOrKey) {
                    MetaToolsTokens.PgpInternalLedEmerald
                } else {
                    MetaToolsTokens.PgpInternalLedAmber
                }
                drawRoundRect(
                    brush = Brush.radialGradient(
                        0.0f to Color.White.copy(alpha = 0.72f * internalLedPulse),
                        0.38f to ledPrimary.copy(alpha = 0.68f * internalLedPulse),
                        1.0f to Color.Transparent,
                        center = Offset(size.width * 0.45f, size.height * 0.48f),
                        radius = size.width * 0.62f,
                    ),
                    cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
                )
            }
        }
        Image(
            painter = spritePainter,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = latchOffsetPx.value * 0.22f
                },
        )
    }
}

private fun DrawScope.drawProceduralSwitchbladeFallback(
    cornerRadiusPx: Float,
    hasPgpContent: Boolean,
    hasArmoredCiphertextOrKey: Boolean,
    internalLedPulse: Float,
    latchUpwardShiftPx: Float,
    maxTravelPx: Float,
) {
    val corner = CornerRadius(cornerRadiusPx, cornerRadiusPx)
    val seamPx = 2.5.dp.toPx()

    drawRoundRect(color = MetaToolsTokens.PolycarbonateBackplate, cornerRadius = corner)

    if (hasPgpContent && internalLedPulse > 0f) {
        val ledPrimary = if (hasArmoredCiphertextOrKey) {
            MetaToolsTokens.PgpInternalLedEmerald
        } else {
            MetaToolsTokens.PgpInternalLedAmber
        }
        drawRoundRect(
            brush = Brush.radialGradient(
                0.0f to Color.White.copy(alpha = 0.85f * internalLedPulse),
                0.30f to ledPrimary.copy(alpha = 0.88f * internalLedPulse),
                0.70f to MetaToolsTokens.PgpInternalLedAmber.copy(alpha = 0.58f * internalLedPulse),
                1.0f to Color.Transparent,
                center = Offset(size.width * 0.5f, size.height * 0.34f),
                radius = size.maxDimension * 0.64f,
            ),
            cornerRadius = corner,
        )
    }

    val rubyTop = if (hasPgpContent) Color(0xE4FF5E1E) else Color(0xE0D82414)
    val rubyMid = if (hasPgpContent) MetaToolsTokens.PolycarbonateRuby else Color(0xE0A8160B)
    val rubyDeep = if (hasPgpContent) Color(0xDC911E00) else Color(0xE05A0804)
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

    drawRoundRect(
        color = MetaToolsTokens.PolycarbonateSeam,
        topLeft = Offset(seamPx / 2f, seamPx / 2f),
        size = Size(size.width - seamPx, size.height - seamPx),
        cornerRadius = CornerRadius(cornerRadiusPx - seamPx / 2f, cornerRadiusPx - seamPx / 2f),
        style = Stroke(width = seamPx),
    )

    val channelW = 32.dp.toPx()
    val channelH = 68.dp.toPx()
    val channelLeft = (size.width - channelW) / 2f
    val channelTop = (size.height - channelH) / 2f
    val channelRadius = 5.dp.toPx()
    val bezelPad = 2.8.dp.toPx()

    drawRoundRect(
        brush = Brush.linearGradient(
            0.0f to Color(0xFFD2DAE2),
            0.5f to Color(0xFF707780),
            1.0f to Color(0xFF282C33),
            start = Offset(channelLeft, channelTop),
            end = Offset(channelLeft + channelW, channelTop + channelH),
        ),
        topLeft = Offset(channelLeft - bezelPad, channelTop - bezelPad),
        size = Size(channelW + bezelPad * 2f, channelH + bezelPad * 2f),
        cornerRadius = CornerRadius(channelRadius + 2.dp.toPx(), channelRadius + 2.dp.toPx()),
    )
    drawRoundRect(
        color = Color(0xFF0A0C0F),
        topLeft = Offset(channelLeft, channelTop),
        size = Size(channelW, channelH),
        cornerRadius = CornerRadius(channelRadius, channelRadius),
    )

    val latchW = 27.dp.toPx()
    val latchH = 25.dp.toPx()
    val latchLeft = (size.width - latchW) / 2f
    val restingLatchTop = channelTop + channelH - latchH - 2.5.dp.toPx()
    val currentLatchTop = (restingLatchTop + latchUpwardShiftPx)
        .coerceIn(channelTop + 7.dp.toPx(), restingLatchTop)

    val springTop = channelTop + 4.5.dp.toPx()
    val springBottom = (currentLatchTop - 3.dp.toPx()).coerceAtLeast(springTop + 4.5.dp.toPx())
    val springHeight = springBottom - springTop
    val coilCount = 6
    val coilLeft = channelLeft + 6.5.dp.toPx()
    val coilRight = channelLeft + channelW - 6.5.dp.toPx()
    val stepY = springHeight / coilCount

    for (i in 0 until coilCount) {
        val y0 = springTop + i * stepY
        val y1 = y0 + stepY * 0.54f
        drawLine(
            color = Color(0xFF2C3139),
            start = Offset(coilRight, y0 + stepY * 0.18f),
            end = Offset(coilLeft, y1 + stepY * 0.22f),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color(0xFFDCE4EC),
            start = Offset(coilLeft, y0),
            end = Offset(coilRight, y1),
            strokeWidth = 2.0.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }

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
        cornerRadius = CornerRadius(3.8.dp.toPx(), 3.8.dp.toPx()),
    )
    drawRoundRect(
        color = if (compressionRatio > 0.7f) {
            MetaToolsTokens.PgpInternalLedEmerald.copy(alpha = 0.78f)
        } else {
            MetaToolsTokens.AluminumLatchLight
        },
        topLeft = Offset(latchLeft + 0.7.dp.toPx(), currentLatchTop + 0.7.dp.toPx()),
        size = Size(latchW - 1.4.dp.toPx(), latchH - 1.4.dp.toPx()),
        cornerRadius = CornerRadius(3.2.dp.toPx(), 3.2.dp.toPx()),
        style = Stroke(width = 1.2.dp.toPx()),
    )
}
