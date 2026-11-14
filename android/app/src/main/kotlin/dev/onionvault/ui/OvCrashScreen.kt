package dev.onionvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.onionvault.R

/**
 * The screen that shows the trace of a crash.
 *
 * The application does not die silently: the trace of the error that
 * stopped it is written into the private directory of the application, and
 * the next start shows this screen instead of the browser.  The trace can
 * be copied into the clipboard, and the user can try again: the report is
 * forgotten and the browser starts as if nothing had happened.
 *
 * The screen wears the colours of the design board: the near black of the
 * panel, the pink of the graffiti and the teal of the brand.
 *
 * ## The catalogue of the surfaces
 *
 * | surface | glyph | what it does |
 * |---|---|---|
 * | the plate of the title | `ic_neon_menu` | the mark of the screen, in the cream of the board |
 * | the trace | — | the whole report, in the mint of the board, monospace, scrollable in both directions |
 * | "Copy the trace" | `ic_neon_sign_pgp` | the trace goes into the clipboard |
 * | "Try again" | `ic_neon_reload` | the report is forgotten and the browser starts |
 *
 * The trace comes from three sources, in the order of how close they are to
 * the crash: the file of the **native guard** (the signal that killed the
 * process), the file of the **Java handler** (the stack trace of an
 * exception) and the **record of the platform** (the reason of the exit
 * and the tombstone of the system).
 *
 * **This screen has no screenshot test.**  It is the one screen that must
 * never be wrong, and it is the only one that is not rendered by
 * `ScreenshotTest.kt`.
 */
@Composable
fun OvCrashScreen(
    report: String,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OnionPalette.Surface)
            .padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(OnionPalette.PinkDeep),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    painter = painterResource(R.drawable.ic_neon_menu),
                    contentDescription = null,
                    tint = OnionPalette.Cream,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Trap stopped",
                    color = OnionPalette.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "the trace of the error is below",
                    color = OnionPalette.TextMuted,
                    fontSize = 13.sp,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(OnionPalette.SurfaceRaised)
                .padding(14.dp),
        ) {
            Text(
                text = report,
                color = OnionPalette.Mint,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OvCrashButton(
                label = "Copy the trace",
                glyph = R.drawable.ic_neon_sign_pgp,
                accent = OnionPalette.Teal,
                onClick = onCopy,
                modifier = Modifier.weight(1f),
            )
            OvCrashButton(
                label = "Try again",
                glyph = R.drawable.ic_neon_reload,
                accent = OnionPalette.Pink,
                onClick = onRetry,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A button of the crash screen.
 *
 * @param label the name of the action.
 * @param glyph the neon glyph of the action.
 * @param accent the colour of the plate.
 * @param onClick the user tapped the button.
 * @param modifier the modifier of the button.
 */
@Composable
private fun OvCrashButton(
    label: String,
    glyph: Int,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = OnionPalette.SurfaceHigh,
            contentColor = accent,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent),
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(glyph),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
