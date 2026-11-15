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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.onionvault.OvNative

/**
 * Compact interstitial consent bottom sheet presented before any clearnet site
 * connects over Tor. Supports `*` / `?` globbing, 3-day persisted allowlist
 * memory (`259,200` seconds), session-only allow, and immediate block.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearnetConsentSheet(
    prompt: ClearnetPromptState,
    onAllow: (prompt: ClearnetPromptState, pattern: String, ttlSeconds: Long) -> Unit,
    onBlock: (prompt: ClearnetPromptState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TrapTheme.colors
    val focusManager = LocalFocusManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val exactHost = remember(prompt.host) {
        prompt.host.trim().trimEnd('.').lowercase()
    }
    val domainGlob = remember(prompt.host, prompt.suggestedPattern) {
        prompt.suggestedPattern.trim().ifBlank {
            OvNative.suggestGlob(exactHost)
        }.ifBlank { exactHost }
    }
    var pattern by remember(prompt.host, prompt.pendingUrl, prompt.suggestedPattern) {
        mutableStateOf(domainGlob)
    }

    val cleanPattern = pattern.trim()
    val canAllow = cleanPattern.isNotEmpty()
    val isDomainSelected = cleanPattern.equals(domainGlob, ignoreCase = true) &&
        (domainGlob != exactHost || !cleanPattern.equals(exactHost, ignoreCase = true))
    val isExactSelected = cleanPattern.equals(exactHost, ignoreCase = true) && !isDomainSelected

    ModalBottomSheet(
        onDismissRequest = { onBlock(prompt) },
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
        scrimColor = Color.Black.copy(alpha = 0.56f),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp)
                .testTag("clearnet_consent_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "Allow clearnet site over Tor?",
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = exactHost.ifEmpty { prompt.host },
                    color = colors.amber,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("clearnet_prompt_host"),
                )
            }

            // Strictly 2 compact scope chips (<= 2:1 label density ceiling)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ClearnetScopeChip(
                    label = "*.domain",
                    selected = isDomainSelected,
                    onClick = { pattern = domainGlob },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("clearnet_scope_domain"),
                )
                ClearnetScopeChip(
                    label = "Exact host",
                    selected = isExactSelected,
                    onClick = { pattern = exactHost },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("clearnet_scope_exact"),
                )
            }

            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clearnet_pattern_input"),
                singleLine = true,
                textStyle = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 13.5.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                placeholder = {
                    Text(
                        text = "*.example.com",
                        color = colors.textMuted,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.teal,
                    unfocusedBorderColor = colors.border,
                    focusedContainerColor = colors.surface2,
                    unfocusedContainerColor = colors.surface2,
                    cursorColor = colors.teal,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                ),
            )

            // Primary full-width 44.dp Amber CTA: Allow for 3 days (259,200s)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (canAllow) colors.goFill else colors.control)
                    .clickable(
                        enabled = canAllow,
                        role = Role.Button,
                        onClick = {
                            focusManager.clearFocus()
                            onAllow(prompt, cleanPattern, OvNative.DEFAULT_CLEARNET_TTL_SECONDS)
                        },
                    )
                    .testTag("clearnet_allow_3d_button"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Allow for 3 days",
                    color = if (canAllow) colors.onGoFill else colors.textMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }

            // Secondary row: Allow once (session) & Block
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OvSecondaryButton(
                    text = "Allow once",
                    enabled = canAllow,
                    onClick = {
                        focusManager.clearFocus()
                        onAllow(prompt, cleanPattern, 0L)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .testTag("clearnet_allow_once_button"),
                )
                OvSecondaryButton(
                    text = "Block",
                    accent = colors.danger,
                    onClick = {
                        focusManager.clearFocus()
                        onBlock(prompt)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .testTag("clearnet_block_button"),
                )
            }
        }
    }
}

@Composable
private fun ClearnetScopeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TrapTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(shape)
            .background(if (selected) colors.control else colors.surface2)
            .border(
                width = 1.dp,
                color = if (selected) colors.teal else colors.cardBorder,
                shape = shape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) colors.textPrimary else colors.textMuted,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

internal fun formatClearnetRuleTtl(
    rule: OvNative.ClearnetRuleItem,
    nowMs: Long = System.currentTimeMillis(),
): String {
    if (rule.sessionOnly || rule.expiresAtMs <= 0L) {
        return "Session"
    }
    val remainingSecs = ((rule.expiresAtMs - nowMs).coerceAtLeast(0L)) / 1_000L
    return when {
        remainingSecs <= 0L -> "Expired"
        remainingSecs > 86_400L -> "${(remainingSecs + 86_399L) / 86_400L}d left"
        remainingSecs >= 3_600L -> "${(remainingSecs + 3_599L) / 3_600L}h left"
        else -> "${((remainingSecs + 59L) / 60L).coerceAtLeast(1L)}m left"
    }
}

@Composable
private fun ClearnetRulesSection(
    rules: List<OvNative.ClearnetRuleItem>,
    hiddenServiceOnly: Boolean,
    onRevokeRule: (String) -> Unit,
    onClearAllRules: () -> Unit,
) {
    val colors = TrapTheme.colors
    val cardShape = RoundedCornerShape(12.dp)
    val nowMs = System.currentTimeMillis()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingSectionLabel(
            if (rules.isEmpty()) "Clearnet Rules" else "Clearnet Rules (${rules.size})",
        )

        if (rules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(colors.surface2)
                    .border(1.dp, colors.cardBorder, cardShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = if (hiddenServiceOnly) {
                        "Clearnet disabled while Hidden Service Only is active."
                    } else {
                        "No active clearnet rules. Every clearnet site asks before connecting."
                    },
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }
        } else {
            rules.forEach { rule ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(colors.surface2)
                        .border(1.dp, colors.cardBorder, cardShape)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .testTag("clearnet_rule_row_${rule.pattern}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rule.pattern,
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatClearnetRuleTtl(rule, nowMs),
                            color = colors.teal,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OvSecondaryButton(
                        text = "Revoke",
                        accent = colors.danger,
                        onClick = { onRevokeRule(rule.pattern) },
                        modifier = Modifier.testTag("clearnet_revoke_${rule.pattern}"),
                    )
                }
            }

            OvSecondaryButton(
                text = "Clear all clearnet rules",
                glyph = TrapGlyph.Delete,
                accent = colors.danger,
                onClick = onClearAllRules,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clearnet_clear_all_button"),
            )
        }
    }
}

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
                .weight(1f, fill = false)
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { tab ->
                val shape = RoundedCornerShape(12.dp)
                val displayTitle = tab.title.ifBlank {
                    if (isBlankOrStartUrl(tab.url)) "Trap" else tab.url
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
 * Renders vertical key-value rows (`<= 2:1` label density), active clearnet rules
 * with remaining TTL + Revoke / Clear All, runtime `Hidden Service Only` toggle,
 * and `"New Identity / Reset Tor Isolation"`.
 */
