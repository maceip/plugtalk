package dev.onionvault.ui

import androidx.compose.ui.graphics.Color

/**
 * The colours of Trap.
 *
 * They are the seven swatches of the brand palette of the design board
 * ("TRAP Neon Graffiti Browser Board"): the neon teal carries the browser,
 * the pink and the mint are the accents of the controls, the amber and the
 * cream are the warmth of the highlights and the purple is the shadow of the
 * mural.  Every surface is a near black, so that the sliced neon glyphs of
 * the board blend into the chrome instead of sitting on a light plate.
 *
 * ## The catalogue of the palette
 *
 * | name | value | where it is used |
 * |---|---|---|
 * | `Teal` | `#00E4C1` | the primary: the border of the plates, the text of the trailing column of the menu, the colour of the cursor |
 * | `TealDeep` | `#00A88F` | the border of the tab card |
 * | `Mint` | `#67EEE1` | the text of the tab badge, the cursor, the trace of the crash |
 * | `Pink` | `#FD96C3` | the accent: the blocked request, the button that clears the address, "Try again" |
 * | `PinkDeep` | `#B85C8B` | the plate of the crash screen |
 * | `Amber` | `#E8933E` | the action: the primary button, the chip that encrypts, the state of the transport |
 * | `Cream` | `#FBD4A9` | the warmth of the highlights: the glyph of the crash screen |
 * | `Purple` | `#8F55A6` | the shadow of the mural — **nothing uses it** |
 * | `PurpleDeep` | `#5A3568` | — **nothing uses it** |
 * | `Surface` | `#15181F` | the background of the application |
 * | `SurfaceRaised` | `#1E222C` | the bars and the plates |
 * | `SurfaceHigh` | `#2A2F3C` | the cards, the chips, the buttons |
 * | `Hairline` | `#3A4050` | the line between two surfaces |
 * | `TextPrimary` | `#F2EEE6` | the text |
 * | `TextMuted` | `#A8A2B8` | the text that matters less |
 * | `OnAmber` | `#2A1608` | the text on the amber |
 * | `OnTeal` | `#04241F` | the text on the teal |
 * | `Good` / `Warn` / `Bad` | `#00E4C1` / `#E8933E` / `#FD96C3` | the semantic colours: the signature that is good, the one that is not, the request that was blocked |
 *
 * ## The colours that are **not** in this palette
 *
 * The palette is the ground truth of the interface, and three things get
 * out of it:
 *
 *   * **the system bars.**  The theme of the platform uses `#FF0B0E11` for
 *     the window and `#FF101418` for the status bar and the navigation
 *     bar (`res/values/themes.xml`).  Neither is in this palette, so the
 *     system is a third dark colour, and it does not match the chrome.
 *   * **the styles that are written into the page.**  The engine marks the
 *     fields of the page with four hard-coded colours — `#00E5FF`,
 *     `#00FF66`, `#FFB300` and `#CE93D8`
 *     (`crates/ov-cef/src/dom_model.rs`).  They are the loudest colours of
 *     the whole application and they belong to no palette at all.
 *   * **the colours of the previews.**  The mocks of the pages in
 *     `ui/previews/OnionVaultPreviews.kt` carry their own colours, which
 *     is right: a mock is not the interface.
 */
object OnionPalette {

    /** The neon teal of the primary colour of the board. */
    val Teal = Color(0xFF00E4C1)

    /** The deep teal below the primary colour. */
    val TealDeep = Color(0xFF00A88F)

    /** The mint of the highlights of the board. */
    val Mint = Color(0xFF67EEE1)

    /** The pink of the graffiti of the board. */
    val Pink = Color(0xFFFD96C3)

    /** The deep pink of the shadow of the graffiti. */
    val PinkDeep = Color(0xFFB85C8B)

    /** The amber of the leaves of the board. */
    val Amber = Color(0xFFE8933E)

    /** The cream of the highlights of the board. */
    val Cream = Color(0xFFFBD4A9)

    /** The purple of the mural of the board. */
    val Purple = Color(0xFF8F55A6)

    /** The deep purple of the shadow of the mural. */
    val PurpleDeep = Color(0xFF5A3568)

    /** The near black of the background of the board. */
    val Surface = Color(0xFF15181F)

    /** The background of a bar. */
    val SurfaceRaised = Color(0xFF1E222C)

    /** The background of a card, a chip or a button. */
    val SurfaceHigh = Color(0xFF2A2F3C)

    /** The line between two surfaces. */
    val Hairline = Color(0xFF3A4050)

    /** The colour of the text. */
    val TextPrimary = Color(0xFFF2EEE6)

    /** The colour of a text that is less important. */
    val TextMuted = Color(0xFFA8A2B8)

    /** The colour of a text on the amber of a button. */
    val OnAmber = Color(0xFF2A1608)

    /** The colour of a text on the teal of a button. */
    val OnTeal = Color(0xFF04241F)

    /** The colour that says that something works. */
    val Good = Color(0xFF00E4C1)

    /** The colour that says that something needs attention. */
    val Warn = Color(0xFFE8933E)

    /** The colour of a bad news. */
    val Bad = Color(0xFFFD96C3)
}
