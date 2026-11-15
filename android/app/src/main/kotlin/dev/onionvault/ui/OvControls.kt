package dev.onionvault.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 44dp circular navigation button matching `Securedrop+Browser+Chrome+Fixed` (`App.tsx:26-41`),
 * adapting cleanly between `SecureDrop` and `NeonGraffiti` (Dark & Light).
 */
@Composable
fun OvNavCircleButton(
    glyph: TrapGlyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = TrapTheme.colors.teal,
    size: Dp = 44.dp,
    badge: Int = 0,
    rotationDegrees: Float = 0f,
    testTag: String? = null,
) {
    val colors = TrapTheme.colors
    val shape = CircleShape
    val borderColor = if (enabled) colors.border else colors.border.copy(alpha = 0.45f)

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(colors.control)
                .border(1.dp, borderColor, shape)
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics { this.contentDescription = contentDescription }
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            TrapIcon(
                glyph = glyph,
                tint = accent,
                size = if (colors.useNeonSprites && glyph.neonRes != null) 24.dp else 20.dp,
                enabled = enabled,
                modifier = if (rotationDegrees != 0f) {
                    Modifier.graphicsLayer { rotationZ = rotationDegrees }
                } else {
                    Modifier
                },
            )
        }

        if (badge > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .clip(CircleShape)
                    .background(colors.teal)
                    .border(1.5.dp, colors.surface, CircleShape)
                    .padding(horizontal = 4.5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge.toString(),
                    color = colors.onTealFill,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 11.sp,
                )
            }
        }
    }
}

/**
 * Unified 44dp `[Shield · .onion Input · Clear ✕ | Go]` capsule (`0dp` internal seam,
 * `#E8A33D` `Go` fill, `#1A1510` bold ink, honest `.onion` URL hint, `about:blank` preserved).
 */
@Composable
fun OvUnifiedAddressCapsule(
    address: String,
    onAddressChange: (String) -> Unit,
    onGo: () -> Unit,
    onClear: () -> Unit,
    onSecurityClick: () -> Unit,
    blocked: Boolean,
    hiddenServiceOnly: Boolean,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val colors = TrapTheme.colors
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }

    val leftShape = RoundedCornerShape(
        topStart = 22.dp,
        bottomStart = 22.dp,
        topEnd = 0.dp,
        bottomEnd = 0.dp,
    )
    val rightShape = RoundedCornerShape(
        topStart = 0.dp,
        bottomStart = 0.dp,
        topEnd = 22.dp,
        bottomEnd = 22.dp,
    )
    val leftBorder = when {
        blocked -> colors.danger
        isFocused -> colors.teal
        else -> colors.border
    }

    Row(
        modifier = modifier
            .height(44.dp)
            .testTag("address_capsule"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(leftShape)
                .background(colors.control)
                .border(1.dp, leftBorder, leftShape)
                .padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClick = onSecurityClick,
                    )
                    .semantics { contentDescription = "Tor security status" },
                contentAlignment = Alignment.Center,
            ) {
                TrapIcon(
                    glyph = TrapGlyph.Shield,
                    tint = if (hiddenServiceOnly) colors.teal else colors.amber,
                    size = 17.dp,
                )
            }

            Spacer(Modifier.width(6.dp))

            BasicTextField(
                value = address,
                onValueChange = onAddressChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { isFocused = it.isFocused }
                    .testTag("address_input"),
                singleLine = true,
                textStyle = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(colors.teal),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        focusManager.clearFocus()
                        onGo()
                    },
                ),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (address.isEmpty()) {
                            Text(
                                text = hint,
                                color = colors.textMuted,
                                fontSize = 13.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            if (address.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(
                            role = Role.Button,
                            onClick = onClear,
                        )
                        .semantics { contentDescription = "Clear address" }
                        .testTag("address_clear"),
                    contentAlignment = Alignment.Center,
                ) {
                    TrapIcon(
                        glyph = TrapGlyph.Close,
                        tint = colors.textMuted,
                        size = 15.dp,
                    )
                }
            }
        }

        // Seamless 0dp-joined Go button (#E8A33D fill, #1A1510 dark bold text)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(rightShape)
                .background(colors.goFill)
                .border(1.dp, colors.goFill, rightShape)
                .clickable(
                    role = Role.Button,
                    onClick = {
                        focusManager.clearFocus()
                        onGo()
                    },
                )
                .padding(horizontal = 18.dp)
                .semantics { contentDescription = "Go" }
                .testTag("address_go"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Go",
                color = colors.onGoFill,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/**
 * Menu row with strictly <= 2:1 label density (1 primary label + at most 1 trailing token).
 */
@Composable
fun OvMenuRow(
    glyph: TrapGlyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: String? = null,
    tint: Color = TrapTheme.colors.teal,
    testTag: String? = null,
) {
    val colors = TrapTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrapIcon(
            glyph = glyph,
            tint = tint,
            size = 22.dp,
            enabled = enabled,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            color = if (enabled) colors.textPrimary else colors.textMuted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!trailing.isNullOrEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = trailing,
                color = colors.teal,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun OvIconButton(
    glyph: TrapGlyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = TrapTheme.colors.teal,
    containerColor: Color = TrapTheme.colors.control,
    borderColor: Color = TrapTheme.colors.border,
    enabled: Boolean = true,
    size: Dp = 38.dp,
    forceVector: Boolean = false,
) {
    val shape = CircleShape
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(containerColor)
            .border(1.dp, borderColor, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        TrapIcon(
            glyph = glyph,
            tint = tint,
            size = size * 0.50f,
            enabled = enabled,
            forceVector = forceVector,
        )
    }
}

@Composable
fun OvPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: TrapGlyph? = null,
    enabled: Boolean = true,
) {
    val colors = TrapTheme.colors
    OvButtonBase(
        text = text,
        onClick = onClick,
        modifier = modifier,
        glyph = glyph,
        enabled = enabled,
        container = colors.goFill,
        content = colors.onGoFill,
        border = null,
        forceVector = true,
    )
}

@Composable
fun OvSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: TrapGlyph? = null,
    enabled: Boolean = true,
    accent: Color = TrapTheme.colors.teal,
) {
    val colors = TrapTheme.colors
    OvButtonBase(
        text = text,
        onClick = onClick,
        modifier = modifier,
        glyph = glyph,
        enabled = enabled,
        container = colors.control,
        content = accent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.75f)),
        forceVector = true,
    )
}

