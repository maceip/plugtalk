package dev.onionvault.ui.pods

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.onionvault.OvNative
import dev.onionvault.ui.BrowserUiState
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.TrapGlyph
import dev.onionvault.ui.TrapIcon
import dev.onionvault.ui.formatClearnetRuleTtl

/**
 * Operational modes of the detached right-edge [PhotoSoapSideRemote].
 */
enum class RemoteMode {
    Menu,
    TorCircuit,
    ContextMenu,
    PgpActions,
}

/**
 * Truthful 4-channel hardware LED status vector derived strictly from live [BrowserUiState].
 * Zero fabricated lights or synthetic activity pulses.
 */
@Immutable
data class RemoteTelemetryLeds(
    val torGreen: Boolean,
    val torAmber: Boolean,
    val routingGreen: Boolean,
    val routingAmber: Boolean,
    val pgpGreen: Boolean,
    val pgpAmber: Boolean,
    val zeroJsGreen: Boolean,
    val zeroJsAmber: Boolean,
    val stencilCode: String,
)

fun deriveRemoteTelemetryLeds(
    state: BrowserUiState,
    hasPgpContent: Boolean,
): RemoteTelemetryLeds {
    val isOnion = isOnionHostUrl(state.url) || !state.tor.isolationToken.isNullOrBlank()
    val hasClearnetActivity = state.clearnetPrompt != null ||
        state.blocked ||
        (!isOnion && !state.isStartPage && state.clearnetRules.isNotEmpty())

    val keySourceFp = state.defaultKeyFingerprint.ifBlank {
        state.keys.firstOrNull { it.hasSecret }?.fingerprint.orEmpty()
    }.replace(" ", "").uppercase()

    val stencil = if (keySourceFp.length >= 4) {
        "ZK\n${keySourceFp.takeLast(4)}"
    } else {
        "ZK\nTOR"
    }

    return RemoteTelemetryLeds(
        torGreen = state.tor.ready,
        torAmber = !state.tor.ready && (state.tor.percent > 0 || state.tor.stage.isNotBlank()),
        routingGreen = isOnion || state.hiddenServiceOnly,
        routingAmber = hasClearnetActivity,
        pgpGreen = state.keys.isNotEmpty(),
        pgpAmber = hasPgpContent || !state.pgp.error.isNullOrBlank(),
        zeroJsGreen = !state.isScreenRecordingActive && !state.viewingSnapshot,
        zeroJsAmber = state.isScreenRecordingActive || state.viewingSnapshot,
        stencilCode = stencil,
    )
}

/**
 * Detached Anodized Pill Remote floating on right thumb edge above the OpenPGP switch (`side_remote_pod`).
 *
 * - **Collapsed (`48.dp` wide)**: Vertical `7.dp` recessed glass LED beads (`#00FF66` green glow /
 *   `#FFAA00` amber glow / `#121418` unlit — backed by REAL Tor bootstrap, `.onion` isolation vs
 *   clearnet rule, Keyring/PGP status, and Zero-JS lock) + tiny torn monochrome sticker detail +
 *   distressed `#B8C0C8` stencil micro-type (`ZK / TOR` or real 4-hex default key short-hash).
 * - **Expanded (`140ms` linear slide to `218.dp`, zero bounce)**: `2.dp` engraved grooves
 *   (`1.dp #0A0B0D` over `1.dp #343942`), inverted bevel + `1.dp` down-right shift + lit LED on
 *   press! Supports [RemoteMode.Menu], [RemoteMode.TorCircuit] (with 2-tap confirm on
 *   `New Tor Identity`), [RemoteMode.ContextMenu], and [RemoteMode.PgpActions].
 */
