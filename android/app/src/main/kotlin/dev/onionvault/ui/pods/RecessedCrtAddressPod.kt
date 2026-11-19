package dev.onionvault.ui.pods

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import dev.onionvault.R
import dev.onionvault.ui.TrustedOnionDirectory
import dev.onionvault.ui.isBlankOrStartUrl
import kotlin.math.abs

// Exact normalized inner CRT glass text rect of `pod_crt_pewter_tv.webp` (500 x 434 px)
private const val CRT_GLASS_LEFT_FRACTION = 0.165f
private const val CRT_GLASS_TOP_FRACTION = 0.280f
private const val CRT_GLASS_RIGHT_FRACTION = 0.835f
private const val CRT_GLASS_BOTTOM_FRACTION = 0.730f

/**
 * Chunky `~5:4` (`156.dp x 130.dp`) Brushed-Pewter 1997 CRT TV Address Pod (`crt_address_pod`) —
 * Lower-Center (`metatools-reference.jpg` 1:1).
 *
 * Resting State:
 * - `156.dp x 130.dp` chunky CRT TV monitor (`pod_crt_pewter_tv.webp`, NOT a wide bottom pill!),
 *   floating over the lower-center typewriter zine sheet.
 * - Glowing phosphor green centered readout (`dread7x.onion`-style host when browsing,
 *   `search / .onion` on StartPage).
 * - Gestures:
 *   - **Tap**: Opens the CRT address editor + `TrustedOnionDirectory` overlay (`isEditing = true`).
 *   - **Horizontal swipe left/right**: Calls [onSwitchAdjacentTab] (`+1` / `-1`).
 *   - **Pull upward** (`dy <= -24.dp`): Calls [onOpenTabSwitcher] (`tabs_sheet`).
 *
 * Editing State:
 * - Expands to `95%` viewport width (`68.dp` height, `17.sp` input text) docked above IME,
 *   resolving search queries to DuckDuckGo `.onion` and preserving direct hosts/URLs.
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
    val tvSpritePainter = painterResource(R.drawable.pod_crt_pewter_tv)

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

    if (isEditing) {
        val editCornerRadius = 22.dp
        val editShape = RoundedCornerShape(editCornerRadius)
        Box(
            modifier = modifier
                .fillMaxWidth(0.95f)
                .height(68.dp)
                .metaToolsDropShadow(cornerRadius = editCornerRadius)
                .clip(editShape)
                .testTag("crt_address_pod"),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCrtPewterHousingAndWell(
                    grainBrush = grainBrush,
                    outerRadiusPx = editCornerRadius.toPx(),
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
                            val resolved = TrustedOnionDirectory.resolveSearchOrAddress(submitted)
                                ?: "about:blank"
                            focusManager.clearFocus()
                            onEditingChange(false)
                            onNavigate(resolved)
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

                // Strictly 2 tactile hardware bezel keys: [Paste] & [Clear] (<= 2:1 label density)
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

        val charLen = readout.text.length
        val readoutFontSize = when {
            charLen <= 14 -> 12.8.sp
            charLen <= 16 -> 11.2.sp
            else -> 9.4.sp
        }
        val readoutLetterSpacing = when {
            charLen <= 14 -> (-0.15).sp
            charLen <= 16 -> (-0.30).sp
            else -> (-0.42).sp
        }

        val tvWidth = 156.dp
        val tvHeight = 135.dp
        val tvCornerRadius = 26.dp

        Box(
            modifier = modifier
                .size(width = tvWidth, height = tvHeight)
                .metaToolsDropShadow(cornerRadius = tvCornerRadius, insetHorizontal = 5.dp, insetVertical = 5.dp)
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
            Image(
                painter = tvSpritePainter,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = tvWidth * CRT_GLASS_LEFT_FRACTION,
                        end = tvWidth * (1f - CRT_GLASS_RIGHT_FRACTION),
                        top = tvHeight * CRT_GLASS_TOP_FRACTION,
                        bottom = tvHeight * (1f - CRT_GLASS_BOTTOM_FRACTION),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = readout.text,
                    fontSize = readoutFontSize,
                    letterSpacing = readoutLetterSpacing,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .testTag("crt_address_readout"),
                )
            }
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

private fun DrawScope.drawCrtPewterHousingAndWell(
    grainBrush: androidx.compose.ui.graphics.ShaderBrush,
    outerRadiusPx: Float,
    collarThicknessPx: Float,
    isFocused: Boolean,
) {
    val outerCorner = CornerRadius(outerRadiusPx, outerRadiusPx)

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
    drawRoundRect(brush = grainBrush, cornerRadius = outerCorner)

    drawMetallicSquircleBevel(
        cornerRadiusPx = outerRadiusPx,
        strokeWidthPx = 2.dp.toPx(),
        highlightColor = MetaToolsTokens.PewterHighlightBevel,
        shadowColor = MetaToolsTokens.PewterShadowBevel,
    )

    val wellLeft = collarThicknessPx
    val wellTop = collarThicknessPx
    val wellWidth = (size.width - collarThicknessPx * 2f).coerceAtLeast(1f)
    val wellHeight = (size.height - collarThicknessPx * 2f).coerceAtLeast(1f)
    val wellRadiusPx = (outerRadiusPx - collarThicknessPx * 0.55f).coerceAtLeast(10f)
    val wellCorner = CornerRadius(wellRadiusPx, wellRadiusPx)

    val lipExpand = 1.8.dp.toPx()
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
        drawRect(
            brush = Brush.radialGradient(
                0.0f to Color(0x321D7030),
                0.65f to Color(0x140E3617),
                1.0f to Color.Transparent,
                center = Offset(wellLeft + wellWidth * 0.5f, wellTop + wellHeight * 0.52f),
                radius = wellWidth * 0.62f,
            ),
            topLeft = Offset(wellLeft, wellTop),
            size = Size(wellWidth, wellHeight),
        )

        val scanStep = 2.dp.toPx()
        var scanY = wellTop + scanStep
        while (scanY < wellTop + wellHeight) {
            drawLine(
                color = Color(0x14000000),
                start = Offset(wellLeft, scanY),
                end = Offset(wellLeft + wellWidth, scanY),
                strokeWidth = 0.8.dp.toPx(),
            )
            scanY += scanStep
        }

        val arcPath = Path().apply {
            val leftX = wellLeft + wellWidth * 0.08f
            val rightX = wellLeft + wellWidth * 0.88f
            val topY = wellTop + 4.dp.toPx()
            val bellyY = wellTop + wellHeight * 0.36f
            moveTo(leftX, topY + 5.dp.toPx())
            quadraticTo(wellLeft + wellWidth * 0.45f, topY - 2.dp.toPx(), rightX, topY + 6.dp.toPx())
            quadraticTo(wellLeft + wellWidth * 0.42f, bellyY, leftX, topY + 5.dp.toPx())
            close()
        }
        drawPath(path = arcPath, color = MetaToolsTokens.CrtGlassReflection)

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
