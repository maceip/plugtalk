package dev.onionvault.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scrollable open-source license index for Trap's bundled runtime components:
 * Cefrium `0.19.0` (LGPL-3.0-or-later / Chromium BSD-3-Clause), Sequoia OpenPGP
 * (LGPL-2.0-or-later), and Arti Tor (MIT OR Apache-2.0).
 */
@Composable
fun LegalNoticesDialog(
    documents: List<LegalDocument>,
    onOpenDocument: (LegalDocument) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TrapTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textMuted,
        title = {
            Text(
                text = "Open-Source Licences",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Trap embeds Cefrium 0.19.0 (Android port of Chromium Embedded " +
                        "Framework, LGPL-3.0-or-later / Chromium BSD-3-Clause), Sequoia OpenPGP " +
                        "(LGPL-2.0-or-later), and Arti (MIT OR Apache-2.0). Full licence texts " +
                        "ship inside assets/licenses/ and alongside release artifacts.",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                if (documents.isEmpty()) {
                    Text(
                        text = "No licence files found in assets/licenses/.",
                        color = colors.textMuted,
                        fontSize = 13.sp,
                    )
                } else {
                    documents.forEach { document ->
                        DocumentRow(
                            document = document,
                            onClick = { onOpenDocument(document) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = colors.teal, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

/**
 * Strict <= 2:1 label density: 1 primary title + 1 byte-size subtitle + 1 trailing action word.
 */
@Composable
private fun DocumentRow(
    document: LegalDocument,
    onClick: () -> Unit,
) {
    val colors = TrapTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface2)
            .border(1.dp, colors.cardBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = document.title,
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            if (document.sizeBytes > 0) {
                Text(
                    text = formatSize(document.sizeBytes),
                    color = colors.textMuted,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Read",
            color = colors.teal,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun LegalDocumentDialog(
    document: LegalDocument,
    loadLines: suspend () -> List<String>,
    onDismiss: () -> Unit,
) {
    val colors = TrapTheme.colors
    var lines by remember(document.name) { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(document.name) {
        lines = withContext(Dispatchers.IO) { loadLines() }
    }

    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.border, shape)
                .padding(16.dp),
        ) {
            Text(
                text = document.title,
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))

            val content = lines
            if (content == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = colors.teal)
                }
            } else {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface2)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(content) { block ->
                        Text(
                            text = block,
                            color = colors.textBody,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OvSecondaryButton(text = "Close", onClick = onDismiss)
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%d KiB".format(bytes / 1024L)
    else -> "$bytes bytes"
}