@Composable
fun PhotoSoapSideRemote(
    state: BrowserUiState,
    expanded: Boolean,
    mode: RemoteMode,
    hasPgpContent: Boolean,
    onExpandMenu: () -> Unit,
    onSelectMode: (RemoteMode) -> Unit,
    onCollapse: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onNewTab: () -> Unit,
    onOpenTabs: () -> Unit,
    onCopyUrl: () -> Unit,
    onHome: () -> Unit,
    onOpenKeyring: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLicences: () -> Unit,
    onToggleHiddenServiceOnly: (Boolean) -> Unit,
    onResetTorIsolation: () -> Unit,
    onRevokeClearnetRule: (String) -> Unit,
    onClearClearnetRules: () -> Unit,
    onFieldAction: (PgpFieldState) -> Unit,
    onInsertMyPubKey: (PgpFieldState) -> Unit,
    onContextMenuOpenNewTab: (String) -> Unit,
    onContextMenuOpenCurrentTab: (String) -> Unit,
    onCopyText: (String) -> Unit,
    onDecryptArmoredText: (String) -> Unit,
    onImportArmoredKey: (String) -> Unit,
    onEncryptSelectedText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val grainBrush = rememberPewterGrainBrush()
    val leds = remember(state, hasPgpContent) {
        deriveRemoteTelemetryLeds(state = state, hasPgpContent = hasPgpContent)
    }

    BackHandler(enabled = expanded) {
        onCollapse()
    }

    val animatedWidth by animateDpAsState(
        targetValue = if (expanded) 218.dp else 48.dp,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.REMOTE_SLIDE_DURATION_MS,
            easing = LinearEasing,
        ),
        label = "side_remote_width",
    )

    val pillRadius = if (expanded) 22.dp else 24.dp
    val pillShape = RoundedCornerShape(pillRadius)

    Box(
        modifier = modifier
            .width(animatedWidth)
            .metaToolsDropShadow(cornerRadius = pillRadius)
            .clip(pillShape)
            .drawBehind {
                drawAnodizedRemoteChassis(
                    grainBrush = grainBrush,
                    cornerRadiusPx = pillRadius.toPx(),
                    isCollapsed = !expanded,
                )
            }
            .testTag("side_remote_pod"),
    ) {
        if (!expanded) {
            CollapsedRemoteFace(
                leds = leds,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onExpandMenu()
                },
            )
        } else {
            ExpandedRemoteFace(
                state = state,
                mode = mode,
                leds = leds,
                onSelectMode = onSelectMode,
                onCollapse = onCollapse,
                onBack = onBack,
                onForward = onForward,
                onReload = onReload,
                onNewTab = onNewTab,
                onOpenTabs = onOpenTabs,
                onCopyUrl = onCopyUrl,
                onHome = onHome,
                onOpenKeyring = onOpenKeyring,
                onOpenSettings = onOpenSettings,
                onOpenLicences = onOpenLicences,
                onToggleHiddenServiceOnly = onToggleHiddenServiceOnly,
                onResetTorIsolation = onResetTorIsolation,
                onRevokeClearnetRule = onRevokeClearnetRule,
                onClearClearnetRules = onClearClearnetRules,
                onFieldAction = onFieldAction,
                onInsertMyPubKey = onInsertMyPubKey,
                onContextMenuOpenNewTab = onContextMenuOpenNewTab,
                onContextMenuOpenCurrentTab = onContextMenuOpenCurrentTab,
                onCopyText = onCopyText,
                onDecryptArmoredText = onDecryptArmoredText,
                onImportArmoredKey = onImportArmoredKey,
                onEncryptSelectedText = onEncryptSelectedText,
            )
        }
    }
}

@Composable
private fun CollapsedRemoteFace(
    leds: RemoteTelemetryLeds,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(48.dp)
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "Open hardware remote menu"
            }
            .padding(vertical = 11.dp)
            .testTag("side_remote_collapsed_face"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 4 Paired 7.dp Recessed Glass LED Channels (Green OK left | Amber Warn/Active right)
        Canvas(
            modifier = Modifier
                .width(34.dp)
                .height(66.dp),
        ) {
            val beadDiameterPx = 7.dp.toPx()
            val leftColX = size.width * 0.28f
            val rightColX = size.width * 0.72f
            val rowSpacing = size.height / 4f
            val pairs = listOf(
                leds.torGreen to leds.torAmber,
                leds.routingGreen to leds.routingAmber,
                leds.pgpGreen to leds.pgpAmber,
                leds.zeroJsGreen to leds.zeroJsAmber,
            )
            pairs.forEachIndexed { index, (greenOn, amberOn) ->
                val y = rowSpacing * (index + 0.5f)
                drawRecessedGlassLedBead(
                    center = Offset(leftColX, y),
                    beadDiameterPx = beadDiameterPx,
                    litColor = if (greenOn) MetaToolsTokens.LedGreen else null,
                )
                drawRecessedGlassLedBead(
                    center = Offset(rightColX, y),
                    beadDiameterPx = beadDiameterPx,
                    litColor = if (amberOn) MetaToolsTokens.LedAmber else null,
                )
            }
        }

        // Tiny torn monochrome zine sticker detail across middle edge (matching metatools-reference.jpg)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp),
        ) {
            drawTornMonochromeSticker()
        }

        Spacer(Modifier.height(3.dp))

        // Distressed #B8C0C8 Stencil Micro-Type (ZK / TOR or real 4-hex default key short-hash)
        Text(
            text = leds.stencilCode,
            color = MetaToolsTokens.StencilInk,
            fontSize = 10.5.sp,
            lineHeight = 11.5.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("side_remote_stencil"),
        )
    }
}

