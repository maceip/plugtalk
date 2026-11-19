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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.onionvault.R

const val DUCKDUCKGO_ONION_URL: String = TrustedOnionDirectory.DUCKDUCKGO_ONION_HOME_URL

@Immutable
internal data class ZineSheetColors(
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

internal val TrapCollageOverlayColors = ZineSheetColors(
    canvasFallback = Color(0xFF0A0C0F),
    steelRule = Color(0xFF3A4048),
    mastheadStencil = Color(0xFFF5B400),
    sectionBg = Color(0xF511151B),
    sectionInk = Color(0xFFD6DEE8),
    rowEvenBg = Color(0xF20B0D10),
    rowOddBg = Color(0xF2090B0E),
    titleInk = Color(0xFFF4F7FA),
    descInk = Color(0xFFA0AAB8),
    keyplateBg = Color(0xF506080B),
    keyplateBorder = Color(0xFF3A4048),
    onionKeyInk = Color(0xFF4ADE80),
)

/**
 * Authentic 100% Unobstructed `TRAP` Street-Zine Collage Homepage (`metatools-reference.jpg` 1:1).
 *
 * Shows the full-bleed `R.drawable.bg_trap_street_zine` street-zine artwork (`TRAP` yellow-black
 * graffiti + `i-Kee Koos` + yellow `INNOVATION` flyer + torn typewriter zine sheet at bottom)
 * completely raw and uncovered beneath the floating 1997 MetaTools 3D hardware pods.
 *
 * [TrustedOnionDirectory] (all 11 cryptographically verified `.onion` services) is reachable in
 * 1 tap via BOTH the CRT TV address editor (`crt_address_pod` tap) AND `PhotoSoapSideRemote`'s
 * `"Onion Sites"` drawer (`remote_onion_sites`).
 */
@Composable
fun StartPage(
    @Suppress("UNUSED_PARAMETER") state: BrowserUiState,
    @Suppress("UNUSED_PARAMETER") onOpenSecurity: () -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") onNavigate: (String) -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(TrapCollageOverlayColors.canvasFallback)
            .testTag("start_page"),
    ) {
        val isPortraitViewport = maxWidth < maxHeight * 0.68f
        Image(
            painter = painterResource(R.drawable.bg_trap_street_zine),
            contentDescription = null,
            contentScale = if (isPortraitViewport) ContentScale.FillBounds else ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp)
                .testTag("start_zine_wallpaper"),
        )
    }
}

/**
 * Hardware-framed directory of all 11 SHA3-256 verified v3 `.onion` services, rendered inside
 * the CRT address editor overlay when the user taps `RecessedCrtAddressPod`.
 */
@Composable
internal fun TrustedOnionDirectorySheet(
    onSelectEntry: (OnionServiceEntry) -> Unit,
    modifier: Modifier = Modifier,
    zine: ZineSheetColors = TrapCollageOverlayColors,
) {
    val sheetShape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            color = zine.sectionInk,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.2.sp,
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
            .heightIn(min = 56.dp)
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
            .padding(horizontal = 13.dp, vertical = 9.dp)
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
                .clip(RoundedCornerShape(4.dp))
                .background(zine.keyplateBg)
                .border(1.dp, zine.keyplateBorder, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
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
