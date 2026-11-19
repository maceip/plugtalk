package dev.onionvault.ui.pods

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import dev.onionvault.R
import dev.onionvault.ui.BrowserUiState
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.TrapGlyph
import dev.onionvault.ui.TrapIcon
import dev.onionvault.ui.TrustedOnionDirectory
import dev.onionvault.ui.formatClearnetRuleTtl

private const val REMOTE_COLLAPSED_ROTATION_DEG = 20f

// Exact normalized 2x5 + top-pilot LED bezel coordinates inside upright `pod_zk_tor_remote.webp` (200 x 620 px)
private const val REMOTE_LED_LEFT_X_FRAC = 0.2468f
private const val REMOTE_LED_RIGHT_X_FRAC = 0.7152f
private val REMOTE_LED_ROW_Y_FRACS = floatArrayOf(0.1469f, 0.2367f, 0.3257f, 0.4143f, 0.5029f)
private const val REMOTE_LED_RADIUS_W_FRAC = 0.0696f
private const val REMOTE_PILOT_LED_X_FRAC = 0.4835f
private const val REMOTE_PILOT_LED_Y_FRAC = 0.0604f
private const val REMOTE_PILOT_LED_RADIUS_W_FRAC = 0.048f

/**
 * Operational modes of the detached right-edge [PhotoSoapSideRemote].
 */
enum class RemoteMode {
    Menu,
    OnionDirectory,
    TorCircuit,
    ContextMenu,
    PgpActions,
}

/**
 * Truthful 5-pair (2x5) hardware LED status vector derived strictly from live [BrowserUiState].
 * Zero fabricated lights or synthetic activity pulses.
 */
@Immutable
data class RemoteTelemetryLeds(
    val torGreen: Boolean,
    val torAmber: Boolean,
    val routingGreen: Boolean,
    val routingAmber: Boolean,
    val isolationGreen: Boolean,
    val loadingAmber: Boolean,
    val pgpGreen: Boolean,
    val pgpAmber: Boolean,
    val zeroJsGreen: Boolean,
    val zeroJsAmber: Boolean,
    val stencilCode: String,
) {
    val fivePairs: List<Pair<Boolean, Boolean>>
        get() = listOf(
            torGreen to torAmber,
            routingGreen to routingAmber,
            isolationGreen to loadingAmber,
            pgpGreen to pgpAmber,
            zeroJsGreen to zeroJsAmber,
        )
}

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
        "ZK\n\"TOR\""
    }

    return RemoteTelemetryLeds(
        torGreen = state.tor.ready,
        torAmber = !state.tor.ready && (state.tor.percent > 0 || state.tor.stage.isNotBlank()),
        routingGreen = isOnion || state.hiddenServiceOnly,
        routingAmber = hasClearnetActivity,
        isolationGreen = !state.tor.isolationToken.isNullOrBlank() || state.isStartPage,
        loadingAmber = state.isLoading,
        pgpGreen = state.keys.isNotEmpty(),
        pgpAmber = hasPgpContent || !state.pgp.error.isNullOrBlank(),
        zeroJsGreen = !state.isScreenRecordingActive && !state.viewingSnapshot,
        zeroJsAmber = state.isScreenRecordingActive || state.viewingSnapshot,
        stencilCode = stencil,
    )
}