@Composable
private fun ExpandedRemoteFace(
    state: BrowserUiState,
    mode: RemoteMode,
    leds: RemoteTelemetryLeds,
    onSelectMode: (RemoteMode) -> Unit,
    onCollapse: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onNewTab: () -> Unit,
    onOpenTabs: () -> Unit,
    onCopyUrl: () -> Unit,
    onHome: () -> Unit,
    onOpenKeyring: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLicences: () -> Unit,
    onToggleHiddenServiceOnly: (Boolean) -> Unit,
    onResetTorIsolation: () -> Unit,
    onRevokeClearnetRule: (String) -> Unit,
    onClearClearnetRules: () -> Unit,
    onFieldAction: (PgpFieldState) -> Unit,
    onInsertMyPubKey: (PgpFieldState) -> Unit,
    onContextMenuOpenNewTab: (String) -> Unit,
    onContextMenuOpenCurrentTab: (String) -> Unit,
    onCopyText: (String) -> Unit,
    onDecryptArmoredText: (String) -> Unit,
    onImportArmoredKey: (String) -> Unit,
    onEncryptSelectedText: (String) -> Unit,
) {
    val headerLabel = when (mode) {
        RemoteMode.Menu -> "REMOTE"
        RemoteMode.TorCircuit -> "TOR CIRCUIT"
        RemoteMode.ContextMenu -> "LINK / TEXT"
        RemoteMode.PgpActions -> "OPENPGP"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 468.dp)
            .padding(top = 10.dp, bottom = 8.dp),
    ) {
        // Engraved Hardware Header Strip (1 primary label + 1 close button -> <= 2:1 density)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(modifier = Modifier.size(10.dp)) {
                drawRecessedGlassLedBead(
                    center = center,
                    beadDiameterPx = 7.dp.toPx(),
                    litColor = if (leds.torGreen) MetaToolsTokens.LedGreen else MetaToolsTokens.LedAmber,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = headerLabel,
                color = MetaToolsTokens.StencilInk,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (mode != RemoteMode.Menu) {
                RemoteMiniKey(
                    label = "MENU",
                    onClick = { onSelectMode(RemoteMode.Menu) },
                    testTag = "remote_switch_to_menu",
                )
                Spacer(Modifier.width(6.dp))
            }
            RemoteMiniKey(
                label = "✕",
                onClick = onCollapse,
                testTag = "remote_close_button",
            )
        }

        EngravedGrooveDivider(modifier = Modifier.padding(top = 6.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            when (mode) {
                RemoteMode.Menu -> RemoteMenuBody(
                    state = state,
                    onCollapse = onCollapse,
                    onBack = onBack,
                    onForward = onForward,
                    onReload = onReload,
                    onNewTab = onNewTab,
                    onOpenTabs = onOpenTabs,
                    onCopyUrl = onCopyUrl,
                    onHome = onHome,
                    onOpenKeyring = onOpenKeyring,
                    onOpenTorCircuit = { onSelectMode(RemoteMode.TorCircuit) },
                    onOpenSettings = onOpenSettings,
                    onOpenLicences = onOpenLicences,
                )

                RemoteMode.TorCircuit -> RemoteTorCircuitBody(
                    state = state,
                    onToggleHiddenServiceOnly = onToggleHiddenServiceOnly,
                    onResetTorIsolation = onResetTorIsolation,
                    onRevokeClearnetRule = onRevokeClearnetRule,
                    onClearClearnetRules = onClearClearnetRules,
                )

                RemoteMode.ContextMenu -> RemoteContextMenuBody(
                    state = state,
                    onCollapse = onCollapse,
                    onOpenNewTab = onContextMenuOpenNewTab,
                    onOpenCurrentTab = onContextMenuOpenCurrentTab,
                    onCopyText = onCopyText,
                    onDecryptArmoredText = onDecryptArmoredText,
                    onImportArmoredKey = onImportArmoredKey,
                    onEncryptSelectedText = onEncryptSelectedText,
                )

                RemoteMode.PgpActions -> RemotePgpActionsBody(
                    state = state,
                    onCollapse = onCollapse,
                    onFieldAction = onFieldAction,
                    onInsertMyPubKey = onInsertMyPubKey,
                    onDecryptArmoredText = onDecryptArmoredText,
                    onImportArmoredKey = onImportArmoredKey,
                    onEncryptSelectedText = onEncryptSelectedText,
                    onCopyText = onCopyText,
                    onOpenKeyring = onOpenKeyring,
                )
            }
        }
    }
}

