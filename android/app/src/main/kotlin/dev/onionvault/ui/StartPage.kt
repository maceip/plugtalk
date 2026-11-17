package dev.onionvault.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.onionvault.R

const val DUCKDUCKGO_ONION_URL =
    "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/"

/**
 * Minimal, calm blank-tab canvas rendered over `about:blank`.
 *
 * Renders only a quiet centered lockup on [TrapColors.background]: small `48.dp`
 * Trap mark + `"Trap"` title + 1-line live Tor status (tappable to open
 * [SecurityCircuitSheet]). Zero cards, zero instructions, zero orange badges.
 */
@Composable
fun StartPage(
    state: BrowserUiState,
    onOpenSecurity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TrapTheme.colors
    val statusText = if (state.tor.ready) {
        "Tor ready"
    } else {
        "Connecting to Tor · ${state.tor.percent}%"
    }
    val statusColor = if (state.tor.ready) colors.green else colors.amber

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .testTag("start_page"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ov_trap_mark),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(72.dp),
            )

            Text(
                text = "Trap",
                color = colors.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = onOpenSecurity)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics { contentDescription = "Tor security and circuit status" }
                    .testTag("start_tor_status"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
