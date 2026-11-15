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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

const val DUCKDUCKGO_ONION_URL =
    "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/"

/**
 * Native Compose Start Page rendered over `about:blank`.
 *
 * Displays live Zero-JS & Hidden-Service-Only security posture, a verified v3
 * `.onion` launch card (DuckDuckGo `.onion`), and quick actions for PGP Keyring
 * and Settings. Every row obeys the `<= 2:1` label density ceiling and uses
 * uniform perimeter borders without left-edge accent stripes.
 */
@Composable
fun StartPage(
    state: BrowserUiState,
    onNavigate: (String) -> Unit,
    onOpenKeyring: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSecurity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TrapTheme.colors
    val cardShape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface2)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp)
            .testTag("start_page"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Brand Lockup
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (colors.useNeonSprites) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_neon_trap_logo),
                        contentDescription = "Trap",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .width(104.dp)
                            .height(44.dp),
                    )
                    Image(
                        painter = painterResource(R.drawable.ic_neon_onion_twins),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .width(48.dp)
                            .height(36.dp),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(colors.control)
                        .border(1.dp, colors.border, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ov_trap_mark),
                        contentDescription = "Trap",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Trap",
                        color = colors.textPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Zero-JavaScript Tor Browser & Native PGP",
                        color = colors.textMuted,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        // Security & Transport Card (tappable to open Tor Security & Circuit Sheet)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(colors.surface)
                .border(1.dp, colors.cardBorder, cardShape)
                .clickable(role = Role.Button, onClick = onOpenSecurity)
                .padding(14.dp)
                .testTag("start_security_card"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StartStatusRow(
                label = "JavaScript & WebAssembly",
                value = "Disabled",
                valueColor = colors.green,
            )
            StartStatusRow(
                label = "Traffic Policy",
                value = if (state.hiddenServiceOnly) "Hidden Services (.onion) Only" else "Tor Proxy (Clearnet Allowed)",
                valueColor = if (state.hiddenServiceOnly) colors.teal else colors.amber,
            )
            StartStatusRow(
                label = "Arti Tor Transport",
                value = if (state.tor.ready) {
                    "Ready"
                } else {
                    "Bootstrapping ${state.tor.percent}%"
                },
                valueColor = if (state.tor.ready) colors.green else colors.amber,
            )
        }

        // Verified v3 .onion Directory / Search Launch Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(colors.surface)
                .border(1.dp, colors.teal.copy(alpha = 0.65f), cardShape)
                .clickable(
                    role = Role.Button,
                    onClick = { onNavigate(DUCKDUCKGO_ONION_URL) },
                )
                .padding(14.dp)
                .testTag("start_ddg_onion_card"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.tealContainer)
                    .border(1.dp, colors.teal, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                TrapIcon(
                    glyph = TrapGlyph.Onion,
                    tint = colors.teal,
                    size = 20.dp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DuckDuckGo (.onion v3)",
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
                    color = colors.textMuted,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            OvBadge(
                text = "Launch",
                accent = colors.onGoFill,
                container = colors.goFill,
            )
        }

        // Quick Actions: PGP Keyring & Settings
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OvSecondaryButton(
                text = if (state.keys.isEmpty()) "PGP Keyring" else "PGP Keyring (${state.keys.size})",
                glyph = TrapGlyph.Key,
                onClick = onOpenKeyring,
                modifier = Modifier
                    .weight(1f)
                    .testTag("start_open_keyring"),
            )
            OvSecondaryButton(
                text = "Settings",
                glyph = TrapGlyph.Settings,
                onClick = onOpenSettings,
                modifier = Modifier
                    .weight(1f)
                    .testTag("start_open_settings"),
            )
        }
    }
}

@Composable
private fun StartStatusRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
) {
    val colors = TrapTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.textMuted,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}