@Composable
private fun RemoteMenuBody(
    state: BrowserUiState,
    onCollapse: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onNewTab: () -> Unit,
    onOpenTabs: () -> Unit,
    onCopyUrl: () -> Unit,
    onHome: () -> Unit,
    onOpenKeyring: () -> Unit,
    onOpenTorCircuit: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLicences: () -> Unit,
) {
    RemoteEngravedRowButton(
        label = "Back",
        glyph = TrapGlyph.Back,
        enabled = state.canGoBack,
        onClick = {
            onCollapse()
            onBack()
        },
        testTag = "remote_back",
    )
    RemoteEngravedRowButton(
        label = "Forward",
        glyph = TrapGlyph.Forward,
        enabled = state.canGoForward,
        onClick = {
            onCollapse()
            onForward()
        },
        testTag = "remote_forward",
    )
    RemoteEngravedRowButton(
        label = if (state.isLoading) "Stop" else "Reload",
        glyph = TrapGlyph.Reload,
        onClick = {
            onCollapse()
            onReload()
        },
        testTag = "remote_reload",
    )
    RemoteEngravedRowButton(
        label = "New Tab",
        glyph = TrapGlyph.NewTab,
        onClick = {
            onCollapse()
            onNewTab()
        },
        testTag = "remote_new_tab",
    )
    RemoteEngravedRowButton(
        label = "Tabs (${state.tabCount})",
        glyph = TrapGlyph.Tabs,
        ledIdleColor = MetaToolsTokens.LedGreen,
        onClick = {
            onCollapse()
            onOpenTabs()
        },
        testTag = "remote_tabs",
    )
    RemoteEngravedRowButton(
        label = "Copy URL",
        glyph = TrapGlyph.Copy,
        enabled = !state.isStartPage,
        onClick = {
            onCollapse()
            onCopyUrl()
        },
        testTag = "remote_copy_url",
    )
    RemoteEngravedRowButton(
        label = "Home",
        glyph = TrapGlyph.Home,
        onClick = {
            onCollapse()
            onHome()
        },
        testTag = "remote_home",
    )
    RemoteEngravedRowButton(
        label = "Tor Circuit",
        glyph = TrapGlyph.Shield,
        trailingToken = if (state.tor.ready) "READY" else "${state.tor.percent}%",
        ledIdleColor = if (state.tor.ready) MetaToolsTokens.LedGreen else MetaToolsTokens.LedAmber,
        onClick = onOpenTorCircuit,
        testTag = "remote_tor_mode_button",
    )
    RemoteEngravedRowButton(
        label = "Keyring",
        glyph = TrapGlyph.Key,
        trailingToken = state.keys.size.takeIf { it > 0 }?.toString(),
        onClick = {
            onCollapse()
            onOpenKeyring()
        },
        testTag = "remote_keyring",
    )
    RemoteEngravedRowButton(
        label = "Settings",
        glyph = TrapGlyph.Settings,
        onClick = {
            onCollapse()
            onOpenSettings()
        },
        testTag = "remote_settings",
    )
    RemoteEngravedRowButton(
        label = "Licences",
        glyph = TrapGlyph.Legal,
        showBottomGroove = false,
        onClick = {
            onCollapse()
            onOpenLicences()
        },
        testTag = "remote_licences",
    )
}

