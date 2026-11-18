package dev.onionvault.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.onionvault.R

const val DUCKDUCKGO_ONION_URL: String = TrustedOnionDirectory.DUCKDUCKGO_ONION_HOME_URL

@Immutable
private data class ZineSheetColors(
    val canvasFallback: Color,
    val steelRule: Color,
    val mastheadStencil: Color,
    val sectionBg: Color,
    val sectionInk: Color,
    val rowEvenBg: Color,
    val rowOddBg: Color,
    val titleInk: Color,
    val descInk: Color,
    val keyplateBg: Color,
    val keyplateBorder: Color,
    val onionKeyInk: Color,
)

private val TrapCollageOverlayColors = ZineSheetColors(
    canvasFallback = Color(0xFF0A0C0F),
    steelRule = Color(0xFF3A4048),
    mastheadStencil = Color(0xFFF5B400),
    sectionBg = Color(0xF011151B),
    sectionInk = Color(0xFFD6DEE8),
    rowEvenBg = Color(0xE60B0D10), // #0B0D10 at 90% alpha
    rowOddBg = Color(0xE6090B0E),
    titleInk = Color(0xFFF4F7FA),
    descInk = Color(0xFFA0AAB8),
    keyplateBg = Color(0xF206080B),
    keyplateBorder = Color(0xFF3A4048),
    onionKeyInk = Color(0xFF4ADE80),
)

/**
 * Authentic `TRAP` Street-Zine Collage Homepage rendered over `about:blank`.
 *
 * Layout & Ergonomics:
 * 1. Full-bleed `R.drawable.bg_trap_street_zine` street-zine collage (`TRAP` yellow-black graffiti +
 *    `i-Kee Koos` + `NNOVATION DAM` posters + torn typewriter zine paper) fixed edge-to-edge behind
 *    a scrollable foreground `Column`.
 * 2. Top hero viewport (`~48%` of screen height) stays **OPEN** over the `TRAP` street-zine artwork,
 *    with only a compact top-right `[ TRAP · ● Tor ready ]` hardware badge (`start_tor_status`).
 * 3. Below the hero window (scrolling smoothly up over the wallpaper with `156.dp` bottom clearance
 *    above the `1.5x` floating hardware pods):
 *    - `52.dp`-tall recessed `#050608` (`92%` alpha, `1.5.dp` `#3A4048` steel bevel) CRT Search Box
 *      (`start_search_box`, `15.sp` phosphor prompt), followed by
 *    - Dark torn-zine steel-bordered [TrustedOnionDirectory] rows (`#0B0D10` at `90%` alpha)
 *      obeying strict `<= 2:1` label density.
 */
@Composable
fun StartPage(
    state: BrowserUiState,
    onOpenSecurity: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
) {
    val zine = TrapCollageOverlayColors
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val submitSearch: () -> Unit = {
        val resolved = TrustedOnionDirectory.resolveSearchOrAddress(searchQuery)
        if (resolved != null) {
            focusManager.clearFocus()
            onNavigate(resolved)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(zine.canvasFallback)
            .testTag("start_page"),
    ) {
        // 1. Fixed Full-Bleed Authentic TRAP Street-Zine Collage Wallpaper
        //    FillWidth + TopCenter maps the full street-poster & TRAP door width edge-to-edge
        //    with zero horizontal cropping so `i-Kee Koos`, `NNOVATION DAM`, and all 4 letters
        //    of `T-R-A-P` are 100% visible on first launch!
        Image(
            painter = painterResource(R.drawable.bg_trap_street_zine),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .testTag("start_zine_wallpaper"),
        )

        // Seamless lower fade from the torn typewriter zine sheet into dark #0A0C0F below
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.62f to Color.Transparent,
                        0.82f to Color(0xCC0A0C0F),
                        1.0f to zine.canvasFallback,
                    ),
                ),
        )

        val heroWindowHeight = (maxHeight * 0.47f).coerceIn(360.dp, 445.dp)

        // 2. Scrollable Foreground over Fixed Street-Zine Wallpaper
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Open Hero Window (~48% viewport height) showcasing the full TRAP graffiti & flyers
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroWindowHeight),
            ) {
                HeroTorHardwareBadge(
                    state = state,
                    zine = zine,
                    onOpenSecurity = onOpenSecurity,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }

            RecessedCrtOnionSearchBox(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onClear = { searchQuery = "" },
                onSubmit = submitSearch,
                zine = zine,
            )

            TrustedOnionDirectorySheet(
                zine = zine,
                onSelectEntry = { entry ->
                    focusManager.clearFocus()
                    onNavigate(entry.url)
                },
            )

            // 156.dp clearance so bottom-most directory rows scroll cleanly into full view
            // above the 1.5x floating KPT hardware pods!
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(156.dp)
                    .navigationBarsPadding()
                    .testTag("start_bottom_clearance"),
            )
        }
    }
}

