package dev.onionvault.ui.pods

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.onionvault.ui.isBlankOrStartUrl
import kotlin.math.abs

/**
 * Center Floating Hardware Island — Bead-Blasted Pewter & Recessed CRT Address Capsule (`crt_address_pod`).
 *
 * Resting State:
 * - `50.dp` height (`44dp–52dp`), flexible resting width (`160dp–210dp`), `18.dp` squircle collar.
 * - Outer collar: `#7D838C` pewter + procedural `3%` monochrome grain shader, `1.5.dp` `#B8C0C8`
 *   top-left highlight bevel, `1.5.dp` `#2B2E33` bottom-right shadow bevel.
 * - Inner CRT well: deeply recessed `#050608` black glass with heavy inset shadow
 *   (`inset 2px 4px 7px #000000`) and curved top glass reflection arc (`rgba(255,255,255,0.08)`).
 * - Typography: Monospace `13.sp`, centered. `.onion` v3 host in `#39FF14` (`#FFB000` while loading)
 *   with `6px` phosphor halation and tail-preserving `.onion` suffix; HTTPS clearnet host in
 *   `#5CFF8A` / `#7DF9FF` (path `#4A5560`); StartPage shows muted phosphor `"search / .onion"`.
 * - Gestures:
 *   - **Tap**: Expands horizontally to `94%` viewport width docked `6.dp` above IME keyboard!
 *   - **Horizontal swipe left/right**: Calls [onSwitchAdjacentTab] (`+1` / `-1`) with haptic tick!
 *   - **Pull upward** (`dy < -24.dp`): Calls [onOpenTabSwitcher] with haptic tick!
 *
 * Editing State:
 * - `TextFieldValue` with **auto-select-all on focus**, `KeyboardType.Uri`, `ImeAction.Go`,
 *   `autoCorrectEnabled = false`, compact `[Paste]` & `[Clear]` bezel keys, and auto-collapse
 *   when IME hides or Back is pressed!
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecessedCrtAddressPod(
    url: String,
    isLoading: Boolean,
    isEditing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onNavigate: (String) -> Unit,
    onSwitchAdjacentTab: (delta: Int) -> Unit,
    onOpenTabSwitcher: () -> Unit,
    modifier: Modifier = Modifier,
    isImeVisibleOverride: Boolean? = null,
) {
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val grainBrush = rememberPewterGrainBrush()

    val rawEditableUrl = remember(url) {
        if (isBlankOrStartUrl(url)) "" else url.trim()
    }

    var textFieldValue by remember(url) {
        mutableStateOf(
            TextFieldValue(
                text = rawEditableUrl,
                selection = TextRange(0, rawEditableUrl.length),
            ),
        )
    }

    // Auto-select-all and request focus immediately upon entering edit mode.
    LaunchedEffect(isEditing) {
        if (isEditing) {
            val initial = if (isBlankOrStartUrl(url)) "" else url.trim()
            textFieldValue = TextFieldValue(
                text = initial,
                selection = TextRange(0, initial.length),
            )
            runCatching { focusRequester.requestFocus() }
        }
    }

    // Observe real IME visibility so dismissing the soft keyboard auto-collapses the CRT pod.
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeCurrentlyVisible = isImeVisibleOverride ?: (imeBottomPx > 0)
    var imeWasVisibleInEditSession by remember(isEditing) { mutableStateOf(false) }

    LaunchedEffect(isEditing, imeCurrentlyVisible) {
        if (isEditing) {
            if (imeCurrentlyVisible) {
                imeWasVisibleInEditSession = true
            } else if (imeWasVisibleInEditSession) {
                focusManager.clearFocus()
                onEditingChange(false)
            }
        }
    }

    BackHandler(enabled = isEditing) {
        focusManager.clearFocus()
        onEditingChange(false)
    }

    val outerCornerRadius = 18.dp
    val outerShape = RoundedCornerShape(outerCornerRadius)

    if (isEditing) {
        Box(
            modifier = modifier
                .fillMaxWidth(0.94f)
                .height(50.dp)
                .metaToolsDropShadow(cornerRadius = outerCornerRadius)
                .clip(outerShape)
                .testTag("crt_address_pod"),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCrtPewterHousingAndWell(
                    grainBrush = grainBrush,
                    outerRadiusPx = outerCornerRadius.toPx(),
                    collarThicknessPx = 4.5.dp.toPx(),
                    isFocused = true,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .padding(start = 6.dp)
                        .testTag("crt_address_input"),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MetaToolsTokens.PhosphorOnionGreen,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(MetaToolsTokens.PhosphorOnionGreen),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val submitted = textFieldValue.text.trim()
                            focusManager.clearFocus()
                            onEditingChange(false)
                            onNavigate(submitted.ifEmpty { "about:blank" })
                        },
                    ),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (textFieldValue.text.isEmpty()) {
                                Text(
                                    text = "search / .onion",
                                    color = MetaToolsTokens.PhosphorMutedHint,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )

                // Strictly 2 tactile bezel keys: [Paste] & [Clear]
                CrtBezelKeyButton(
                    label = "Paste",
                    onClick = {
                        val clip = clipboard.getText()?.text?.trim().orEmpty()
                        if (clip.isNotEmpty()) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            textFieldValue = TextFieldValue(
                                text = clip,
                                selection = TextRange(clip.length),
                            )
                        }
                    },
                    testTag = "crt_paste_button",
                )
                CrtBezelKeyButton(
                    label = "Clear",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        textFieldValue = TextFieldValue(text = "", selection = TextRange.Zero)
                    },
                    testTag = "crt_clear_button",
                )
            }
        }
    } else {
        val readout = remember(url, isLoading) {
            formatCrtDisplayAddress(url = url, isLoading = isLoading)
        }

        Box(
            modifier = modifier
                .widthIn(min = 162.dp, max = 204.dp)
                .height(50.dp)
                .metaToolsDropShadow(cornerRadius = outerCornerRadius)
                .clip(outerShape)
                .semantics {
                    role = Role.Button
                    contentDescription = "Address bar: ${readout.text.text}"
                    onClick {
                        onEditingChange(true)
                        true
                    }
                }
                .pointerInput(onEditingChange, onSwitchAdjacentTab, onOpenTabSwitcher) {
                    val pullUpThresholdPx = 24.dp.toPx()
                    val horizontalSwipeThresholdPx = 28.dp.toPx()
                    val tapSlopPx = viewConfiguration.touchSlop * 1.5f

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var totalDx = 0f
                        var totalDy = 0f
                        var actionFired = false
                        var cancelled = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break

                            if (change.isConsumed && !actionFired && change.pressed) {
                                // Continue tracking unless cancelled externally
                            }
                            if (!change.pressed) {
                                change.consume()
                                break
                            }

                            val delta = change.positionChange()
                            totalDx += delta.x
                            totalDy += delta.y
                            change.consume()

                            if (!actionFired) {
                                if (totalDy <= -pullUpThresholdPx && abs(totalDy) >= abs(totalDx)) {
                                    actionFired = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onOpenTabSwitcher()
                                } else if (abs(totalDx) >= horizontalSwipeThresholdPx && abs(totalDx) > abs(totalDy)) {
                                    actionFired = true
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val tabDelta = if (totalDx < 0f) +1 else -1
                                    onSwitchAdjacentTab(tabDelta)
                                }
                            }
                        }

                        if (!actionFired && !cancelled &&
                            abs(totalDx) <= tapSlopPx && abs(totalDy) <= tapSlopPx
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onEditingChange(true)
                        }
                    }
                }
                .testTag("crt_address_pod"),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawCrtPewterHousingAndWell(
                    grainBrush = grainBrush,
                    outerRadiusPx = outerCornerRadius.toPx(),
                    collarThicknessPx = 6.dp.toPx(),
                    isFocused = false,
                )
            }

            Text(
                text = readout.text,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (readout.isOnion) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 13.dp)
                    .testTag("crt_address_readout"),
            )
        }
    }
}

@Composable
private fun CrtBezelKeyButton(
    label: String,
    onClick: () -> Unit,
    testTag: String,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 3.dp)
            .clip(shape)
            .background(MetaToolsTokens.AnodizedSurfaceTop)
            .border(1.dp, MetaToolsTokens.PewterHighlightBevel.copy(alpha = 0.55f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MetaToolsTokens.StencilInk,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

/**
 * Draws the bead-blasted cast-pewter outer collar (`#7D838C` + `3%` grain + `1.5.dp` `#B8C0C8` /
 * `#2B2E33` bevels) and the deeply recessed `#050608` curved CRT black glass well with heavy
 * inset shadow (`inset 2px 4px 7px #000000`) and top glass reflection arc (`rgba(255,255,255,0.08)`).
 */