@Composable
private fun RemoteTorCircuitBody(
    state: BrowserUiState,
    onToggleHiddenServiceOnly: (Boolean) -> Unit,
    onResetTorIsolation: () -> Unit,
    onRevokeClearnetRule: (String) -> Unit,
    onClearClearnetRules: () -> Unit,
) {
    var confirmIdentityReset by remember { mutableStateOf(false) }
    val tor = state.tor
    val nowMs = System.currentTimeMillis()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_tor_circuit"),
    ) {
        // Real Arti Bootstrap & Loopback SOCKS5 Telemetry Well
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MetaToolsTokens.CrtBlackGlass)
                .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            RemoteCrtStatLine(
                key = "ARTI",
                value = if (tor.ready) {
                    "100% ${tor.stage.ifBlank { "READY" }}"
                } else {
                    "${tor.percent}% ${tor.stage.ifBlank { "BOOT" }}"
                },
                valueColor = if (tor.ready) {
                    MetaToolsTokens.PhosphorOnionGreen
                } else {
                    MetaToolsTokens.PhosphorLoadingAmber
                },
            )
            RemoteCrtStatLine(
                key = "SOCKS5",
                value = tor.socksAddr.ifBlank { "127.0.0.1:1" },
                valueColor = MetaToolsTokens.StencilInk,
            )
            RemoteCrtStatLine(
                key = "ISO",
                value = tor.isolationToken?.takeIf { it.isNotBlank() } ?: "none",
                valueColor = if (!tor.isolationToken.isNullOrBlank()) {
                    MetaToolsTokens.PhosphorClearnetCyan
                } else {
                    MetaToolsTokens.PhosphorPathDim
                },
            )
        }

        EngravedGrooveDivider()

        // 2-Tap Confirm New Tor Identity Button
        RemoteEngravedRowButton(
            label = if (confirmIdentityReset) "Confirm New ID?" else "New Tor Identity",
            glyph = TrapGlyph.RefreshIdentity,
            ledIdleColor = if (confirmIdentityReset) MetaToolsTokens.LedAmber else null,
            onClick = {
                if (!confirmIdentityReset) {
                    confirmIdentityReset = true
                } else {
                    confirmIdentityReset = false
                    onResetTorIsolation()
                }
            },
            testTag = "remote_new_identity",
        )

        // .onion-only Toggle
        RemoteEngravedRowButton(
            label = ".onion-Only Lock",
            glyph = TrapGlyph.Onion,
            trailingToken = if (state.hiddenServiceOnly) "ON" else "OFF",
            ledIdleColor = if (state.hiddenServiceOnly) MetaToolsTokens.LedGreen else null,
            onClick = { onToggleHiddenServiceOnly(!state.hiddenServiceOnly) },
            testTag = "remote_onion_only_toggle",
        )

        // Active 3-Day Clearnet Rules List
        if (state.clearnetRules.isNotEmpty()) {
            Text(
                text = "CLEARNET RULES (${state.clearnetRules.size})",
                color = MetaToolsTokens.StencilInk,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            state.clearnetRules.forEach { rule: OvNative.ClearnetRuleItem ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MetaToolsTokens.AnodizedRecessedWell)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rule.pattern,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatClearnetRuleTtl(rule, nowMs),
                            color = MetaToolsTokens.PhosphorClearnetGreen,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    RemoteMiniKey(
                        label = "✕",
                        onClick = { onRevokeClearnetRule(rule.pattern) },
                        testTag = "clearnet_revoke_${rule.pattern}",
                    )
                }
            }
            RemoteEngravedRowButton(
                label = "Clear All Rules",
                glyph = TrapGlyph.Delete,
                showBottomGroove = false,
                onClick = onClearClearnetRules,
                testTag = "clearnet_clear_all_button",
            )
        }
    }
}

