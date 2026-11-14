package dev.onionvault.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.onionvault.R

/**
 * The controls of Trap.
 *
 * The iconography is the neon system of the design board: every glyph is cut
 * out of the board itself, with its own colour, and it is drawn on a plate
 * whose border carries the teal of the brand.  The controls are never the
 * stock icons of the material library: the board is the style of the
 * application.
 *
 * ## The catalogue of the controls
 *
 * | control | what it is | used by |
 * |---|---|---|
 * | `OvMenuRow` | a line of the menu: a glyph, a name and, when the action has a state, that state at the right | the menu of the browser |
 * | `OvGlassButton` | a round plate of glass that floats above the page | the row of the buttons, the button that clears the address |
 * | `OvAddressPlate` | the rounded plate of the field of the address | the field of the address |
 * | `OvIconButton` | a small round plate with a thin outline | the export of a public key |
 * | `OvPrimaryButton` | the amber of the board, full of paint | the key manager |
 * | `OvSecondaryButton` | an outlined plate with the teal of the brand | "Insert into the page", the close of a licence |
 * | `OvActionChip` | the mark of the spray can that says what the action does | the overlay of the PGP fields |
 * | `OvBadge` | a small number or a small word on a plate | the tabs, the state of a key, the state of a signature |
 * | `OvProgressBar` | the paint that fills up while the client bootstraps | the line of the transport |
 * | `OvBrandMark` | the wordmark of the board and the two onions | **nothing** — it is dead |
 * | `OvTabBadge` | the number of the tabs of the old top bar | **nothing** — it is dead |
 *
 * The two dead controls are the remains of the top bar of the first
 * generation of the chrome, the one that the board had and that Safari on
 * iOS does not have.  They are kept here because their artwork is part of
 * the identity of the board, and they are the natural place to start when
 * the brand has to be shown again somewhere.
 */

/**
 * A row of the menu of the browser.
 *
 * @param icon the glyph of the row.
 * @param label the name of the action.
 * @param onClick the user tapped the row.
 * @param modifier the modifier of the row.
 * @param enabled `false` dims the row and stops the taps.
 * @param trailing the text that is shown at the end of the row.
 */
@Composable
fun OvMenuRow(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = icon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(26.dp)
                .alpha(if (enabled) 1f else 0.4f),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            color = if (enabled) OnionPalette.TextPrimary else OnionPalette.TextMuted,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                color = OnionPalette.Teal,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The brand mark of Trap: the wordmark of the board and the two onions.
 *
 * @param modifier the modifier of the mark.
 * @param wordmark the size of the wordmark.
 */
@Composable
fun OvBrandMark(
    modifier: Modifier = Modifier,
    wordmark: Dp = 88.dp,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_neon_trap_logo),
            contentDescription = "Trap",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(wordmark)
                .height(wordmark * 0.5f),
        )
        Image(
            painter = painterResource(R.drawable.ic_neon_onion_twins),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(wordmark * 0.5f)
                .height(wordmark * 0.35f),
        )
    }
}

/**
 * A button of the chrome in the style of Safari on iOS.
 *
 * The button is a round plate of glass that floats above the page: a
 * translucent fill, a thin line around it and a soft shadow.  The glyph is
 * the one of the icon system of the design board and keeps its own colour.
 *
 * @param icon the glyph of the button.
 * @param contentDescription the description of the button.
 * @param onClick the user tapped the button.
 * @param modifier the modifier of the button.
 * @param enabled `false` dims the button and stops the taps.
 * @param accent the colour of the border of the plate.
 * @param size the size of the plate of the button.
 * @param glyph the size of the glyph.
 * @param elevation the shadow under the plate.
 * @param badge the number that the button carries; it is `0` when there is
 * no badge.
 */
@Composable
fun OvGlassButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = OnionPalette.Hairline,
    size: Dp = 44.dp,
    glyph: Dp = 26.dp,
    elevation: Dp = 8.dp,
    badge: Int = 0,
) {
    val shape = CircleShape
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .shadow(
                    elevation = elevation,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = 0.55f),
                    spotColor = Color.Black.copy(alpha = 0.55f),
                )
                .clip(shape)
                .background(OnionPalette.SurfaceRaised.copy(alpha = 0.86f))
                .border(1.dp, accent.copy(alpha = 0.9f), shape)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(glyph)
                    .alpha(if (enabled) 1f else 0.3f),
            )
        }
        if (badge > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp)
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(OnionPalette.Mint)
                    .border(1.5.dp, OnionPalette.SurfaceRaised, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge.toString(),
                    color = OnionPalette.OnTeal,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 10.sp,
                )
            }
        }
    }
}

