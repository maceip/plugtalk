package dev.onionvault.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.onionvault.platform.SensitiveContentEffect
import dev.onionvault.platform.SurfaceFilterMode
import dev.onionvault.ui.pods.MetaToolsBrowserShell
import dev.onionvault.ui.pods.RemoteMode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Primary Compose Browser Shell for Trap.
 *
 * Renders the 100% full-bleed edge-to-edge browser viewport and street-zine [StartPage]
 * (`0dp` top/bottom container bars) beneath [MetaToolsBrowserShell]'s detached sculptural
 * hardware pods (`PerimeterSteelRails`, `TorKnurledOrbPod`, `RecessedCrtAddressPod`,
 * `OpenPgpRockerPod`, `PhotoSoapSideRemote`, and `HardwareModalPods`).
 */
@Composable
fun BrowserScreen(
    state: BrowserUiState,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
    onAllowClearnet: (prompt: ClearnetPromptState, pattern: String, ttlSeconds: Long) -> Unit = { _, _, _ -> },
    onBlockClearnet: (prompt: ClearnetPromptState) -> Unit = {},
    onRevokeClearnetRule: (String) -> Unit = {},
    onClearClearnetRules: () -> Unit = {},
    onFieldAction: (PgpFieldState) -> Unit = {},
    onInsertMyPubKey: (PgpFieldState) -> Unit = {},
    onConfirmEncrypt: (
        request: EncryptRequestState,
        plaintext: String,
        recipientFingerprint: String,
        signerFingerprintOrEmpty: String,
        passphrase: String,
    ) -> Unit = { _, _, _, _, _ -> },
    onDismissEncrypt: () -> Unit = {},
    onSubmitDecryptPassphrase: (DecryptPassphraseRequestState, String) -> Unit = { _, _ -> },
    onDismissDecryptPassphrase: () -> Unit = {},
    onCopyPlaintext: (String) -> Unit = {},
    onInsertPlaintext: (PgpPlaintextState) -> Unit = {},
    onDismissPlaintext: () -> Unit = {},
    onReloadLive: () -> Unit = {},
    onBack: () -> Unit = {},
    onForward: () -> Unit = {},
    onReload: () -> Unit = {},
    onNewTab: () -> Unit = {},
    onNewPrivateTab: () -> Unit = {},
    onSelectTab: (String) -> Unit = {},
    onSwitchAdjacentTab: (Int) -> Unit = {},
    onCloseTab: (String) -> Unit = {},
    onHome: () -> Unit = {},
    onGenerateKey: (userId: String, passphrase: String) -> Unit = { _, _ -> },
    onImportKey: (armored: String, passphrase: String) -> Unit = { _, _ -> },
    onExportKey: (String) -> Unit = {},
    onSetDefaultKey: (String) -> Unit = {},
    onDeleteKey: (String) -> Unit = {},
    onToggleRouting: (Boolean) -> Unit = {},
    onToggleRequireBiometric: (Boolean) -> Unit = {},
    onTogglePipOnMinimize: (Boolean) -> Unit = {},
    onTogglePipExcludePrivateTabs: (Boolean) -> Unit = {},
    onSelectSurfaceFilterMode: (SurfaceFilterMode) -> Unit = {},
    onToggleHandoff: (Boolean) -> Unit = {},
    onResetTorIsolation: () -> Unit = {},
    onSelectThemeFamily: (TrapThemeFamily) -> Unit = {},
    onSelectThemeMode: (TrapThemeMode) -> Unit = {},
    onSelectDefaultZoom: (Int) -> Unit = {},
    onClearSession: () -> Unit = {},
    onZoomIn: () -> Unit = {},
    onZoomOut: () -> Unit = {},
    onDismissStatus: () -> Unit = {},
    onContextMenuOpenNewTab: (String) -> Unit = {},
    onContextMenuOpenCurrentTab: (String) -> Unit = {},
    onCopyText: (String) -> Unit = {},
    onDecryptArmoredText: (String) -> Unit = {},
    onEncryptSelectedText: (String) -> Unit = {},
    onDismissContextMenu: () -> Unit = {},
    onDismissSelectionPgp: () -> Unit = {},
    onOverlayOpenChanged: (Boolean) -> Unit = {},
    onSetPodsRetracted: (Boolean) -> Unit = {},
    onLoadLegalLines: suspend (String) -> List<String> = { emptyList() },
    surface: @Composable () -> Unit = {},
    previewProgress: Float = 0f,
    showKeyManager: Boolean = false,
    showLegalNotices: Boolean = false,
    showMenu: Boolean = false,
    showTabs: Boolean = false,
    showSecurity: Boolean = false,
    showSettings: Boolean = false,
) {
    TrapTheme(
        family = state.themeFamily,
        mode = state.themeMode,
    ) {
        BrowserScreenContent(
            state = state,
            modifier = modifier,
            onNavigate = onNavigate,
            onAllowClearnet = onAllowClearnet,
            onBlockClearnet = onBlockClearnet,
            onRevokeClearnetRule = onRevokeClearnetRule,
            onClearClearnetRules = onClearClearnetRules,
            onFieldAction = onFieldAction,
            onInsertMyPubKey = onInsertMyPubKey,
            onConfirmEncrypt = onConfirmEncrypt,
            onDismissEncrypt = onDismissEncrypt,
            onSubmitDecryptPassphrase = onSubmitDecryptPassphrase,
            onDismissDecryptPassphrase = onDismissDecryptPassphrase,
            onCopyPlaintext = onCopyPlaintext,
            onInsertPlaintext = onInsertPlaintext,
            onDismissPlaintext = onDismissPlaintext,
            onReloadLive = onReloadLive,
            onBack = onBack,
            onForward = onForward,
            onReload = onReload,
            onNewTab = onNewTab,
            onNewPrivateTab = onNewPrivateTab,
            onSelectTab = onSelectTab,
            onSwitchAdjacentTab = onSwitchAdjacentTab,
            onCloseTab = onCloseTab,
            onHome = onHome,
            onGenerateKey = onGenerateKey,
            onImportKey = onImportKey,
            onExportKey = onExportKey,
            onSetDefaultKey = onSetDefaultKey,
            onDeleteKey = onDeleteKey,
            onToggleRouting = onToggleRouting,
            onToggleRequireBiometric = onToggleRequireBiometric,
            onTogglePipOnMinimize = onTogglePipOnMinimize,
            onTogglePipExcludePrivateTabs = onTogglePipExcludePrivateTabs,
            onSelectSurfaceFilterMode = onSelectSurfaceFilterMode,
            onToggleHandoff = onToggleHandoff,
            onResetTorIsolation = onResetTorIsolation,
            onSelectThemeFamily = onSelectThemeFamily,
            onSelectThemeMode = onSelectThemeMode,
            onSelectDefaultZoom = onSelectDefaultZoom,
            onClearSession = onClearSession,
            onZoomIn = onZoomIn,
            onZoomOut = onZoomOut,
            onDismissStatus = onDismissStatus,
            onContextMenuOpenNewTab = onContextMenuOpenNewTab,
            onContextMenuOpenCurrentTab = onContextMenuOpenCurrentTab,
            onCopyText = onCopyText,
            onDecryptArmoredText = onDecryptArmoredText,
            onEncryptSelectedText = onEncryptSelectedText,
            onDismissContextMenu = onDismissContextMenu,
            onDismissSelectionPgp = onDismissSelectionPgp,
            onOverlayOpenChanged = onOverlayOpenChanged,
            onSetPodsRetracted = onSetPodsRetracted,
            onLoadLegalLines = onLoadLegalLines,
            surface = surface,
            previewProgress = previewProgress,
            showKeyManager = showKeyManager,
            showLegalNotices = showLegalNotices,
            showMenu = showMenu,
            showTabs = showTabs,
            showSecurity = showSecurity,
            showSettings = showSettings,
        )
    }
}