@Composable
private fun RemoteContextMenuBody(
    state: BrowserUiState,
    onCollapse: () -> Unit,
    onOpenNewTab: (String) -> Unit,
    onOpenCurrentTab: (String) -> Unit,
    onCopyText: (String) -> Unit,
    onDecryptArmoredText: (String) -> Unit,
    onImportArmoredKey: (String) -> Unit,
    onEncryptSelectedText: (String) -> Unit,
) {
    val menu = state.contextMenu
    val targetUrl = menu?.linkUrl?.takeIf { it.isNotBlank() }
        ?: menu?.mediaUrl?.takeIf { it.isNotBlank() }
    val selected = menu?.selectedText?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_context_menu"),
    ) {
        val preview = targetUrl ?: selected
        if (preview != null) {
            Text(
                text = preview,
                color = MetaToolsTokens.PhosphorClearnetGreen,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MetaToolsTokens.CrtBlackGlass)
                    .padding(8.dp),
            )
            EngravedGrooveDivider()
        }

        if (targetUrl != null) {
            RemoteEngravedRowButton(
                label = "Open in New Tab",
                glyph = TrapGlyph.NewTab,
                onClick = {
                    onCollapse()
                    onOpenNewTab(targetUrl)
                },
                testTag = "remote_ctx_open_new_tab",
            )
            RemoteEngravedRowButton(
                label = "Open Here",
                glyph = TrapGlyph.Forward,
                onClick = {
                    onCollapse()
                    onOpenCurrentTab(targetUrl)
                },
                testTag = "remote_ctx_open_current",
            )
            RemoteEngravedRowButton(
                label = "Copy Link",
                glyph = TrapGlyph.Copy,
                showBottomGroove = selected != null,
                onClick = {
                    onCollapse()
                    onCopyText(targetUrl)
                },
                testTag = "remote_ctx_copy_link",
            )
        }

        if (selected != null) {
            when (menu.classificationKind) {
                "pgp_message" -> {
                    RemoteEngravedRowButton(
                        label = "Decrypt PGP",
                        glyph = TrapGlyph.Decrypt,
                        ledIdleColor = MetaToolsTokens.LedGreen,
                        onClick = {
                            onCollapse()
                            onDecryptArmoredText(menu.armoredPayload.ifBlank { selected })
                        },
                        testTag = "remote_ctx_decrypt",
                    )
                }

                "pgp_public_key" -> {
                    RemoteEngravedRowButton(
                        label = "Import Key",
                        glyph = TrapGlyph.Import,
                        ledIdleColor = MetaToolsTokens.LedGreen,
                        onClick = {
                            onCollapse()
                            onImportArmoredKey(menu.armoredPayload.ifBlank { selected })
                        },
                        testTag = "remote_ctx_import_key",
                    )
                }
            }
            RemoteEngravedRowButton(
                label = "Encrypt Text",
                glyph = TrapGlyph.Encrypt,
                onClick = {
                    onCollapse()
                    onEncryptSelectedText(selected)
                },
                testTag = "remote_ctx_encrypt",
            )
            RemoteEngravedRowButton(
                label = "Copy Selection",
                glyph = TrapGlyph.Copy,
                showBottomGroove = false,
                onClick = {
                    onCollapse()
                    onCopyText(selected)
                },
                testTag = "remote_ctx_copy_selection",
            )
        }
    }
}

@Composable
private fun RemotePgpActionsBody(
    state: BrowserUiState,
    onCollapse: () -> Unit,
    onFieldAction: (PgpFieldState) -> Unit,
    onInsertMyPubKey: (PgpFieldState) -> Unit,
    onDecryptArmoredText: (String) -> Unit,
    onImportArmoredKey: (String) -> Unit,
    onEncryptSelectedText: (String) -> Unit,
    onCopyText: (String) -> Unit,
    onOpenKeyring: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_pgp_actions"),
    ) {
        val sel = state.selectionPgp
        if (sel != null) {
            when (sel.kind) {
                "pgp_message" -> RemoteEngravedRowButton(
                    label = "Decrypt Selection",
                    glyph = TrapGlyph.Decrypt,
                    ledIdleColor = MetaToolsTokens.LedGreen,
                    onClick = {
                        onCollapse()
                        onDecryptArmoredText(sel.armored.ifBlank { sel.selectedText })
                    },
                    testTag = "remote_pgp_decrypt_selection",
                )

                "pgp_public_key" -> RemoteEngravedRowButton(
                    label = "Import PubKey",
                    glyph = TrapGlyph.Import,
                    ledIdleColor = MetaToolsTokens.LedGreen,
                    onClick = {
                        onCollapse()
                        onImportArmoredKey(sel.armored.ifBlank { sel.selectedText })
                    },
                    testTag = "remote_pgp_import_selection",
                )
            }
            RemoteEngravedRowButton(
                label = "Encrypt Selection",
                glyph = TrapGlyph.Encrypt,
                onClick = {
                    onCollapse()
                    onEncryptSelectedText(sel.selectedText)
                },
                testTag = "remote_pgp_encrypt_selection",
            )
            RemoteEngravedRowButton(
                label = "Copy Selection",
                glyph = TrapGlyph.Copy,
                onClick = {
                    onCollapse()
                    onCopyText(sel.selectedText)
                },
                testTag = "remote_pgp_copy_selection",
            )
        }

        state.pgp.fields.forEach { field ->
            when (field.kind) {
                "pgp_message" -> RemoteEngravedRowButton(
                    label = "Decrypt Block",
                    glyph = TrapGlyph.Decrypt,
                    ledIdleColor = MetaToolsTokens.LedGreen,
                    onClick = {
                        onCollapse()
                        onFieldAction(field)
                    },
                    testTag = "remote_pgp_field_${field.nodeId}",
                )

                "pgp_public_key" -> {
                    val isEditable = field.label.equals("Insert My PubKey", ignoreCase = true)
                    RemoteEngravedRowButton(
                        label = if (isEditable) "Insert My PubKey" else "Import Key",
                        glyph = if (isEditable) TrapGlyph.Key else TrapGlyph.Import,
                        ledIdleColor = MetaToolsTokens.LedGreen,
                        onClick = {
                            onCollapse()
                            if (isEditable) onInsertMyPubKey(field) else onFieldAction(field)
                        },
                        testTag = "remote_pgp_field_${field.nodeId}",
                    )
                }

                "pgp_textarea", "textarea", "text_input", "editable" -> {
                    RemoteEngravedRowButton(
                        label = "Encrypt Field",
                        glyph = TrapGlyph.Encrypt,
                        ledIdleColor = MetaToolsTokens.LedAmber,
                        onClick = {
                            onCollapse()
                            onFieldAction(field)
                        },
                        testTag = "remote_pgp_encrypt_${field.nodeId}",
                    )
                    RemoteEngravedRowButton(
                        label = "Insert My PubKey",
                        glyph = TrapGlyph.Key,
                        onClick = {
                            onCollapse()
                            onInsertMyPubKey(field)
                        },
                        testTag = "remote_pgp_pubkey_${field.nodeId}",
                    )
                }
            }
        }

        RemoteEngravedRowButton(
            label = "Keyring (${state.keys.size})",
            glyph = TrapGlyph.Key,
            showBottomGroove = false,
            onClick = {
                onCollapse()
                onOpenKeyring()
            },
            testTag = "remote_pgp_open_keyring",
        )
    }
}