/**
 * The plate of the address of the browser.
 *
 * The plate is the field of Safari: a rounded field that floats above the
 * bottom of the screen, with a translucent fill and a thin line around it.
 *
 * @param content the content of the field.
 * @param modifier the modifier of the plate.
 * @param border the colour of the line around the plate.
 */
@Composable
fun OvAddressPlate(
    modifier: Modifier = Modifier,
    border: Color = OnionPalette.Hairline,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.55f),
                spotColor = Color.Black.copy(alpha = 0.55f),
            )
            .clip(shape)
            .background(OnionPalette.SurfaceRaised.copy(alpha = 0.92f))
            .border(1.dp, border, shape),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

/** The icon button of the browser: a round plate with a thin outline. */
@Composable
fun OvIconButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = OnionPalette.Teal,
    enabled: Boolean = true,
    size: Dp = 38.dp,
) {
    val shape = CircleShape
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(if (enabled) OnionPalette.SurfaceHigh else OnionPalette.SurfaceRaised)
            .border(1.dp, if (enabled) OnionPalette.Hairline else OnionPalette.SurfaceRaised, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = if (enabled) tint else OnionPalette.TextMuted.copy(alpha = 0.4f),
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/**
 * The primary button of the browser: the amber of the board.
 *
 * @param icon the icon of the button; it may be `null`.
 */
@Composable
fun OvPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    enabled: Boolean = true,
) {
    OvTextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        enabled = enabled,
        container = OnionPalette.Amber,
        content = OnionPalette.OnAmber,
        border = null,
    )
}

/**
 * The secondary button of the browser: an outlined plate with the teal of
 * the brand.
 *
 * @param icon the icon of the button; it may be `null`.
 */
@Composable
fun OvSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    enabled: Boolean = true,
) {
    OvTextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        enabled = enabled,
        container = OnionPalette.SurfaceHigh,
        content = OnionPalette.Teal,
        border = BorderStroke(1.dp, OnionPalette.TealDeep),
    )
}

/** The button that carries a text and, optionally, an icon. */
@Composable
private fun OvTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: Painter?,
    enabled: Boolean,
    container: Color,
    content: Color,
    border: BorderStroke?,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) container else OnionPalette.SurfaceRaised)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Image(
                painter = icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = if (enabled) content else OnionPalette.TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * The chip of a PGP action.
 *
 * The colour of the chip tells what the action does: the amber encrypts, the
 * teal decrypts and the mint of the board handles the keys.
 */
@Composable
fun OvActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    kind: ChipKind = ChipKind.Decrypt,
) {
    val accent = when (kind) {
        ChipKind.Encrypt -> OnionPalette.Amber
        ChipKind.Decrypt -> OnionPalette.Teal
        ChipKind.Key -> OnionPalette.Mint
    }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(OnionPalette.SurfaceHigh)
            .border(1.dp, accent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                painter = icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** The kinds of the action chips. */
enum class ChipKind { Encrypt, Decrypt, Key }

/** The plate that holds a label, used for the state of the transport. */
@Composable
fun OvBadge(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = OnionPalette.TextMuted,
    container: Color = OnionPalette.SurfaceHigh,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(container)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * The progress bar of the bootstrap.
 *
 * The bar is painted with the gradient of the brand, so that it looks like
 * the neon of the board fills up while the client bootstraps.
 */
@Composable
fun OvProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(OnionPalette.SurfaceHigh),
    ) {
        Box(
            modifier = Modifier
                .height(height)
                .fillMaxWidth(fraction)
                .clip(RoundedCornerShape(height / 2))
                .background(
                    Brush.horizontalGradient(
                        listOf(OnionPalette.Teal, OnionPalette.Mint),
                    ),
                ),
        )
    }
}

/**
 * The tab badge of the top bar.
 *
 * @param count the number of the tabs of the browser.
 */
@Composable
fun OvTabBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(OnionPalette.Teal.copy(alpha = 0.14f))
            .border(1.dp, OnionPalette.Teal.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = count.toString(),
            color = OnionPalette.Mint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}