@Composable
private fun OvButtonBase(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    glyph: TrapGlyph?,
    enabled: Boolean,
    container: Color,
    content: Color,
    border: BorderStroke?,
    forceVector: Boolean,
) {
    val colors = TrapTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (enabled) container else colors.surface)
            .then(if (border != null && enabled) Modifier.border(border, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            TrapIcon(
                glyph = glyph,
                tint = if (enabled) content else colors.textMuted,
                size = 16.dp,
                enabled = enabled,
                forceVector = forceVector,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = if (enabled) content else colors.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

enum class PgpPillKind {
    EncryptField,
    DecryptBlock,
    InsertPubKey,
    ImportKey,
}

/**
 * Anchored PGP overlay pill using exact `App.tsx` tokens:
 * - `Encrypt Field`: `#E8A33D` fill, `#1A1510` ink
 * - `Decrypt Block`: `#2EC4B0` fill, `#062A26` ink
 * - `Insert My PubKey`: `#3ECF6A` fill, `#0B2A16` ink
 * - `Import Key`: `#2EC4B0` / `#00E4C1` fill, `#062A26` ink
 */
@Composable
fun OvPgpPill(
    text: String,
    kind: PgpPillKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TrapTheme.colors
    val (fill, ink, glyph) = when (kind) {
        PgpPillKind.EncryptField -> Triple(colors.pgpEncryptFill, colors.pgpEncryptInk, TrapGlyph.Encrypt)
        PgpPillKind.DecryptBlock -> Triple(colors.pgpDecryptFill, colors.pgpDecryptInk, TrapGlyph.Decrypt)
        PgpPillKind.InsertPubKey -> Triple(colors.pgpPubKeyFill, colors.pgpPubKeyInk, TrapGlyph.Key)
        PgpPillKind.ImportKey -> Triple(colors.pgpDecryptFill, colors.pgpDecryptInk, TrapGlyph.Import)
    }
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .shadow(4.dp, shape)
            .clip(shape)
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrapIcon(
            glyph = glyph,
            tint = ink,
            size = 14.dp,
            forceVector = true,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            color = ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun OvBadge(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = TrapTheme.colors.textMuted,
    container: Color = TrapTheme.colors.control,
    border: Color? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(container)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = accent,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun OvProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
) {
    val colors = TrapTheme.colors
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(colors.control),
    ) {
        Box(
            modifier = Modifier
                .height(height)
                .fillMaxWidth(fraction)
                .clip(RoundedCornerShape(height / 2))
                .background(colors.teal),
        )
    }
}

/**
 * Clean 2-option or N-option segmented selector backed by caller state.
 */
@Composable
fun <T> OvSegmentedRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TrapTheme.colors
    val outerShape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(outerShape)
            .background(colors.surface2)
            .border(1.dp, colors.border, outerShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val itemShape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .clip(itemShape)
                    .background(if (isSelected) colors.control else Color.Transparent)
                    .then(
                        if (isSelected) {
                            Modifier.border(1.dp, colors.teal, itemShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable(role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) colors.textPrimary else colors.textMuted,
                    fontSize = 12.5.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Shared bottom-anchored modal sheet container with scrim, system-bar insets,
 * non-propagating inner tap handling, and header close control.
 */
@Composable
fun OvBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TrapTheme.colors
    val sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.56f))
            .clickable(onClick = onDismiss)
            .statusBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(sheetShape)
                .background(colors.surface)
                .border(1.dp, colors.border, sheetShape)
                .clickable(enabled = false, onClick = {})
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.border),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (headerAction != null) {
                    headerAction()
                    Spacer(Modifier.width(8.dp))
                }
                OvIconButton(
                    glyph = TrapGlyph.Close,
                    contentDescription = "Close $title",
                    onClick = onDismiss,
                    modifier = Modifier.testTag("sheet_close_button"),
                    tint = colors.textMuted,
                    size = 34.dp,
                )
            }

            content()
        }
    }
}