/**
 * Bottom-Right `+20 deg` Clockwise `ZK "TOR"` LED Remote (`side_remote_pod` / `side_remote_collapsed_face`) —
 * `metatools-reference.jpg` 1:1.
 *
 * - **Collapsed (`72.dp x 174.dp`, rotated `+20f` clockwise)**: Renders `R.drawable.pod_zk_tor_remote`
 *   (`pod_zk_tor_remote.webp`) with live 2x5 green/amber LED glow dots overlaid accurately on the
 *   2x5 recessed LED bezels!
 * - **Expanded (`268.dp` wide, upright `0 deg`)**: Tactile hardware menu including `"Onion Sites"`
 *   (`RemoteMode.OnionDirectory` — all 11 live-verified `.onion` sites), `Forward`, `Reload`,
 *   `Text Search`, `New Tab`, `Tabs (N)`, `Copy URL`, `Home`, `Zoom`, `Tor Circuit`, `Keyring`,
 *   `Settings`, and `Licences`.
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
    onNavigate: (String) -> Unit = {},
    onZoomIn: () -> Unit = {},
    onZoomOut: () -> Unit = {},
    onOpenFindInPage: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val grainBrush = rememberPewterGrainBrush()
    val remoteSpritePainter = painterResource(R.drawable.pod_zk_tor_remote)
    val leds = remember(state, hasPgpContent) {
        deriveRemoteTelemetryLeds(state = state, hasPgpContent = hasPgpContent)
    }

    BackHandler(enabled = expanded) {
        onCollapse()
    }

    val animatedWidth by animateDpAsState(
        targetValue = if (expanded) 268.dp else 60.dp,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.REMOTE_SLIDE_DURATION_MS,
            easing = LinearEasing,
        ),
        label = "side_remote_width",
    )

    val pillRadius = if (expanded) 24.dp else 30.dp
    val pillShape = RoundedCornerShape(pillRadius)
    val rotationDeg = if (expanded) 0f else REMOTE_COLLAPSED_ROTATION_DEG

    Box(
        modifier = modifier
            .width(animatedWidth)
            .metaToolsDropShadow(
                cornerRadius = pillRadius,
                rotationDegrees = rotationDeg,
                insetHorizontal = if (expanded) 0.dp else 4.dp,
                insetVertical = if (expanded) 0.dp else 4.dp,
            )
            .graphicsLayer {
                rotationZ = rotationDeg
            }
            .then(
                if (expanded) {
                    Modifier
                        .clip(pillShape)
                        .drawBehind {
                            drawAnodizedRemoteChassis(
                                grainBrush = grainBrush,
                                cornerRadiusPx = pillRadius.toPx(),
                                isCollapsed = false,
                            )
                        }
                } else {
                    Modifier
                },
            )
            .testTag("side_remote_pod"),
    ) {
        if (!expanded) {
            CollapsedRemoteFace(
                leds = leds,
                spritePainter = remoteSpritePainter,
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
                onNavigate = onNavigate,
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
                onZoomIn = onZoomIn,
                onZoomOut = onZoomOut,
                onOpenFindInPage = onOpenFindInPage,
            )
        }
    }
}

@Composable
private fun CollapsedRemoteFace(
    leds: RemoteTelemetryLeds,
    spritePainter: androidx.compose.ui.graphics.painter.Painter,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 60.dp, height = 186.dp)
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "Open hardware remote menu"
            }
            .testTag("side_remote_collapsed_face"),
    ) {
        Image(
            painter = spritePainter,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        // Live 2x5 + Top Pilot Green/Amber LED Glow Overlay aligned to the raytraced LED bezels
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pilotColor = when {
                leds.torGreen -> MetaToolsTokens.LedGreen
                leds.torAmber -> MetaToolsTokens.LedAmber
                else -> null
            }
            if (pilotColor != null) {
                drawLiveSpriteLedOverlay(
                    center = Offset(
                        x = size.width * REMOTE_PILOT_LED_X_FRAC,
                        y = size.height * REMOTE_PILOT_LED_Y_FRAC,
                    ),
                    lensRadiusPx = size.width * REMOTE_PILOT_LED_RADIUS_W_FRAC,
                    litColor = pilotColor,
                )
            }

            val lensRadiusPx = size.width * REMOTE_LED_RADIUS_W_FRAC
            val leftX = size.width * REMOTE_LED_LEFT_X_FRAC
            val rightX = size.width * REMOTE_LED_RIGHT_X_FRAC
            leds.fivePairs.forEachIndexed { index, (greenOn, amberOn) ->
                val y = size.height * REMOTE_LED_ROW_Y_FRACS[index]
                if (greenOn) {
                    drawLiveSpriteLedOverlay(
                        center = Offset(leftX, y),
                        lensRadiusPx = lensRadiusPx,
                        litColor = MetaToolsTokens.LedGreen,
                    )
                }
                if (amberOn) {
                    drawLiveSpriteLedOverlay(
                        center = Offset(rightX, y),
                        lensRadiusPx = lensRadiusPx,
                        litColor = MetaToolsTokens.LedAmber,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawLiveSpriteLedOverlay(
    center: Offset,
    lensRadiusPx: Float,
    litColor: Color,
) {
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to litColor.copy(alpha = 0.66f),
            0.58f to litColor.copy(alpha = 0.24f),
            1.0f to Color.Transparent,
            center = center,
            radius = lensRadiusPx * 2.25f,
        ),
        radius = lensRadiusPx * 2.25f,
        center = center,
    )
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to Color.White.copy(alpha = 0.96f),
            0.36f to litColor,
            0.88f to litColor.copy(alpha = 0.86f),
            1.0f to Color.Transparent,
            center = Offset(center.x - lensRadiusPx * 0.24f, center.y - lensRadiusPx * 0.24f),
            radius = lensRadiusPx * 1.12f,
        ),
        radius = lensRadiusPx,
        center = center,
    )
}

@Composable
private fun ExpandedRemoteFace(
    state: BrowserUiState,
    mode: RemoteMode,
    leds: RemoteTelemetryLeds,
    onSelectMode: (RemoteMode) -> Unit,
    onCollapse: () -> Unit,
    onNavigate: (String) -> Unit,
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
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onOpenFindInPage: () -> Unit,
) {
    val headerLabel = when (mode) {
        RemoteMode.Menu -> "REMOTE"
        RemoteMode.OnionDirectory -> "ONION SITES"
        RemoteMode.TorCircuit -> "TOR CIRCUIT"
        RemoteMode.ContextMenu -> "LINK / TEXT"
        RemoteMode.PgpActions -> "OPENPGP"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .padding(top = 12.dp, bottom = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(modifier = Modifier.size(14.dp)) {
                drawRecessedGlassLedBead(
                    center = center,
                    beadDiameterPx = 10.5.dp.toPx(),
                    litColor = if (leds.torGreen) MetaToolsTokens.LedGreen else MetaToolsTokens.LedAmber,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = headerLabel,
                color = MetaToolsTokens.StencilInk,
                fontSize = 14.sp,
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

        EngravedGrooveDivider(modifier = Modifier.padding(top = 8.dp))

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
                    onOpenOnionDirectory = { onSelectMode(RemoteMode.OnionDirectory) },
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
                    onZoomIn = onZoomIn,
                    onZoomOut = onZoomOut,
                    onOpenFindInPage = onOpenFindInPage,
                )

                RemoteMode.OnionDirectory -> RemoteOnionDirectoryBody(
                    onSelectOnionUrl = { targetUrl ->
                        onCollapse()
                        onNavigate(targetUrl)
                    },
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
    onOpenOnionDirectory: () -> Unit,
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
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onOpenFindInPage: () -> Unit,
) {
    RemoteEngravedRowButton(
        label = "Onion Sites",
        glyph = TrapGlyph.Onion,
        trailingToken = "${TrustedOnionDirectory.entries.size}",
        ledIdleColor = MetaToolsTokens.LedGreen,
        onClick = onOpenOnionDirectory,
        testTag = "remote_onion_sites",
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
        label = "Text Search",
        glyph = TrapGlyph.Search,
        enabled = !state.isStartPage,
        onClick = {
            onCollapse()
            onOpenFindInPage()
        },
        testTag = "remote_text_search",
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        RemoteMiniKey(
            label = "−",
            onClick = onZoomOut,
            testTag = "remote_zoom_out",
        )
        Text(
            text = "Zoom ${state.zoomPercent}%",
            color = MetaToolsTokens.StencilInk,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.testTag("remote_zoom_readout"),
        )
        RemoteMiniKey(
            label = "+",
            onClick = onZoomIn,
            testTag = "remote_zoom_in",
        )
    }
    EngravedGrooveDivider()
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
private fun RemoteOnionDirectoryBody(
    onSelectOnionUrl: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote_onion_directory"),
    ) {
        val allEntries = TrustedOnionDirectory.entries
        allEntries.forEachIndexed { idx, entry ->
            RemoteEngravedRowButton(
                label = entry.title,
                glyph = TrapGlyph.Onion,
                trailingToken = "${entry.rootV3Label.take(4)}…${entry.rootV3Label.takeLast(4)}",
                ledIdleColor = MetaToolsTokens.LedGreen,
                showBottomGroove = idx < allEntries.lastIndex,
                onClick = { onSelectOnionUrl(entry.url) },
                testTag = "remote_onion_dir_${entry.id}",
            )
        }
    }
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MetaToolsTokens.CrtBlackGlass)
                .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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

        RemoteEngravedRowButton(
            label = ".onion-Only Lock",
            glyph = TrapGlyph.Onion,
            trailingToken = if (state.hiddenServiceOnly) "ON" else "OFF",
            ledIdleColor = if (state.hiddenServiceOnly) MetaToolsTokens.LedGreen else null,
            onClick = { onToggleHiddenServiceOnly(!state.hiddenServiceOnly) },
            testTag = "remote_onion_only_toggle",
        )

        if (state.clearnetRules.isNotEmpty()) {
            Text(
                text = "CLEARNET RULES (${state.clearnetRules.size})",
                color = MetaToolsTokens.StencilInk,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            state.clearnetRules.forEach { rule: OvNative.ClearnetRuleItem ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MetaToolsTokens.AnodizedRecessedWell)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rule.pattern,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatClearnetRuleTtl(rule, nowMs),
                            color = MetaToolsTokens.PhosphorClearnetGreen,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
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
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MetaToolsTokens.CrtBlackGlass)
                    .padding(10.dp),
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
                .height(45.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .drawBehind {
                    if (isPressed && enabled) {
                        val r = 9.dp.toPx()
                        drawRoundRect(
                            color = MetaToolsTokens.AnodizedRecessedWell,
                            cornerRadius = CornerRadius(r, r),
                        )
                        drawMetallicSquircleBevel(
                            cornerRadiusPx = r,
                            strokeWidthPx = 1.2.dp.toPx(),
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
                .padding(horizontal = 10.dp)
                .offset(x = pressShift, y = pressShift)
                .testTag(testTag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(modifier = Modifier.size(12.dp)) {
                drawRecessedGlassLedBead(
                    center = center,
                    beadDiameterPx = 8.5.dp.toPx(),
                    litColor = activeLedColor,
                )
            }
            Spacer(Modifier.width(10.dp))
            TrapIcon(
                glyph = glyph,
                tint = if (enabled) MetaToolsTokens.StencilInk else MetaToolsTokens.PhosphorPathDim,
                size = 19.dp,
                enabled = enabled,
                forceVector = true,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                color = if (enabled) Color(0xFFF0F4F8) else MetaToolsTokens.PhosphorPathDim,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!trailingToken.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = trailingToken,
                    color = MetaToolsTokens.PhosphorClearnetGreen,
                    fontSize = 12.sp,
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
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(MetaToolsTokens.AnodizedRecessedWell)
            .border(1.2.dp, MetaToolsTokens.AnodizedBevelLight, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MetaToolsTokens.StencilInk,
            fontSize = 12.5.sp,
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
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.5.sp,
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
            .padding(horizontal = 10.dp),
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

    drawRoundRect(
        brush = Brush.horizontalGradient(
            0.0f to MetaToolsTokens.AnodizedSurfaceTop,
            0.26f to Color(0xFF343B46),
            0.68f to MetaToolsTokens.AnodizedSurface,
            1.0f to MetaToolsTokens.AnodizedSurfaceDeep,
        ),
        cornerRadius = corner,
    )
    drawRoundRect(brush = grainBrush, cornerRadius = corner)

    drawMetallicSquircleBevel(
        cornerRadiusPx = cornerRadiusPx,
        strokeWidthPx = 2.dp.toPx(),
        highlightColor = MetaToolsTokens.AnodizedBevelLight,
        shadowColor = MetaToolsTokens.AnodizedBevelDark,
    )

    if (isCollapsed) {
        val scuffColor = Color(0x70C4CCD6)
        drawLine(
            color = scuffColor,
            start = Offset(size.width - 3.dp.toPx(), size.height * 0.24f),
            end = Offset(size.width - 1.5.dp.toPx(), size.height * 0.27f),
            strokeWidth = 1.2.dp.toPx(),
        )
    }
}

private fun DrawScope.drawTornMonochromeSticker() {
    val h = size.height
    val w = size.width
    val stickerW = w * 0.68f

    val tornScrap = Path().apply {
        moveTo(2.dp.toPx(), h * 0.12f)
        lineTo(stickerW * 0.88f, h * 0.22f)
        lineTo(stickerW, h * 0.48f)
        lineTo(stickerW * 0.91f, h * 0.70f)
        lineTo(stickerW * 0.96f, h * 0.90f)
        lineTo(2.dp.toPx(), h * 0.84f)
        close()
    }
    drawPath(path = tornScrap, color = MetaToolsTokens.StickerPaper)

    drawRect(
        color = MetaToolsTokens.StickerInk,
        topLeft = Offset(3.5.dp.toPx(), h * 0.36f),
        size = Size(stickerW * 0.72f, h * 0.32f),
    )
    drawLine(
        color = MetaToolsTokens.StickerPaper,
        start = Offset(5.5.dp.toPx(), h * 0.52f),
        end = Offset(stickerW * 0.62f, h * 0.52f),
        strokeWidth = 1.3.dp.toPx(),
    )
}