/**
 * Physical hardware button row inside expanded [PhotoSoapSideRemote]:
 * - `2.dp` engraved bottom groove (`1.dp #0A0B0D` over `1.dp #343942`)
 * - On press: inverted bevel + `1.dp` down-right shift + lit `6.dp` recessed green LED!
 * - Strict `<= 2:1` label density (1 primary label + at most 1 trailing token).
 */
@Composable
private fun RemoteEngravedRowButton(
    label: String,
    glyph: TrapGlyph,
    onClick: () -> Unit,
    testTag: String,
    enabled: Boolean = true,
    trailingToken: String? = null,
    ledIdleColor: Color? = null,
    showBottomGroove: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressShift = if (isPressed && enabled) 1.dp else 0.dp
    val activeLedColor = when {
        !enabled -> null
        isPressed -> MetaToolsTokens.LedGreen
        else -> ledIdleColor
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(horizontal = 6.dp, vertical = 1.5.dp)
                .drawBehind {
                    if (isPressed && enabled) {
                        // Inverted sunken bevel on physical press
                        val r = 7.dp.toPx()
                        drawRoundRect(
                            color = MetaToolsTokens.AnodizedRecessedWell,
                            cornerRadius = CornerRadius(r, r),
                        )
                        drawMetallicSquircleBevel(
                            cornerRadiusPx = r,
                            strokeWidthPx = 1.dp.toPx(),
                            highlightColor = MetaToolsTokens.AnodizedBevelDark,
                            shadowColor = MetaToolsTokens.AnodizedBevelLight,
                        )
                    }
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    },
                )
                .padding(horizontal = 8.dp)
                .offset(x = pressShift, y = pressShift)
                .testTag(testTag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(modifier = Modifier.size(9.dp)) {
                drawRecessedGlassLedBead(
                    center = center,
                    beadDiameterPx = 6.dp.toPx(),
                    litColor = activeLedColor,
                )
            }
            Spacer(Modifier.width(8.dp))
            TrapIcon(
                glyph = glyph,
                tint = if (enabled) MetaToolsTokens.StencilInk else MetaToolsTokens.PhosphorPathDim,
                size = 15.dp,
                enabled = enabled,
                forceVector = true,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = if (enabled) Color(0xFFECEFF4) else MetaToolsTokens.PhosphorPathDim,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!trailingToken.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = trailingToken,
                    color = MetaToolsTokens.PhosphorClearnetGreen,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }

        if (showBottomGroove) {
            EngravedGrooveDivider()
        }
    }
}