@Composable
fun SecurityCircuitSheet(
    state: BrowserUiState,
    onToggleHiddenServiceOnly: (Boolean) -> Unit,
    onResetTorIsolation: () -> Unit,
    onRevokeClearnetRule: (String) -> Unit = {},
    onClearClearnetRules: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TrapTheme.colors
    val tor = state.tor
    val cardShape = RoundedCornerShape(12.dp)

    OvBottomSheet(
        title = "Tor & Clearnet Rules",
        onDismiss = onDismiss,
        modifier = modifier.testTag("security_circuit_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 480.dp)
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

            ClearnetRulesSection(
                rules = state.clearnetRules,
                hiddenServiceOnly = state.hiddenServiceOnly,
                onRevokeRule = onRevokeClearnetRule,
                onClearAllRules = onClearClearnetRules,
            )

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
    accent: Color,
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
 * Persisted Settings Sheet (`TrapPreferences` + `OvNative` clearnet rules).
 */
@Composable
fun SettingsSheet(
    state: BrowserUiState,
    onSelectThemeFamily: (TrapThemeFamily) -> Unit,
    onSelectThemeMode: (TrapThemeMode) -> Unit,
    onToggleHiddenServiceOnly: (Boolean) -> Unit,
    onToggleRequireBiometric: (Boolean) -> Unit,
    onSelectDefaultZoom: (Int) -> Unit,
    onRevokeClearnetRule: (String) -> Unit = {},
    onClearClearnetRules: () -> Unit = {},
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
                .weight(1f, fill = false)
                .heightIn(max = 560.dp)
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

            ClearnetRulesSection(
                rules = state.clearnetRules,
                hiddenServiceOnly = state.hiddenServiceOnly,
                onRevokeRule = onRevokeClearnetRule,
                onClearAllRules = onClearClearnetRules,
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

            Spacer(Modifier.height(4.dp))

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
 * Provides crisp 1-tap access to Forward, Reload, New Tab, Tabs, PGP Keyring,
 * Tor & Clearnet Rules, Zoom, Settings, and Open-Source Licences.
 */
@Composable
fun BrowserMenuSheet(
    state: BrowserUiState,
    onForward: () -> Unit,
    onReload: () -> Unit,
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
    val securitySummary = when {
        state.hiddenServiceOnly -> ".onion only"
        state.clearnetRules.isNotEmpty() -> "${state.clearnetRules.size} allowed"
        else -> "Ask"
    }

    OvBottomSheet(
        title = "Menu",
        onDismiss = onDismiss,
        modifier = modifier.testTag("browser_menu_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OvMenuRow(
                glyph = TrapGlyph.Forward,
                label = "Forward",
                enabled = state.canGoForward,
                onClick = {
                    onDismiss()
                    onForward()
                },
                testTag = "menu_forward",
            )
            OvMenuRow(
                glyph = TrapGlyph.Reload,
                label = if (state.isLoading) "Stop Loading" else "Reload",
                onClick = {
                    onDismiss()
                    onReload()
                },
                testTag = "menu_reload",
            )
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
                glyph = TrapGlyph.Home,
                label = "Start Page",
                onClick = {
                    onDismiss()
                    onStartPage()
                },
                testTag = "menu_start_page",
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
                label = "Tor & Clearnet Rules",
                trailing = securitySummary,
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
                trailing = state.themeFamily.displayName,
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
