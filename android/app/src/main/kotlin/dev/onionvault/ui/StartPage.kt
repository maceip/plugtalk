package dev.onionvault.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
    val canvas: Color,
    val gridLine: Color,
    val halftoneDot: Color,
    val steelRule: Color,
    val cropTick: Color,
    val mastheadBg: Color,
    val mastheadStencil: Color,
    val sprayAccent: Color,
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

private fun zineColorsFor(isDark: Boolean): ZineSheetColors = if (isDark) {
    ZineSheetColors(
        canvas = Color(0xFF0D0F12),
        gridLine = Color(0xFF14181E),
        halftoneDot = Color(0xFF1A1F27),
        steelRule = Color(0xFF2A2F36),
        cropTick = Color(0xFF4D5662),
        mastheadBg = Color(0xFF12161C),
        mastheadStencil = Color(0xFFE8A33D),
        sprayAccent = Color(0xFFFF1A6C),
        sectionBg = Color(0xFF14181F),
        sectionInk = Color(0xFFC6CFD9),
        rowEvenBg = Color(0xFF101318),
        rowOddBg = Color(0xFF0D0F12),
        titleInk = Color(0xFFF2F5F8),
        descInk = Color(0xFF919BA8),
        keyplateBg = Color(0xFF07090C),
        keyplateBorder = Color(0xFF232A32),
        onionKeyInk = Color(0xFF4ADE80),
    )
} else {
    ZineSheetColors(
        canvas = Color(0xFFF2EFE9),
        gridLine = Color(0xFFE5DFD4),
        halftoneDot = Color(0xFFDAD3C6),
        steelRule = Color(0xFFC2BBB0),
        cropTick = Color(0xFF7B7468),
        mastheadBg = Color(0xFFE8E2D6),
        mastheadStencil = Color(0xFFC47A12),
        sprayAccent = Color(0xFFD81B60),
        sectionBg = Color(0xFFE3DDD1),
        sectionInk = Color(0xFF262B33),
        rowEvenBg = Color(0xFFFAF8F4),
        rowOddBg = Color(0xFFF2EFE9),
        titleInk = Color(0xFF14171C),
        descInk = Color(0xFF4D5562),
        keyplateBg = Color(0xFF0B0E12),
        keyplateBorder = Color(0xFF2A2F36),
        onionKeyInk = Color(0xFF4ADE80),
    )
}

/**
 * Street-Zine / Industrial First-Open & New-Tab Homepage rendered over `about:blank`.
 *
 * Combines:
 * 1. Raw dark wheatpaste / concrete grid surface (`#0D0F12`) with crisp `1.dp` `#2A2F36` steel rules.
 * 2. Bold `TRAP` street-stencil masthead lockup + live 1-line Tor transport status (`start_tor_status`).
 * 3. Recessed phosphor CRT `.onion` search / address input (`start_search_box`) wired to DuckDuckGo v3 `.onion`.
 * 4. Cryptographically verified [TrustedOnionDirectory] (`trusted_onion_directory`) obeying `<= 2:1` label density,
 *    plus `132.dp` bottom clearance so every item scrolls cleanly above floating hardware pods.
 */
@Composable
fun StartPage(
    state: BrowserUiState,
    onOpenSecurity: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
) {
    val trapColors = TrapTheme.colors
    val zine = zineColorsFor(trapColors.isDark)
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(zine.canvas)
            .drawBehind {
                // Concrete cutting-mat 24.dp grid + subtle diagonal zine registration stipple
                val stepPx = 24.dp.toPx()
                val strokePx = 1.dp.toPx()
                var x = stepPx
                while (x < size.width) {
                    drawLine(
                        color = zine.gridLine,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = strokePx,
                    )
                    x += stepPx
                }
                var y = stepPx
                while (y < size.height) {
                    drawLine(
                        color = zine.gridLine,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokePx,
                    )
                    y += stepPx
                }
                val dotRadius = 0.8.dp.toPx()
                var gy = stepPx / 2f
                var rowIdx = 0
                while (gy < size.height) {
                    var gx = if (rowIdx % 2 == 0) stepPx / 2f else stepPx
                    while (gx < size.width) {
                        drawCircle(
                            color = zine.halftoneDot,
                            radius = dotRadius,
                            center = Offset(gx, gy),
                        )
                        gx += stepPx * 2f
                    }
                    gy += stepPx * 2f
                    rowIdx++
                }
            }
            .testTag("start_page"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ZineMastheadHeader(
                state = state,
                zine = zine,
                onOpenSecurity = onOpenSecurity,
            )

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

            // 132.dp clearance so the bottom-most directory items scroll cleanly into full view
            // above the floating MetaTools Tri-Pod hardware cluster.
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .navigationBarsPadding()
                    .testTag("start_bottom_clearance"),
            )
        }
    }
}