@Composable
private fun BrowserScreenContent(
    state: BrowserUiState,
    modifier: Modifier,
    onNavigate: (String) -> Unit,
    onAllowClearnet: (prompt: ClearnetPromptState, pattern: String, ttlSeconds: Long) -> Unit,
    onBlockClearnet: (prompt: ClearnetPromptState) -> Unit,
    onRevokeClearnetRule: (String) -> Unit,
    onClearClearnetRules: () -> Unit,
    onFieldAction: (PgpFieldState) -> Unit,
    onInsertMyPubKey: (PgpFieldState) -> Unit,
    onConfirmEncrypt: (
        request: EncryptRequestState,
        plaintext: String,
        recipientFingerprint: String,
        signerFingerprintOrEmpty: String,
        passphrase: String,
    ) -> Unit,
    onDismissEncrypt: () -> Unit,
    onSubmitDecryptPassphrase: (DecryptPassphraseRequestState, String) -> Unit,
    onDismissDecryptPassphrase: () -> Unit,
    onCopyPlaintext: (String) -> Unit,
    onInsertPlaintext: (PgpPlaintextState) -> Unit,
    onDismissPlaintext: () -> Unit,
    onReloadLive: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onSelectTab: (String) -> Unit,
    onSwitchAdjacentTab: (Int) -> Unit,
    onCloseTab: (String) -> Unit,
    onHome: () -> Unit,
    onGenerateKey: (userId: String, passphrase: String) -> Unit,
    onImportKey: (armored: String, passphrase: String) -> Unit,
    onExportKey: (String) -> Unit,
    onSetDefaultKey: (String) -> Unit,
    onDeleteKey: (String) -> Unit,
    onToggleRouting: (Boolean) -> Unit,
    onToggleRequireBiometric: (Boolean) -> Unit,
    onTogglePipOnMinimize: (Boolean) -> Unit,
    onTogglePipExcludePrivateTabs: (Boolean) -> Unit,
    onSelectSurfaceFilterMode: (SurfaceFilterMode) -> Unit,
    onToggleHandoff: (Boolean) -> Unit,
    onResetTorIsolation: () -> Unit,
    onSelectThemeFamily: (TrapThemeFamily) -> Unit,
    onSelectThemeMode: (TrapThemeMode) -> Unit,
    onSelectDefaultZoom: (Int) -> Unit,
    onClearSession: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onDismissStatus: () -> Unit,
    onContextMenuOpenNewTab: (String) -> Unit,
    onContextMenuOpenCurrentTab: (String) -> Unit,
    onCopyText: (String) -> Unit,
    onDecryptArmoredText: (String) -> Unit,
    onEncryptSelectedText: (String) -> Unit,
    onDismissContextMenu: () -> Unit,
    onDismissSelectionPgp: () -> Unit,
    onOverlayOpenChanged: (Boolean) -> Unit,
    onSetPodsRetracted: (Boolean) -> Unit,
    onLoadLegalLines: suspend (String) -> List<String>,
    surface: @Composable () -> Unit,
    previewProgress: Float,
    showKeyManager: Boolean,
    showLegalNotices: Boolean,
    showMenu: Boolean,
    showTabs: Boolean,
    showSecurity: Boolean,
    showSettings: Boolean,
) {
    val colors = TrapTheme.colors
    val scale = remember { Animatable(1f - previewProgress * 0.12f) }
    val shift = remember { Animatable(previewProgress * 96f) }
    var progress by remember { mutableFloatStateOf(previewProgress) }
    var shellOverlayOpen by remember { mutableStateOf(false) }
    var openTorCircuitCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val chromeHiddenForPip = state.isInPipMode || state.isPipChromeHidden

    PredictiveBackHandler(
        enabled = (state.canGoBack || !state.isStartPage) && !shellOverlayOpen && !chromeHiddenForPip,
    ) { backFlow ->
        try {
            backFlow.collect { event ->
                progress = event.progress
                scope.launch {
                    scale.snapTo(1f - event.progress * 0.12f)
                    shift.snapTo(
                        event.progress * 96f *
                            if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f,
                    )
                }
            }
            if (state.canGoBack) {
                onBack()
            } else {
                onHome()
            }
        } finally {
            withContext(NonCancellable) {
                scale.animateTo(1f, animationSpec = tween(180))
                shift.animateTo(0f, animationSpec = tween(180))
            }
            progress = 0f
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .testTag("browser_screen"),
    ) {
        MetaToolsBrowserShell(
            state = state,
            podsRetracted = state.podsRetracted,
            onSetPodsRetracted = onSetPodsRetracted,
            initialRemoteExpanded = showMenu || showSecurity,
            initialRemoteMode = if (showSecurity) RemoteMode.TorCircuit else RemoteMode.Menu,
            openTorCircuitTrigger = openTorCircuitCount,
            showTabsModal = showTabs,
            showKeyringModal = showKeyManager,
            showSettingsModal = showSettings,
            showLegalModal = showLegalNotices,
            onNavigate = onNavigate,
            onSwitchAdjacentTab = onSwitchAdjacentTab,
            onSelectTab = onSelectTab,
            onCloseTab = onCloseTab,
            onNewTab = onNewTab,
            onNewPrivateTab = onNewPrivateTab,
            onBack = onBack,
            onForward = onForward,
            onReload = onReload,
            onHome = onHome,
            onZoomIn = onZoomIn,
            onZoomOut = onZoomOut,
            onAllowClearnet = onAllowClearnet,
            onBlockClearnet = onBlockClearnet,
            onRevokeClearnetRule = onRevokeClearnetRule,
            onClearClearnetRules = onClearClearnetRules,
            onToggleRouting = onToggleRouting,
            onResetTorIsolation = onResetTorIsolation,
            onFieldAction = onFieldAction,
            onInsertMyPubKey = onInsertMyPubKey,
            onConfirmEncrypt = onConfirmEncrypt,
            onDismissEncrypt = onDismissEncrypt,
            onSubmitDecryptPassphrase = onSubmitDecryptPassphrase,
            onDismissDecryptPassphrase = onDismissDecryptPassphrase,
            onCopyPlaintext = onCopyPlaintext,
            onInsertPlaintext = onInsertPlaintext,
            onDismissPlaintext = onDismissPlaintext,
            onGenerateKey = onGenerateKey,
            onImportKey = onImportKey,
            onExportKey = onExportKey,
            onSetDefaultKey = onSetDefaultKey,
            onDeleteKey = onDeleteKey,
            onToggleRequireBiometric = onToggleRequireBiometric,
            onTogglePipOnMinimize = onTogglePipOnMinimize,
            onTogglePipExcludePrivateTabs = onTogglePipExcludePrivateTabs,
            onSelectSurfaceFilterMode = onSelectSurfaceFilterMode,
            onToggleHandoff = onToggleHandoff,
            onSelectThemeFamily = onSelectThemeFamily,
            onSelectThemeMode = onSelectThemeMode,
            onSelectDefaultZoom = onSelectDefaultZoom,
            onClearSession = onClearSession,
            onContextMenuOpenNewTab = onContextMenuOpenNewTab,
            onContextMenuOpenCurrentTab = onContextMenuOpenCurrentTab,
            onCopyText = onCopyText,
            onDecryptArmoredText = onDecryptArmoredText,
            onEncryptSelectedText = onEncryptSelectedText,
            onDismissContextMenu = onDismissContextMenu,
            onDismissSelectionPgp = onDismissSelectionPgp,
            onOverlayOpenChanged = { open ->
                shellOverlayOpen = open
                onOverlayOpenChanged(open)
            },
            onLoadLegalLines = onLoadLegalLines,
            surface = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background),
                ) {
                    if (progress > 0f && state.backPreview != null && !chromeHiddenForPip) {
                        BackPreviewLayer(state = state, progress = progress)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale.value
                                scaleY = scale.value
                                translationX = shift.value
                            },
                    ) {
                        val webpageViewportModifier = if (!chromeHiddenForPip && !state.isStartPage) {
                            Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                        } else {
                            Modifier.fillMaxSize()
                        }

                        Box(modifier = webpageViewportModifier) {
                            surface()

                            if (state.viewingSnapshot && state.backPreview != null && progress == 0f) {
                                BackPreviewLayer(state = state, progress = 1f)
                            }

                            if (!chromeHiddenForPip &&
                                !state.isScreenRecordingActive &&
                                !state.isStartPage &&
                                state.pgp.fields.isNotEmpty() &&
                                progress == 0f
                            ) {
                                PgpFieldOverlay(
                                    state = state,
                                    onFieldAction = onFieldAction,
                                    onInsertMyPubKey = onInsertMyPubKey,
                                )
                            }
                        }

                        if (state.isStartPage && progress == 0f) {
                            StartPage(
                                state = state,
                                onOpenSecurity = {
                                    if (!chromeHiddenForPip) {
                                        openTorCircuitCount += 1
                                    }
                                },
                                onNavigate = onNavigate,
                            )
                        }
                    }

                    if (!chromeHiddenForPip) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(top = 6.dp),
                        ) {
                            if (state.isScreenRecordingActive) {
                                ScreenRecordingWarningBanner()
                            }

                            if (state.viewingSnapshot) {
                                SnapshotReloadBanner(onReloadLive = onReloadLive)
                            }

                            val bannerMessage = if (state.clearnetPrompt != null) {
                                null
                            } else {
                                state.pgp.error?.takeIf { it.isNotBlank() }
                                    ?: state.status.takeIf { it.isNotBlank() }
                                    ?: if (state.blocked) {
                                        "Clearnet request refused by Hidden Service Only policy."
                                    } else {
                                        null
                                    }
                            }

                            if (bannerMessage != null) {
                                StatusNoticeBanner(
                                    message = bannerMessage,
                                    isAlert = state.blocked || !state.pgp.error.isNullOrBlank(),
                                    onDismiss = onDismissStatus,
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ScreenRecordingWarningBanner() {
    val colors = TrapTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(colors.dangerContainer)
            .border(1.dp, colors.danger, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("screen_recording_warning_banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrapIcon(
            glyph = TrapGlyph.Warning,
            tint = colors.danger,
            size = 16.dp,
            forceVector = true,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Screen recording active · PGP & credential popups paused",
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SnapshotReloadBanner(onReloadLive: () -> Unit) {
    val colors = TrapTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.teal, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("snapshot_reload_banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Cached snapshot",
            color = colors.textPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        OvSecondaryButton(
            text = "Reload Live over Tor",
            glyph = TrapGlyph.Reload,
            onClick = onReloadLive,
            modifier = Modifier.testTag("reload_live_button"),
        )
    }
}

@Composable
private fun StatusNoticeBanner(
    message: String,
    isAlert: Boolean,
    onDismiss: () -> Unit,
) {
    val colors = TrapTheme.colors
    val shape = RoundedCornerShape(10.dp)
    val borderColor = if (isAlert) colors.danger else colors.teal
    val bgColor = if (isAlert) colors.dangerContainer else colors.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("status_notice_banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrapIcon(
            glyph = if (isAlert) TrapGlyph.Warning else TrapGlyph.Shield,
            tint = borderColor,
            size = 16.dp,
            forceVector = true,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = message,
            color = colors.textPrimary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        OvIconButton(
            glyph = TrapGlyph.Close,
            contentDescription = "Dismiss notice",
            onClick = onDismiss,
            tint = colors.textMuted,
            size = 26.dp,
        )
    }
}

@Composable
private fun BackPreviewLayer(state: BrowserUiState, progress: Float) {
    val colors = TrapTheme.colors
    val preview = state.backPreview ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .testTag("back_preview_layer"),
        contentAlignment = Alignment.Center,
    ) {
        val frame = preview.frameSnapshot
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = preview.title.ifEmpty { preview.url },
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    text = preview.title.ifEmpty { "Previous Page" },
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = preview.url,
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f * (1f - progress.coerceIn(0f, 1f)))),
        )
    }
}

/**
 * Anchored PGP overlay chips positioned from CDP `DOM.getBoxModel` normalized viewport bounds.
 */
@Composable
private fun PgpFieldOverlay(
    state: BrowserUiState,
    onFieldAction: (PgpFieldState) -> Unit,
    onInsertMyPubKey: (PgpFieldState) -> Unit,
) {
    val fields = state.pgp.fields
    if (fields.isEmpty()) return

    SensitiveContentEffect()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("pgp_field_overlay"),
    ) {
        val viewportW = maxWidth
        val viewportH = maxHeight

        fields.forEach { field ->
            val bounds = field.bounds
            val anchorX = (viewportW * bounds.left + 8.dp).coerceIn(8.dp, (viewportW - 140.dp).coerceAtLeast(8.dp))
            val fieldHeight = viewportH * (bounds.bottom - bounds.top).coerceAtLeast(0f)
            val rawAnchorY = if (field.isEditableTarget() && fieldHeight < 76.dp) {
                viewportH * bounds.bottom + 6.dp
            } else {
                (viewportH * bounds.bottom - 38.dp).coerceAtLeast(viewportH * bounds.top + 6.dp)
            }
            val anchorY = rawAnchorY.coerceIn(6.dp, (viewportH - 42.dp).coerceAtLeast(6.dp))

            Box(
                modifier = Modifier.offset(x = anchorX, y = anchorY),
            ) {
                when (field.kind) {
                    "pgp_textarea", "textarea", "text_input", "editable" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OvPgpPill(
                                text = "Encrypt Field",
                                kind = PgpPillKind.EncryptField,
                                onClick = { onFieldAction(field) },
                            )
                            OvPgpPill(
                                text = "Insert My PubKey",
                                kind = PgpPillKind.InsertPubKey,
                                onClick = { onInsertMyPubKey(field) },
                            )
                        }
                    }

                    "pgp_message" -> {
                        OvPgpPill(
                            text = "Decrypt Block",
                            kind = PgpPillKind.DecryptBlock,
                            onClick = { onFieldAction(field) },
                        )
                    }

                    "pgp_public_key" -> {
                        if (field.isEditablePubKeyTarget()) {
                            OvPgpPill(
                                text = "Insert My PubKey",
                                kind = PgpPillKind.InsertPubKey,
                                onClick = { onInsertMyPubKey(field) },
                            )
                        } else {
                            OvPgpPill(
                                text = "Import Key",
                                kind = PgpPillKind.ImportKey,
                                onClick = { onFieldAction(field) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val EDITABLE_FIELD_KINDS = setOf("pgp_textarea", "textarea", "text_input", "editable")

private fun PgpFieldState.isEditablePubKeyTarget(): Boolean =
    kind == "pgp_public_key" && label.equals("Insert My PubKey", ignoreCase = true)

private fun PgpFieldState.isEditableTarget(): Boolean =
    !isSearchLike && (kind in EDITABLE_FIELD_KINDS || isEditablePubKeyTarget())
