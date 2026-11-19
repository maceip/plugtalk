package dev.onionvault.ui.pods

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `62.dp x 40.dp` Rectangular Chamfered Cast-Pewter `◀` Tactile Switch (`kpt_back_pod`).
 *
 * Positioned cleanly on the left above the bottom-left purple Tor orb (left of the CRT TV's
 * upper-left corner).
 *
 * **STRICTLY NON-ROUND** (`62dp x 40dp` `1.55:1` horizontal beveled cast-pewter keycap —
 * ZERO circular/ball silhouette so `TorKnurledOrbPod` is the sole round orb in the UI!).
 *
 * - Renders `R.drawable.pod_pewter_back_key` (`pod_pewter_back_key.webp`) at full crisp visibility,
 *   adding an active emerald phosphor halation glow when back navigation is available.
 * - **Tap**: invokes [onBack] when [canGoBack] is `true`, or [onReturnToStartPage] when
 *   [canReturnToStartPage] is `true`.
 * - **Long-press (`400ms`)**: invokes [onForward] when [canGoForward] is `true`.
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
    val spritePainter = painterResource(R.drawable.pod_pewter_back_key)
    var isPressed by remember { mutableStateOf(false) }

    val effectiveCanGoBack = canGoBack || canReturnToStartPage

    val depressOffset by animateDpAsState(
        targetValue = if (isPressed) 2.dp else 0.dp,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.ORB_DEPRESS_DURATION_MS,
            easing = LinearEasing,
        ),
        label = "kpt_back_key_depress",
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
            .size(width = 62.dp, height = 40.dp)
            .metaToolsDropShadow(cornerRadius = 6.dp, isCircle = false, insetHorizontal = 3.dp, insetVertical = 3.dp)
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
        val pressScale = if (isPressed) 0.96f else 1.0f
        Image(
            painter = spritePainter,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                    translationX = depressOffset.toPx() * 0.45f
                    translationY = depressOffset.toPx() * 0.65f
                },
        )
        if (effectiveCanGoBack) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to MetaToolsTokens.LedGreen.copy(alpha = 0.28f),
                        1.0f to Color.Transparent,
                        center = center,
                        radius = size.minDimension * 0.44f,
                    ),
                    radius = size.minDimension * 0.44f,
                    center = center,
                )
            }
        }
    }
}
