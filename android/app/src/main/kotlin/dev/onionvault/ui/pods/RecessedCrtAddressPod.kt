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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
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
 * `1.5x` Scaled Sculptural 1997 CRT TV Monitor Address Pod (`crt_address_pod`) — Bottom-Center.
 *
 * Resting State:
 * - `74.dp` height, fills flexible `weight(1f)` width (`~197dp–219dp`), `22.dp` squircle collar.
 * - Thick `7.dp` cast-pewter TV housing (`#7D838C` + `3%` monochrome grain, `2.dp` `#C4CCD4`
 *   top-left specular bevel & `#26292E` bottom-right shadow bevel, sunken inner CRT tube lip).
 * - Deeply recessed `#050608` curved CRT glass well with radial phosphor bloom, subtle scanlines,
 *   heavy inset bezel overhang shadow, and curved top CRT glass reflection arc.
 * - Typography: Bold `16.5.sp` Monospace phosphor green `#39FF14` (`#FFB000` while loading) with
 *   `8px` phosphor halation and tail-preserving `.onion` suffix; StartPage shows `"search / .onion"`.
 * - Gestures:
 *   - **Tap**: Expands horizontally to `95%` viewport width (`68.dp` height, `17.sp` input text)
 *     docked `8.dp` above IME keyboard!
 *   - **Horizontal swipe left/right**: Calls [onSwitchAdjacentTab] (`+1` / `-1`) with haptic tick!
 *   - **Pull upward** (`dy < -24.dp`): Calls [onOpenTabSwitcher] with haptic tick!
 *
 * Editing State:
 * - `TextFieldValue` with **auto-select-all on focus**, `KeyboardType.Uri`, `ImeAction.Go`,
 *   `autoCorrectEnabled = false`, tactile `[Paste]` & `[Clear]` hardware bezel keys, and
 *   auto-collapse when IME hides or Back is pressed!
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

    val outerCornerRadius = 22.dp
    val outerShape = RoundedCornerShape(outerCornerRadius)

    if (isEditing) {
        Box(
            modifier = modifier
                .fillMaxWidth(0.95f)
                .height(68.dp)
                .metaToolsDropShadow(cornerRadius = outerCornerRadius)
                .clip(outerShape)
                .testTag("crt_address_pod"),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCrtPewterHousingAndWell(
                    grainBrush = grainBrush,
                    outerRadiusPx = outerCornerRadius.toPx(),
                    collarThicknessPx = 6.dp.toPx(),
                    isFocused = true,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
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
                                    fontSize = 17.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )

                // Strictly 2 tactile hardware bezel keys: [Paste] & [Clear]
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
        val fullTarget = if (isBlankOrStartUrl(url)) "search / .onion" else url.trim()
        val a11yAddressDesc = if (isLoading && !isBlankOrStartUrl(url)) {
            "Address bar (Stop loading): $fullTarget"
        } else {
            "Address bar: $fullTarget"
        }
        // Tight tracking on 19-20 char truncated .onion strings so 16.5.sp bold monospace
        // fits end-to-end inside the ~219dp CRT well without trailing ellipsis.
        val readoutLetterSpacing = if (readout.text.length >= 18) (-0.65).sp else (-0.2).sp

        Box(
            modifier = modifier
                .widthIn(min = 156.dp)
                .height(74.dp)
                .metaToolsDropShadow(cornerRadius = outerCornerRadius)
                .clip(outerShape)
                .semantics {
                    role = Role.Button
                    contentDescription = a11yAddressDesc
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

                        if (!actionFired &&
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
                    collarThicknessPx = 7.dp.toPx(),
                    isFocused = false,
                )
            }

            Text(
                text = readout.text,
                fontSize = 16.5.sp,
                letterSpacing = readoutLetterSpacing,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
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
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 3.dp)
            .clip(shape)
            .background(MetaToolsTokens.AnodizedSurfaceTop)
            .border(1.2.dp, MetaToolsTokens.PewterHighlightBevel.copy(alpha = 0.62f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 11.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MetaToolsTokens.StencilInk,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

/**
 * Draws the chunky 1997 CRT TV monitor cast-pewter collar (`7.dp` thick, `2.dp` `#C4CCD4` /
 * `#26292E` bevels + `3%` grain + contoured inner CRT lip) and deeply sunken `#050608` curved
 * CRT glass tube with phosphor bloom, scanlines, heavy inset shadow, and curved reflection arc.
 */
private fun DrawScope.drawCrtPewterHousingAndWell(
    grainBrush: androidx.compose.ui.graphics.ShaderBrush,
    outerRadiusPx: Float,
    collarThicknessPx: Float,
    isFocused: Boolean,
) {
    val outerCorner = CornerRadius(outerRadiusPx, outerRadiusPx)

    // 1. Sculptural Cast-Pewter CRT TV Monitor Collar + Pillowed Metallic Contour + 3% Grain
    drawRoundRect(
        brush = Brush.linearGradient(
            0.0f to Color(0xFFA6AEB8),
            0.28f to MetaToolsTokens.PewterBase,
            0.72f to MetaToolsTokens.PewterMidDark,
            1.0f to Color(0xFF393E45),
            start = Offset.Zero,
            end = Offset(size.width * 0.7f, size.height),
        ),
        cornerRadius = outerCorner,
    )
    drawRoundRect(
        brush = grainBrush,
        cornerRadius = outerCorner,
    )

    // 2.dp Top-Left Highlight & Bottom-Right Shadow Outer Bevel
    drawMetallicSquircleBevel(
        cornerRadiusPx = outerRadiusPx,
        strokeWidthPx = 2.dp.toPx(),
        highlightColor = MetaToolsTokens.PewterHighlightBevel,
        shadowColor = MetaToolsTokens.PewterShadowBevel,
    )

    // 2. Deeply Sunken CRT Black Glass Tube Well (#050608)
    val wellLeft = collarThicknessPx
    val wellTop = collarThicknessPx
    val wellWidth = (size.width - collarThicknessPx * 2f).coerceAtLeast(1f)
    val wellHeight = (size.height - collarThicknessPx * 2f).coerceAtLeast(1f)
    val wellRadiusPx = (outerRadiusPx - collarThicknessPx * 0.78f).coerceAtLeast(8f)
    val wellCorner = CornerRadius(wellRadiusPx, wellRadiusPx)

    // Stepped inner CRT bezel lip (deep dark sunken overhang top-left, bright steel catchlight bottom-right)
    val lipExpand = 1.6.dp.toPx()
    drawRoundRect(
        brush = Brush.linearGradient(
            0.0f to Color(0xFF0E1014),
            0.52f to Color(0xFF2C3138),
            1.0f to Color(0xFFB4BCC6),
            start = Offset(wellLeft, wellTop),
            end = Offset(wellLeft + wellWidth, wellTop + wellHeight),
        ),
        topLeft = Offset(wellLeft - lipExpand, wellTop - lipExpand),
        size = Size(wellWidth + lipExpand * 2f, wellHeight + lipExpand * 2f),
        cornerRadius = CornerRadius(wellRadiusPx + lipExpand, wellRadiusPx + lipExpand),
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
        // Curved green CRT tube center bloom matching reference TV monitor
        drawRect(
            brush = Brush.radialGradient(
                0.0f to Color(0x281B662C),
                0.65f to Color(0x100E3617),
                1.0f to Color.Transparent,
                center = Offset(wellLeft + wellWidth * 0.5f, wellTop + wellHeight * 0.52f),
                radius = wellWidth * 0.58f,
            ),
            topLeft = Offset(wellLeft, wellTop),
            size = Size(wellWidth, wellHeight),
        )

        // Subtle horizontal CRT phosphor scanlines (2.dp pitch)
        val scanStep = 2.dp.toPx()
        var scanY = wellTop + scanStep
        while (scanY < wellTop + wellHeight) {
            drawLine(
                color = Color(0x12000000),
                start = Offset(wellLeft, scanY),
                end = Offset(wellLeft + wellWidth, scanY),
                strokeWidth = 0.8.dp.toPx(),
            )
            scanY += scanStep
        }

        // Heavy inset overhang shadow along top, left, and bottom CRT tube edges
        val insetDepthY = 9.5.dp.toPx()
        val insetDepthX = 7.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color.Black.copy(alpha = 0.96f),
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
                0.0f to Color.Black.copy(alpha = 0.90f),
                0.55f to Color.Black.copy(alpha = 0.36f),
                1.0f to Color.Transparent,
                startX = wellLeft,
                endX = wellLeft + insetDepthX,
            ),
            topLeft = Offset(wellLeft, wellTop),
            size = Size(insetDepthX, wellHeight),
        )
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = 0.55f),
                startY = wellTop + wellHeight - 5.dp.toPx(),
                endY = wellTop + wellHeight,
            ),
            topLeft = Offset(wellLeft, wellTop + wellHeight - 5.dp.toPx()),
            size = Size(wellWidth, 5.dp.toPx()),
        )

        // Curved top CRT glass tube reflection arc
        val arcPath = Path().apply {
            val leftX = wellLeft + wellWidth * 0.07f
            val rightX = wellLeft + wellWidth * 0.93f
            val topY = wellTop + 3.dp.toPx()
            val bellyY = wellTop + wellHeight * 0.34f
            moveTo(leftX, topY + 4.dp.toPx())
            quadraticTo(
                wellLeft + wellWidth * 0.5f,
                topY - 2.dp.toPx(),
                rightX,
                topY + 4.dp.toPx(),
            )
            quadraticTo(
                wellLeft + wellWidth * 0.5f,
                bellyY,
                leftX,
                topY + 4.dp.toPx(),
            )
            close()
        }
        drawPath(
            path = arcPath,
            color = MetaToolsTokens.CrtGlassReflection,
        )

        if (isFocused) {
            drawRoundRect(
                color = MetaToolsTokens.PhosphorOnionGreen.copy(alpha = 0.38f),
                topLeft = Offset(wellLeft + 0.6.dp.toPx(), wellTop + 0.6.dp.toPx()),
                size = Size(wellWidth - 1.2.dp.toPx(), wellHeight - 1.2.dp.toPx()),
                cornerRadius = wellCorner,
                style = Stroke(width = 1.2.dp.toPx()),
            )
        }
    }
}