/**
 * Compact top-right anodized hardware status keyplate (`start_tor_status`) tucked into the
 * upper-right corner so the `TRAP` street-zine collage remains 100% open across the hero viewport.
 * Strictly 2 labels (`TRAP` + live Tor status = `1:1 <= 2:1` label density).
 */
@Composable
private fun HeroTorHardwareBadge(
    state: BrowserUiState,
    zine: ZineSheetColors,
    onOpenSecurity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusText = if (state.tor.ready) {
        "Tor ready"
    } else {
        "Connecting to Tor \u00B7 ${state.tor.percent}%"
    }
    val ledColor = if (state.tor.ready) Color(0xFF39FF14) else Color(0xFFFFB000)
    val badgeShape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(badgeShape)
            .background(Color(0xE807090C))
            .border(1.5.dp, zine.steelRule, badgeShape)
            .clickable(role = Role.Button, onClick = onOpenSecurity)
            .padding(horizontal = 11.dp, vertical = 7.dp)
            .semantics { contentDescription = "Tor security and circuit status" }
            .testTag("start_tor_status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "TRAP",
            color = zine.mastheadStencil,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.4.sp,
            maxLines = 1,
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(14.dp)
                .background(zine.steelRule),
        )

        Box(
            modifier = Modifier
                .size(8.dp)
                .drawBehind {
                    drawCircle(
                        color = ledColor.copy(alpha = 0.32f),
                        radius = size.minDimension * 0.9f,
                    )
                    drawCircle(
                        color = ledColor,
                        radius = size.minDimension / 2f,
                    )
                },
        )

        Text(
            text = statusText,
            color = ledColor,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecessedCrtOnionSearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    zine: ZineSheetColors,
) {
    val bezelOuter = Color(0xFF3A4048)
    val bezelHighlight = Color(0xFF7D8692)
    val bezelShadow = Color(0xFF15181D)
    val crtWell = Color(0xEB050608) // #050608 at 92% alpha
    val phosphorInk = Color(0xFF39FF14)
    val phosphorHint = Color(0xFF6B9475)
    val outerShape = RoundedCornerShape(10.dp)
    val innerShape = RoundedCornerShape(8.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outerShape)
            .background(bezelOuter)
            .drawBehind {
                val bevel = 1.5.dp.toPx()
                drawRect(
                    color = bezelHighlight,
                    topLeft = Offset.Zero,
                    size = Size(size.width, bevel),
                )
                drawRect(
                    color = bezelHighlight,
                    topLeft = Offset.Zero,
                    size = Size(bevel, size.height),
                )
                drawRect(
                    color = bezelShadow,
                    topLeft = Offset(0f, size.height - bevel),
                    size = Size(size.width, bevel),
                )
                drawRect(
                    color = bezelShadow,
                    topLeft = Offset(size.width - bevel, 0f),
                    size = Size(bevel, size.height),
                )
            }
            .padding(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(innerShape)
                .background(crtWell)
                .border(1.5.dp, Color(0xFF3A4048), innerShape)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xCC000000), Color.Transparent),
                            startY = 0f,
                            endY = 7.dp.toPx(),
                        ),
                    )
                    drawLine(
                        color = Color(0x18FFFFFF),
                        start = Offset(10.dp.toPx(), 1.5.dp.toPx()),
                        end = Offset(size.width - 10.dp.toPx(), 1.5.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            TrapIcon(
                glyph = TrapGlyph.Onion,
                tint = phosphorInk,
                size = 18.dp,
                forceVector = true,
            )

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = phosphorInk,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(phosphorInk),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Search,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { onSubmit() },
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("start_search_box"),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search DuckDuckGo .onion or enter address...",
                                color = phosphorHint,
                                fontSize = 13.5.sp,
                                letterSpacing = (-0.5).sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .clickable(
                            role = Role.Button,
                            onClick = onClear,
                        )
                        .semantics { contentDescription = "Clear search input" }
                        .testTag("start_search_clear"),
                    contentAlignment = Alignment.Center,
                ) {
                    TrapIcon(
                        glyph = TrapGlyph.Close,
                        tint = Color(0xFF98A89E),
                        size = 16.dp,
                        forceVector = true,
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(zine.mastheadStencil)
                        .clickable(
                            role = Role.Button,
                            onClick = onSubmit,
                        )
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                        .testTag("start_search_submit"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "GO",
                        color = Color(0xFF12100C),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrustedOnionDirectorySheet(
    zine: ZineSheetColors,
    onSelectEntry: (OnionServiceEntry) -> Unit,
) {
    val sheetShape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(sheetShape)
            .border(width = 1.5.dp, color = zine.steelRule, shape = sheetShape)
            .testTag("trusted_onion_directory"),
    ) {
        TrustedOnionDirectory.entriesBySection.forEachIndexed { sectionIndex, (section, sectionEntries) ->
            ZineDirectorySectionHeader(
                title = section.headerTitle,
                zine = zine,
                drawTopRule = sectionIndex > 0,
            )

            sectionEntries.forEachIndexed { rowIndex, entry ->
                OnionDirectoryRow(
                    entry = entry,
                    isEven = rowIndex % 2 == 0,
                    isLastInSection = rowIndex == sectionEntries.lastIndex,
                    zine = zine,
                    onClick = { onSelectEntry(entry) },
                )
            }
        }
    }
}

@Composable
private fun ZineDirectorySectionHeader(
    title: String,
    zine: ZineSheetColors,
    drawTopRule: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(zine.sectionBg)
            .drawBehind {
                val rulePx = 1.dp.toPx()
                if (drawTopRule) {
                    drawLine(
                        color = zine.steelRule,
                        start = Offset(0f, rulePx / 2f),
                        end = Offset(size.width, rulePx / 2f),
                        strokeWidth = rulePx,
                    )
                }
                drawLine(
                    color = zine.steelRule,
                    start = Offset(0f, size.height - rulePx / 2f),
                    end = Offset(size.width, size.height - rulePx / 2f),
                    strokeWidth = rulePx,
                )
                drawRect(
                    color = zine.mastheadStencil,
                    topLeft = Offset.Zero,
                    size = Size(3.5.dp.toPx(), size.height),
                )
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = title,
            color = zine.sectionInk,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.3.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Single 1-tap directory launch row obeying strict `<= 2:1` UI Label Density:
 * - 1 primary label: [OnionServiceEntry.title]
 * - 2 secondary tokens: [OnionServiceEntry.description] and [OnionServiceEntry.shortOnionKey]
 * - Zero extra badges or chips.
 */
@Composable
private fun OnionDirectoryRow(
    entry: OnionServiceEntry,
    isEven: Boolean,
    isLastInSection: Boolean,
    zine: ZineSheetColors,
    onClick: () -> Unit,
) {
    val rowBg = if (isEven) zine.rowEvenBg else zine.rowOddBg

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .background(rowBg)
            .clickable(role = Role.Button, onClick = onClick)
            .drawBehind {
                if (!isLastInSection) {
                    val rulePx = 1.dp.toPx()
                    drawLine(
                        color = zine.steelRule,
                        start = Offset(0f, size.height - rulePx / 2f),
                        end = Offset(size.width, size.height - rulePx / 2f),
                        strokeWidth = rulePx,
                    )
                }
            }
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag("onion_dir_${entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = entry.title,
                color = zine.titleInk,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.description,
                color = zine.descInk,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(zine.keyplateBg)
                .border(1.dp, zine.keyplateBorder, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.shortOnionKey,
                color = zine.onionKeyInk,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}