private fun DrawScope.drawCrtPewterHousingAndWell(
    grainBrush: androidx.compose.ui.graphics.ShaderBrush,
    outerRadiusPx: Float,
    collarThicknessPx: Float,
    isFocused: Boolean,
) {
    val outerCorner = CornerRadius(outerRadiusPx, outerRadiusPx)

    // 1. Cast-Pewter Outer Collar Base + Cylindrical Metallic Sheen + 3% Monochrome Grain
    drawRoundRect(
        brush = Brush.linearGradient(
            0.0f to Color(0xFF969DA6),
            0.35f to MetaToolsTokens.PewterBase,
            0.78f to MetaToolsTokens.PewterMidDark,
            1.0f to Color(0xFF42474E),
            start = Offset.Zero,
            end = Offset(size.width * 0.65f, size.height),
        ),
        cornerRadius = outerCorner,
    )
    drawRoundRect(
        brush = grainBrush,
        cornerRadius = outerCorner,
    )

    // 1.5.dp Top-Left Highlight (#B8C0C8) & Bottom-Right Shadow (#2B2E33) Bevel
    drawMetallicSquircleBevel(
        cornerRadiusPx = outerRadiusPx,
        strokeWidthPx = 1.5.dp.toPx(),
        highlightColor = MetaToolsTokens.PewterHighlightBevel,
        shadowColor = MetaToolsTokens.PewterShadowBevel,
    )

    // 2. Deeply Recessed CRT Black Glass Well (#050608)
    val wellLeft = collarThicknessPx
    val wellTop = collarThicknessPx
    val wellWidth = (size.width - collarThicknessPx * 2f).coerceAtLeast(1f)
    val wellHeight = (size.height - collarThicknessPx * 2f).coerceAtLeast(1f)
    val wellRadiusPx = (outerRadiusPx - collarThicknessPx * 0.85f).coerceAtLeast(6f)
    val wellCorner = CornerRadius(wellRadiusPx, wellRadiusPx)

    // Inner bevel lip (dark top-left sunken lip, bright bottom-right catchlight)
    drawRoundRect(
        brush = Brush.linearGradient(
            0.0f to Color(0xFF14161A),
            0.55f to Color(0xFF2E333A),
            1.0f to Color(0xFFA8B0B8),
            start = Offset(wellLeft, wellTop),
            end = Offset(wellLeft + wellWidth, wellTop + wellHeight),
        ),
        topLeft = Offset(wellLeft - 1.dp.toPx(), wellTop - 1.dp.toPx()),
        size = Size(wellWidth + 2.dp.toPx(), wellHeight + 2.dp.toPx()),
        cornerRadius = CornerRadius(wellRadiusPx + 1.dp.toPx(), wellRadiusPx + 1.dp.toPx()),
    )

    drawRoundRect(
        color = MetaToolsTokens.CrtBlackGlass,
        topLeft = Offset(wellLeft, wellTop),
        size = Size(wellWidth, wellHeight),
        cornerRadius = wellCorner,
    )

    val wellPath = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = wellLeft,
                top = wellTop,
                right = wellLeft + wellWidth,
                bottom = wellTop + wellHeight,
                cornerRadius = wellCorner,
            ),
        )
    }

    clipPath(wellPath) {
        // Subtle green CRT tube center bloom when resting/focused
        drawRect(
            brush = Brush.radialGradient(
                0.0f to Color(0x16144D22),
                0.75f to Color(0x080A2611),
                1.0f to Color.Transparent,
                center = Offset(wellLeft + wellWidth * 0.5f, wellTop + wellHeight * 0.54f),
                radius = wellWidth * 0.55f,
            ),
            topLeft = Offset(wellLeft, wellTop),
            size = Size(wellWidth, wellHeight),
        )

        // Heavy inset shadow (inset 2px 4px 7px #000000) along top and left CRT bezel overhang
        val insetDepthY = 7.dp.toPx()
        val insetDepthX = 5.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color.Black.copy(alpha = 0.95f),
                0.55f to Color.Black.copy(alpha = 0.48f),
                1.0f to Color.Transparent,
                startY = wellTop,
                endY = wellTop + insetDepthY,
            ),
            topLeft = Offset(wellLeft, wellTop),
            size = Size(wellWidth, insetDepthY),
        )
        drawRect(
            brush = Brush.horizontalGradient(
                0.0f to Color.Black.copy(alpha = 0.88f),
                0.55f to Color.Black.copy(alpha = 0.35f),
                1.0f to Color.Transparent,
                startX = wellLeft,
                endX = wellLeft + insetDepthX,
            ),
            topLeft = Offset(wellLeft, wellTop),
            size = Size(insetDepthX, wellHeight),
        )

        // Subtle curved top CRT glass reflection arc (rgba(255,255,255,0.08))
        val arcPath = Path().apply {
            val leftX = wellLeft + wellWidth * 0.08f
            val rightX = wellLeft + wellWidth * 0.92f
            val topY = wellTop + 2.5.dp.toPx()
            val bellyY = wellTop + wellHeight * 0.34f
            moveTo(leftX, topY + 3.dp.toPx())
            quadraticTo(
                wellLeft + wellWidth * 0.5f,
                topY - 1.5.dp.toPx(),
                rightX,
                topY + 3.dp.toPx(),
            )
            quadraticTo(
                wellLeft + wellWidth * 0.5f,
                bellyY,
                leftX,
                topY + 3.dp.toPx(),
            )
            close()
        }
        drawPath(
            path = arcPath,
            color = MetaToolsTokens.CrtGlassReflection,
        )

        if (isFocused) {
            drawRoundRect(
                color = MetaToolsTokens.PhosphorOnionGreen.copy(alpha = 0.32f),
                topLeft = Offset(wellLeft + 0.5.dp.toPx(), wellTop + 0.5.dp.toPx()),
                size = Size(wellWidth - 1.dp.toPx(), wellHeight - 1.dp.toPx()),
                cornerRadius = wellCorner,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