@Composable
private fun ZineMastheadHeader(
    state: BrowserUiState,
    zine: ZineSheetColors,
    onOpenSecurity: () -> Unit,
) {
    val statusText = if (state.tor.ready) {
        "Tor ready"
    } else {
        "Connecting to Tor \u00B7 ${state.tor.percent}%"
    }
    val ledColor = if (state.tor.ready) Color(0xFF39FF14) else Color(0xFFFFB000)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(zine.mastheadBg)
            .border(width = 1.dp, color = zine.steelRule)
            .drawBehind {
                // Wheatpaste street-stencil amber bar + magenta registration tick on left edge
                val barWidth = 4.dp.toPx()
                drawRect(
                    color = zine.mastheadStencil,
                    topLeft = Offset.Zero,
                    size = Size(barWidth, size.height),
                )
                drawRect(
                    color = zine.sprayAccent,
                    topLeft = Offset(0f, 0f),
                    size = Size(barWidth, 12.dp.toPx()),
                )
                // Corner steel crop marks
                val tickLen = 7.dp.toPx()
                val stroke = 1.5.dp.toPx()
                drawLine(
                    color = zine.cropTick,
                    start = Offset(size.width - tickLen, stroke),
                    end = Offset(size.width, stroke),
                    strokeWidth = stroke,
                )
                drawLine(
                    color = zine.cropTick,
                    start = Offset(size.width - stroke, 0f),
                    end = Offset(size.width - stroke, tickLen),
                    strokeWidth = stroke,
                )
            }
            .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ov_trap_mark),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(42.dp),
            )

            // Street-zine stencil block lockup ("TRAP") — strictly 1 primary title
            Box(
                modifier = Modifier
                    .drawBehind {
                        // Hard 2dp amber stencil offset block behind dark cutout plate
                        val offsetPx = 2.dp.toPx()
                        drawRect(
                            color = zine.mastheadStencil,
                            topLeft = Offset(offsetPx, offsetPx),
                            size = size,
                        )
                        drawRect(
                            color = Color(0xFF0A0C0F),
                            topLeft = Offset.Zero,
                            size = size,
                        )
                    }
                    .border(1.dp, zine.mastheadStencil)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "TRAP",
                    color = zine.mastheadStencil,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.5.sp,
                    maxLines = 1,
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Live 1-line Tor transport readout (tappable to open Security / Tor Circuit pod/sheet)
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = 38.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(zine.keyplateBg)
                .border(1.dp, zine.keyplateBorder, RoundedCornerShape(3.dp))
                .clickable(role = Role.Button, onClick = onOpenSecurity)
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .semantics { contentDescription = "Tor security and circuit status" }
                .testTag("start_tor_status"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .drawBehind {
                        drawCircle(
                            color = ledColor.copy(alpha = 0.28f),
                            radius = size.minDimension * 0.85f,
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
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
    val bezelOuter = Color(0xFF565E68)
    val bezelHighlight = Color(0xFF8B94A0)
    val bezelShadow = Color(0xFF1B1F25)
    val crtWell = Color(0xFF050608)
    val phosphorInk = Color(0xFF39FF14)
    val phosphorHint = Color(0xFF5B7D63)

    // Bead-blasted pewter outer collar framing recessed #050608 CRT phosphor search well
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bezelOuter)
            .drawBehind {
                val bevel = 1.5.dp.toPx()
                // Top & left specular steel ridge
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
                // Bottom & right cast shadow bevel
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
            .padding(3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(crtWell)
                .border(1.dp, Color(0xFF17201B), RoundedCornerShape(4.dp))
                .drawWithContent {
                    drawContent()
                    // Recessed CRT top inset shadow + faint curved glass reflection + scanlines
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0xCC000000), Color.Transparent),
                            startY = 0f,
                            endY = 6.dp.toPx(),
                        ),
                    )
                    drawLine(
                        color = Color(0x14FFFFFF),
                        start = Offset(8.dp.toPx(), 1.5.dp.toPx()),
                        end = Offset(size.width - 8.dp.toPx(), 1.5.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            TrapIcon(
                glyph = TrapGlyph.Onion,
                tint = phosphorInk,
                size = 16.dp,
                forceVector = true,
            )

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = phosphorInk,
                    fontSize = 13.sp,
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
                                fontSize = 11.5.sp,
                                letterSpacing = (-0.2).sp,
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
                        .size(28.dp)
                        .clip(RoundedCornerShape(3.dp))
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
                        tint = Color(0xFF8A9990),
                        size = 15.dp,
                        forceVector = true,
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(zine.mastheadStencil)
                        .clickable(
                            role = Role.Button,
                            onClick = onSubmit,
                        )
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                        .testTag("start_search_submit"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "GO",
                        color = Color(0xFF12100C),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = zine.steelRule)
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
                    size = Size(3.dp.toPx(), size.height),
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            color = zine.sectionInk,
            fontSize = 11.sp,
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
            .heightIn(min = 58.dp)
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
            .padding(horizontal = 12.dp, vertical = 10.dp)
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
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.description,
                color = zine.descInk,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(zine.keyplateBg)
                .border(1.dp, zine.keyplateBorder, RoundedCornerShape(3.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.shortOnionKey,
                color = zine.onionKeyInk,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}
