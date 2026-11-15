package dev.onionvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Multi-Tab Sheet backed by `TabSessionManager` sessions (switch, close `✕`, `+ New Tab`).
 */
@Composable
fun TabsSheet(
    tabs: List<TabItemState>,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TrapTheme.colors

    OvBottomSheet(
        title = "Tabs (${tabs.size})",
        onDismiss = onDismiss,
        modifier = modifier.testTag("tabs_sheet"),
        headerAction = {
            OvPrimaryButton(
                text = "+ New Tab",
                onClick = {
                    onNewTab()
                    onDismiss()
                },
                modifier = Modifier.testTag("tabs_new_tab"),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { tab ->
                val shape = RoundedCornerShape(12.dp)
                val displayTitle = tab.title.ifBlank {
                    if (isBlankOrStartUrl(tab.url)) "Trap Start" else tab.url
                }
                val displayUrl = tab.url.ifBlank { "about:blank" }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(if (tab.isActive) colors.control else colors.surface2)
                        .border(
                            width = 1.dp,
                            color = if (tab.isActive) colors.teal else colors.cardBorder,
                            shape = shape,
                        )
                        .clickable(
                            role = Role.Button,
                            onClick = {
                                onSelectTab(tab.id)
                                onDismiss()
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("tab_row_${tab.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayTitle,
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = if (tab.isActive) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = displayUrl,
                            color = colors.textMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (tab.isActive) {
                        Spacer(Modifier.width(8.dp))
                        OvBadge(
                            text = "Active",
                            accent = colors.teal,
                            container = colors.surface,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OvIconButton(
                        glyph = TrapGlyph.Close,
                        contentDescription = "Close tab $displayTitle",
                        onClick = { onCloseTab(tab.id) },
                        tint = colors.textMuted,
                        size = 32.dp,
                        modifier = Modifier.testTag("tab_close_${tab.id}"),
                    )
                }
            }
        }
    }
}

/**
 * Tor Security & Circuit Sheet.
 *
 * Renders vertical key-value rows (`<= 2:1` label density — zero 4-chip horizontal stacks!)
 * showing real Arti bootstrap %, summary, SOCKS5 loopback endpoint, active `.onion`
 * `isolationToken`, `isolatedGroupCount`, runtime `Hidden service only` toggle,
 * JS-disabled enforcement status, and `"New Identity / Reset Tor Isolation"`.
 */
@Composable
fun SecurityCircuitSheet(
    state: BrowserUiState,
    onToggleHiddenServiceOnly: (Boolean) -> Unit,
    onResetTorIsolation: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TrapTheme.colors
    val tor = state.tor
    val cardShape = RoundedCornerShape(12.dp)

    OvBottomSheet(
        title = "Tor Security & Circuit",
        onDismiss = onDismiss,
        modifier = modifier.testTag("security_circuit_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(colors.surface2)
                    .border(1.dp, colors.cardBorder, cardShape)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VerticalTelemetryRow(
                    title = "Arti Bootstrap",
                    value = if (tor.ready) {
                        "100% · ${tor.stage.ifBlank { "Ready" }}"
                    } else {
                        "${tor.percent}% · ${tor.stage.ifBlank { "Connecting" }}"
                    },
                    accent = if (tor.ready) colors.green else colors.amber,
                )
                if (!tor.ready) {
                    OvProgressBar(
                        progress = tor.percent / 100f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                VerticalTelemetryRow(
                    title = "Loopback SOCKS5",
                    value = tor.socksAddr.ifBlank { "127.0.0.1:1 (fail-closed)" },
                    accent = colors.textPrimary,
                )
                VerticalTelemetryRow(
                    title = "Site Isolation Token",
                    value = tor.isolationToken?.takeIf { it.isNotBlank() } ?: "None (no .onion host)",
                    accent = if (!tor.isolationToken.isNullOrBlank()) colors.teal else colors.textMuted,
                )
                VerticalTelemetryRow(
                    title = "Isolated .onion Groups",
                    value = tor.isolatedGroupCount.toString(),
                    accent = colors.textPrimary,
                )
                VerticalTelemetryRow(
                    title = "JavaScript & WebAssembly",
                    value = "Disabled (Cefrium + CDP)",
                    accent = colors.green,
                )
            }

            ToggleSettingRow(
                title = "Hidden Service Only",
                subtitle = "Refuse clearnet URLs; permit only v3 .onion & about:blank",
                checked = state.hiddenServiceOnly,
                onCheckedChange = onToggleHiddenServiceOnly,
                testTag = "security_hidden_service_switch",
            )

            OvPrimaryButton(
                text = "New Identity / Reset Tor Isolation",
                glyph = TrapGlyph.RefreshIdentity,
                onClick = onResetTorIsolation,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("security_reset_identity"),
            )
        }
    }
}

@Composable
private fun VerticalTelemetryRow(
    title: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
) {
    val colors = TrapTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = colors.textMuted,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Persisted Settings Sheet (`TrapPreferences`-backed):
 * - Theme Family (`SecureDrop` / `Neon Graffiti`)
 * - Mode (`Dark` / `Light`)
 * - `Hidden service only`
 * - `Require biometric/device unlock for secret keys`
 * - Default Zoom (`75%..150%`)
 * - Clear Ephemeral Session
 */
@Composable
fun SettingsSheet(
    state: BrowserUiState,
    onSelectThemeFamily: (TrapThemeFamily) -> Unit,
    onSelectThemeMode: (TrapThemeMode) -> Unit,
    onToggleHiddenServiceOnly: (Boolean) -> Unit,
    onToggleRequireBiometric: (Boolean) -> Unit,
    onSelectDefaultZoom: (Int) -> Unit,
    onClearSession: () -> Unit,
    onOpenLegalNotices: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TrapTheme.colors
    var confirmClearSession by remember { mutableStateOf(false) }

    OvBottomSheet(
        title = "Settings",
        onDismiss = onDismiss,
        modifier = modifier.testTag("settings_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            SettingSectionLabel("Theme Family")
            OvSegmentedRow(
                options = listOf(
                    TrapThemeFamily.SecureDrop to "SecureDrop",
                    TrapThemeFamily.NeonGraffiti to "Neon Graffiti",
                ),
                selected = state.themeFamily,
                onSelect = onSelectThemeFamily,
                modifier = Modifier.testTag("settings_theme_family"),
            )

            SettingSectionLabel("Appearance Mode")
            OvSegmentedRow(
                options = listOf(
                    TrapThemeMode.Dark to "Dark",
                    TrapThemeMode.Light to "Light",
                ),
                selected = state.themeMode,
                onSelect = onSelectThemeMode,
                modifier = Modifier.testTag("settings_theme_mode"),
            )

            ToggleSettingRow(
                title = "Hidden Service Only",
                subtitle = "Block all non-.onion requests in engine & SOCKS5 proxy",
                checked = state.hiddenServiceOnly,
                onCheckedChange = onToggleHiddenServiceOnly,
                testTag = "settings_hidden_service_switch",
            )

            ToggleSettingRow(
                title = "Require Biometric / Device Unlock",
                subtitle = "Confirm identity before decrypting, signing, or generating keys",
                checked = state.requireBiometric,
                onCheckedChange = onToggleRequireBiometric,
                testTag = "settings_biometric_switch",
            )

            SettingSectionLabel("Default Page Zoom (${state.defaultZoomPercent}%)")
            OvSegmentedRow(
                options = listOf(
                    75 to "75%",
                    90 to "90%",
                    100 to "100%",
                    125 to "125%",
                    150 to "150%",
                ),
                selected = state.defaultZoomPercent,
                onSelect = onSelectDefaultZoom,
                modifier = Modifier.testTag("settings_default_zoom"),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OvSecondaryButton(
                text = "Licences",
                glyph = TrapGlyph.Legal,
                onClick = {
                    onDismiss()
                    onOpenLegalNotices()
                },
                modifier = Modifier.weight(1f),
            )
            OvSecondaryButton(
                text = "Clear Session",
                glyph = TrapGlyph.Delete,
                accent = colors.danger,
                onClick = { confirmClearSession = true },
                modifier = Modifier
                    .weight(1f)
                    .testTag("settings_clear_session"),
            )
        }
    }

    if (confirmClearSession) {
        AlertDialog(
            onDismissRequest = { confirmClearSession = false },
            containerColor = colors.surface,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textMuted,
            title = {
                Text("Clear Ephemeral Session?", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Close all open tabs, purge in-memory browsing state and back-stack " +
                        "snapshots, and rotate Tor circuit isolation?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearSession = false
                        onClearSession()
                        onDismiss()
                    },
                ) {
                    Text("Clear Session", color = colors.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearSession = false }) {
                    Text("Cancel", color = colors.textMuted)
                }
            },
        )
    }
}

@Composable
private fun SettingSectionLabel(text: String) {
    val colors = TrapTheme.colors
    Text(
        text = text,
        color = colors.textPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null,
) {
    val colors = TrapTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface2)
            .border(1.dp, colors.cardBorder, shape)
            .clickable(role = Role.Switch, onClick = { onCheckedChange(!checked) })
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = colors.textMuted,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onTealFill,
                checkedTrackColor = colors.teal,
                uncheckedThumbColor = colors.textMuted,
                uncheckedTrackColor = colors.control,
                uncheckedBorderColor = colors.border,
            ),
        )
    }
}

/**
 * Browser Menu Sheet (`☰`).
 */
@Composable
fun BrowserMenuSheet(
    state: BrowserUiState,
    onNewTab: () -> Unit,
    onStartPage: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenKeyring: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenSettings: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onOpenLegal: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OvBottomSheet(
        title = "Trap Menu",
        onDismiss = onDismiss,
        modifier = modifier.testTag("browser_menu_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OvMenuRow(
                glyph = TrapGlyph.NewTab,
                label = "New Tab",
                onClick = {
                    onDismiss()
                    onNewTab()
                },
                testTag = "menu_new_tab",
            )
            OvMenuRow(
                glyph = TrapGlyph.Home,
                label = "Start Page",
                onClick = {
                    onDismiss()
                    onStartPage()
                },
                testTag = "menu_start_page",
            )
            OvMenuRow(
                glyph = TrapGlyph.Tabs,
                label = "Tabs",
                trailing = state.tabCount.toString(),
                onClick = {
                    onDismiss()
                    onOpenTabs()
                },
                testTag = "menu_tabs",
            )
            OvMenuRow(
                glyph = TrapGlyph.Key,
                label = "PGP Keyring",
                trailing = if (state.keys.isEmpty()) null else state.keys.size.toString(),
                onClick = {
                    onDismiss()
                    onOpenKeyring()
                },
                testTag = "menu_keyring",
            )
            OvMenuRow(
                glyph = TrapGlyph.Shield,
                label = "Tor Security & Circuit",
                trailing = if (state.hiddenServiceOnly) ".onion only" else "Tor proxy",
                onClick = {
                    onDismiss()
                    onOpenSecurity()
                },
                testTag = "menu_security",
            )
            OvMenuRow(
                glyph = TrapGlyph.ZoomIn,
                label = "Zoom In",
                trailing = "${state.zoomPercent}%",
                onClick = onZoomIn,
                testTag = "menu_zoom_in",
            )
            OvMenuRow(
                glyph = TrapGlyph.ZoomOut,
                label = "Zoom Out",
                onClick = onZoomOut,
                testTag = "menu_zoom_out",
            )
            OvMenuRow(
                glyph = TrapGlyph.Settings,
                label = "Settings",
                trailing = "${state.themeFamily.displayName} · ${state.themeMode.displayName}",
                onClick = {
                    onDismiss()
                    onOpenSettings()
                },
                testTag = "menu_settings",
            )
            OvMenuRow(
                glyph = TrapGlyph.Legal,
                label = "Open-Source Licences",
                onClick = {
                    onDismiss()
                    onOpenLegal()
                },
                testTag = "menu_legal",
            )
        }
    }
}

/**
 * Page / Link Context Menu Sheet surfaced from `CefriumBrowser.setOnContextMenuListener`.
 */
@Composable
fun ContextMenuSheet(
    menu: ContextMenuUiState,
    onOpenInNewTab: (String) -> Unit,
    onOpenInCurrentTab: (String) -> Unit,
    onCopyUrl: (String) -> Unit,
    onDecryptSelection: (String) -> Unit,
    onImportKeySelection: (String) -> Unit,
    onEncryptSelection: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TrapTheme.colors
    val targetUrl = menu.linkUrl?.takeIf { it.isNotBlank() }
        ?: menu.mediaUrl?.takeIf { it.isNotBlank() }
    val selected = menu.selectedText?.takeIf { it.isNotBlank() }

    OvBottomSheet(
        title = "Page Actions",
        onDismiss = onDismiss,
        modifier = Modifier.testTag("context_menu_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (targetUrl != null) {
                Text(
                    text = targetUrl,
                    color = colors.textMuted,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                OvMenuRow(
                    glyph = TrapGlyph.NewTab,
                    label = "Open Link in New Tab",
                    onClick = {
                        onDismiss()
                        onOpenInNewTab(targetUrl)
                    },
                )
                OvMenuRow(
                    glyph = TrapGlyph.Forward,
                    label = "Open Link in Current Tab",
                    onClick = {
                        onDismiss()
                        onOpenInCurrentTab(targetUrl)
                    },
                )
                OvMenuRow(
                    glyph = TrapGlyph.Copy,
                    label = "Copy Link Address",
                    onClick = {
                        onDismiss()
                        onCopyUrl(targetUrl)
                    },
                )
            }

            if (selected != null) {
                when (menu.classificationKind) {
                    "pgp_message" -> {
                        OvMenuRow(
                            glyph = TrapGlyph.Decrypt,
                            label = "Decrypt Selected PGP Message",
                            onClick = {
                                onDismiss()
                                onDecryptSelection(menu.armoredPayload.ifBlank { selected })
                            },
                        )
                    }

                    "pgp_public_key" -> {
                        OvMenuRow(
                            glyph = TrapGlyph.Import,
                            label = "Import Selected PGP Public Key",
                            onClick = {
                                onDismiss()
                                onImportKeySelection(menu.armoredPayload.ifBlank { selected })
                            },
                        )
                    }
                }
                OvMenuRow(
                    glyph = TrapGlyph.Encrypt,
                    label = "Encrypt Selected Text…",
                    onClick = {
                        onDismiss()
                        onEncryptSelection(selected)
                    },
                )
            }
        }
    }
}

/**
 * Text Selection PGP Action Sheet surfaced from `TabSessionManager`'s hardened
 * `setTextSelectionActionModeCallback` (which strips external `WEB_SEARCH` / `PROCESS_TEXT` leaks).
 */
@Composable
fun SelectionPgpSheet(
    selection: SelectionPgpUiState,
    onDecrypt: (String) -> Unit,
    onImportKey: (String) -> Unit,
    onEncrypt: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TrapTheme.colors

    OvBottomSheet(
        title = "Selected Text PGP Actions",
        onDismiss = onDismiss,
        modifier = Modifier.testTag("selection_pgp_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = selection.selectedText,
                color = colors.textMuted,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface2)
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(10.dp))
                    .padding(10.dp),
            )

            when (selection.kind) {
                "pgp_message" -> {
                    OvMenuRow(
                        glyph = TrapGlyph.Decrypt,
                        label = "Decrypt PGP Block",
                        onClick = {
                            onDismiss()
                            onDecrypt(selection.armored.ifBlank { selection.selectedText })
                        },
                    )
                }

                "pgp_public_key" -> {
                    OvMenuRow(
                        glyph = TrapGlyph.Import,
                        label = "Import PGP Public Key",
                        onClick = {
                            onDismiss()
                            onImportKey(selection.armored.ifBlank { selection.selectedText })
                        },
                    )
                }
            }

            OvMenuRow(
                glyph = TrapGlyph.Encrypt,
                label = "Encrypt Text with Keyring…",
                onClick = {
                    onDismiss()
                    onEncrypt(selection.selectedText)
                },
            )
            OvMenuRow(
                glyph = TrapGlyph.Copy,
                label = "Copy Selection",
                onClick = {
                    onDismiss()
                    onCopy(selection.selectedText)
                },
            )
        }
    }
}