@Composable
private fun RemoteMiniKey(
    label: String,
    onClick: () -> Unit,
    testTag: String,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .height(24.dp)
            .clip(shape)
            .background(MetaToolsTokens.AnodizedRecessedWell)
            .border(1.dp, MetaToolsTokens.AnodizedBevelLight, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 7.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MetaToolsTokens.StencilInk,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@Composable
private fun RemoteCrtStatLine(
    key: String,
    value: String,
    valueColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = key,
            color = MetaToolsTokens.PhosphorPathDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun EngravedGrooveDivider(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .padding(horizontal = 8.dp),
    ) {
        drawEngravedGroove(yPx = 0f, startXPx = 0f, endXPx = size.width)
    }
}

private fun DrawScope.drawAnodizedRemoteChassis(
    grainBrush: androidx.compose.ui.graphics.ShaderBrush,
    cornerRadiusPx: Float,
    isCollapsed: Boolean,
) {
    val corner = CornerRadius(cornerRadiusPx, cornerRadiusPx)

    // Cylindrical dark-anodized aluminum body
    drawRoundRect(
        brush = Brush.horizontalGradient(
            0.0f to MetaToolsTokens.AnodizedSurfaceTop,
            0.28f to Color(0xFF313742),
            0.68f to MetaToolsTokens.AnodizedSurface,
            1.0f to MetaToolsTokens.AnodizedSurfaceDeep,
        ),
        cornerRadius = corner,
    )
    drawRoundRect(
        brush = grainBrush,
        cornerRadius = corner,
    )

    // Crisp outer anodized metallic bevel
    drawMetallicSquircleBevel(
        cornerRadiusPx = cornerRadiusPx,
        strokeWidthPx = 1.5.dp.toPx(),
        highlightColor = MetaToolsTokens.AnodizedBevelLight,
        shadowColor = MetaToolsTokens.AnodizedBevelDark,
    )

    // Subtle weathered edge micro-scuffs on collapsed pill matching metatools-reference.jpg
    if (isCollapsed) {
        val scuffColor = Color(0x66C0C8D2)
        drawLine(
            color = scuffColor,
            start = Offset(size.width - 2.2.dp.toPx(), size.height * 0.24f),
            end = Offset(size.width - 1.2.dp.toPx(), size.height * 0.27f),
            strokeWidth = 0.9.dp.toPx(),
        )
        drawLine(
            color = scuffColor,
            start = Offset(size.width - 2.5.dp.toPx(), size.height * 0.36f),
            end = Offset(size.width - 1.4.dp.toPx(), size.height * 0.38f),
            strokeWidth = 0.8.dp.toPx(),
        )
    }
}

/**
 * Renders the tiny torn monochrome wheatpaste/zine sticker across the middle left flank
 * of the collapsed anodized pill remote (matching `metatools-reference.jpg`).
 */
private fun DrawScope.drawTornMonochromeSticker() {
    val h = size.height
    val w = size.width
    val stickerW = w * 0.66f

    val tornScrap = Path().apply {
        moveTo(1.5.dp.toPx(), h * 0.14f)
        lineTo(stickerW * 0.88f, h * 0.22f)
        lineTo(stickerW, h * 0.48f)
        lineTo(stickerW * 0.91f, h * 0.68f)
        lineTo(stickerW * 0.96f, h * 0.88f)
        lineTo(1.5.dp.toPx(), h * 0.82f)
        close()
    }
    drawPath(path = tornScrap, color = MetaToolsTokens.StickerPaper)

    // Crisp black zine ink bar inside the torn paper strip
    drawRect(
        color = MetaToolsTokens.StickerInk,
        topLeft = Offset(2.5.dp.toPx(), h * 0.36f),
        size = Size(stickerW * 0.72f, h * 0.30f),
    )
    drawLine(
        color = MetaToolsTokens.StickerPaper,
        start = Offset(4.dp.toPx(), h * 0.51f),
        end = Offset(stickerW * 0.62f, h * 0.51f),
        strokeWidth = 1.dp.toPx(),
    )

    // Tiny secondary monochrome black corner tab on right flank
    drawRoundRect(
        color = MetaToolsTokens.StickerInk,
        topLeft = Offset(w * 0.74f, h * 0.42f),
        size = Size(w * 0.22f, h * 0.34f),
        cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
    )
    drawLine(
        color = MetaToolsTokens.StickerPaper,
        start = Offset(w * 0.77f, h * 0.59f),
        end = Offset(w * 0.92f, h * 0.59f),
        strokeWidth = 0.9.dp.toPx(),
    )
}
