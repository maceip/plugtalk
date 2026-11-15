package dev.onionvault.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.onionvault.R
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Primary Compose Browser Chrome for Trap.
 *
 * Enforces a strict single-row bottom-only bar (`56.dp` content height before system
 * navigation bar inset, `0` top app bars, `0` footer bars):
 * - Full-bleed viewport from `statusBarsPadding()` down to `bottom_control_dock`
 *   with only a thin `2.dp` loading bar overlaid at top edge while loading.
 * - Unfocused (`< 560.dp`): `[ Back (40dp) · Capsule (Shield + URL + Reload | Go) · Tabs · Menu ]`
 * - Unfocused (`>= 560.dp`): adds `Forward (44dp)` next to `Back`.
 * - Focused: `[ Cancel (40dp) · Capsule (URL + Clear ✕ | Go) ]` in the same `44.dp` row.
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
    onSelectTab: (String) -> Unit = {},
    onCloseTab: (String) -> Unit = {},
    onHome: () -> Unit = {},
    onGenerateKey: (userId: String, passphrase: String) -> Unit = { _, _ -> },
    onImportKey: (armored: String, passphrase: String) -> Unit = { _, _ -> },
    onExportKey: (String) -> Unit = {},
    onSetDefaultKey: (String) -> Unit = {},
    onDeleteKey: (String) -> Unit = {},
    onToggleRouting: (Boolean) -> Unit = {},
    onToggleRequireBiometric: (Boolean) -> Unit = {},
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
            onSelectTab = onSelectTab,
            onCloseTab = onCloseTab,
            onHome = onHome,
            onGenerateKey = onGenerateKey,
            onImportKey = onImportKey,
            onExportKey = onExportKey,
            onSetDefaultKey = onSetDefaultKey,
            onDeleteKey = onDeleteKey,
            onToggleRouting = onToggleRouting,
            onToggleRequireBiometric = onToggleRequireBiometric,
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
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onHome: () -> Unit,
    onGenerateKey: (userId: String, passphrase: String) -> Unit,
    onImportKey: (armored: String, passphrase: String) -> Unit,
    onExportKey: (String) -> Unit,
    onSetDefaultKey: (String) -> Unit,
    onDeleteKey: (String) -> Unit,
    onToggleRouting: (Boolean) -> Unit,
    onToggleRequireBiometric: (Boolean) -> Unit,
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
    var address by remember(state.url, state.activeTabId) {
        mutableStateOf(if (isBlankOrStartUrl(state.url)) "" else state.url)
    }
    val scale = remember { Animatable(1f - previewProgress * 0.12f) }
    val shift = remember { Animatable(previewProgress * 96f) }
    var progress by remember { mutableFloatStateOf(previewProgress) }
    var keysOpen by remember { mutableStateOf(showKeyManager) }
    var legalOpen by remember { mutableStateOf(showLegalNotices) }
    var legalDocument by remember { mutableStateOf<LegalDocument?>(null) }
    var menuOpen by remember { mutableStateOf(showMenu) }
    var tabsOpen by remember { mutableStateOf(showTabs) }
    var securityOpen by remember { mutableStateOf(showSecurity) }
    var settingsOpen by remember { mutableStateOf(showSettings) }
    val scope = rememberCoroutineScope()

    val anyOverlayOpen = keysOpen ||
        legalOpen ||
        legalDocument != null ||
        menuOpen ||
        tabsOpen ||
        securityOpen ||
        settingsOpen ||
        state.clearnetPrompt != null ||
        state.pgp.plaintext != null ||
        state.pgp.encryptRequest != null ||
        state.pgp.decryptPassphraseRequest != null ||
        state.contextMenu != null ||
        state.selectionPgp != null

    LaunchedEffect(anyOverlayOpen) {
        onOverlayOpenChanged(anyOverlayOpen)
    }

    PredictiveBackHandler(enabled = state.canGoBack && !anyOverlayOpen) { backFlow ->
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
            onBack()
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // Full-bleed Center Viewport (no top app bar, no top address bar)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(colors.background),
            ) {
                if (progress > 0f && state.backPreview != null) {
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
                    surface()

                    if (state.viewingSnapshot && state.backPreview != null && progress == 0f) {
                        BackPreviewLayer(state = state, progress = 1f)
                    }

                    if (state.isStartPage && progress == 0f) {
                        StartPage(
                            state = state,
                            onOpenSecurity = { securityOpen = true },
                        )
                    }
                }

                if (!state.isStartPage && state.pgp.fields.isNotEmpty() && progress == 0f) {
                    PgpFieldOverlay(
                        state = state,
                        onFieldAction = onFieldAction,
                        onInsertMyPubKey = onInsertMyPubKey,
                    )
                }

                // Thin 2.dp top-edge loading indicator + transient notices
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                ) {
                    if (state.isLoading) {
                        OvProgressBar(
                            progress = (state.loadProgress.coerceIn(8, 100)) / 100f,
                            height = 2.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("top_loading_bar"),
                        )
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

            // Strictly single-row Bottom Control Dock (~56.dp content height)
            BottomControlDock(
                state = state,
                address = address,
                onAddressChange = { address = it },
                onSubmitAddress = {
                    val trimmed = address.trim()
                    onNavigate(trimmed.ifEmpty { "about:blank" })
                },
                onClearAddress = { address = "" },
                onCancelAddressEdit = {
                    address = if (isBlankOrStartUrl(state.url)) "" else state.url
                },
                onSecurityClick = { securityOpen = true },
                onBack = onBack,
                onForward = onForward,
                onReload = onReload,
                onOpenTabs = { tabsOpen = true },
                onOpenMenu = { menuOpen = true },
            )
        }

        // Modal Sheets & Dialogs
        state.clearnetPrompt?.let { prompt ->
            ClearnetConsentSheet(
                prompt = prompt,
                onAllow = onAllowClearnet,
                onBlock = onBlockClearnet,
            )
        }

        if (menuOpen) {
            BrowserMenuSheet(
                state = state,
                onForward = onForward,
                onReload = onReload,
                onNewTab = {
                    address = ""
                    onNewTab()
                },
                onStartPage = {
                    address = ""
                    onHome()
                },
                onOpenTabs = { tabsOpen = true },
                onOpenKeyring = { keysOpen = true },
                onOpenSecurity = { securityOpen = true },
                onOpenSettings = { settingsOpen = true },
                onZoomIn = onZoomIn,
                onZoomOut = onZoomOut,
                onOpenLegal = { legalOpen = true },
                onDismiss = { menuOpen = false },
            )
        }

        if (tabsOpen) {
            TabsSheet(
                tabs = state.tabs,
                onSelectTab = onSelectTab,
                onCloseTab = onCloseTab,
                onNewTab = {
                    address = ""
                    onNewTab()
                },
                onDismiss = { tabsOpen = false },
            )
        }

        if (securityOpen) {
            SecurityCircuitSheet(
                state = state,
                onToggleHiddenServiceOnly = onToggleRouting,
                onResetTorIsolation = onResetTorIsolation,
                onRevokeClearnetRule = onRevokeClearnetRule,
                onClearClearnetRules = onClearClearnetRules,
                onDismiss = { securityOpen = false },
            )
        }

        if (settingsOpen) {
            SettingsSheet(
                state = state,
                onSelectThemeFamily = onSelectThemeFamily,
                onSelectThemeMode = onSelectThemeMode,
                onToggleHiddenServiceOnly = onToggleRouting,
                onToggleRequireBiometric = onToggleRequireBiometric,
                onSelectDefaultZoom = onSelectDefaultZoom,
                onRevokeClearnetRule = onRevokeClearnetRule,
                onClearClearnetRules = onClearClearnetRules,
                onClearSession = onClearSession,
                onOpenLegalNotices = { legalOpen = true },
                onDismiss = { settingsOpen = false },
            )
        }

        if (keysOpen) {
            KeyringSheet(
                keys = state.keys,
                defaultKeyFingerprint = state.defaultKeyFingerprint,
                errorMessage = state.pgp.error,
                onGenerateKey = onGenerateKey,
                onImportKey = onImportKey,
                onExportKey = onExportKey,
                onSetDefaultKey = onSetDefaultKey,
                onDeleteKey = onDeleteKey,
                onDismiss = { keysOpen = false },
            )
        }

        state.pgp.encryptRequest?.let { req ->
            EncryptPickerSheet(
                request = req,
                keys = state.keys,
                defaultKeyFingerprint = state.defaultKeyFingerprint,
                errorMessage = state.pgp.error,
                onOpenKeyring = { keysOpen = true },
                onConfirmEncrypt = { plaintext, recipientFp, signerFp, passphrase ->
                    onConfirmEncrypt(req, plaintext, recipientFp, signerFp, passphrase)
                },
                onDismiss = onDismissEncrypt,
            )
        }

        state.pgp.decryptPassphraseRequest?.let { req ->
            DecryptPassphraseSheet(
                request = req,
                onSubmitPassphrase = { passphrase -> onSubmitDecryptPassphrase(req, passphrase) },
                onDismiss = onDismissDecryptPassphrase,
            )
        }

        state.pgp.plaintext?.let { plaintext ->
            PlaintextSheet(
                plaintext = plaintext,
                onCopy = onCopyPlaintext,
                onInsert = onInsertPlaintext,
                onClose = onDismissPlaintext,
            )
        }

        state.contextMenu?.let { menu ->
            ContextMenuSheet(
                menu = menu,
                onOpenInNewTab = onContextMenuOpenNewTab,
                onOpenInCurrentTab = onContextMenuOpenCurrentTab,
                onCopyUrl = onCopyText,
                onDecryptSelection = onDecryptArmoredText,
                onImportKeySelection = { armored -> onImportKey(armored, "") },
                onEncryptSelection = onEncryptSelectedText,
                onDismiss = onDismissContextMenu,
            )
        }

        state.selectionPgp?.let { selection ->
            SelectionPgpSheet(
                selection = selection,
                onDecrypt = onDecryptArmoredText,
                onImportKey = { armored -> onImportKey(armored, "") },
                onEncrypt = onEncryptSelectedText,
                onCopy = onCopyText,
                onDismiss = onDismissSelectionPgp,
            )
        }

        if (legalOpen) {
            LegalNoticesDialog(
                documents = state.legalDocuments,
                onOpenDocument = { doc ->
                    legalOpen = false
                    legalDocument = doc
                },
                onDismiss = { legalOpen = false },
            )
        }

        legalDocument?.let { doc ->
            LegalDocumentDialog(
                document = doc,
                loadLines = { onLoadLegalLines(doc.name) },
                onDismiss = { legalDocument = null },
            )
        }
    }
}

/**
 * Strictly one-row Bottom Control Dock (`44.dp` control row inside `horizontal = 8.dp, vertical = 6.dp`
 * padding = `56.dp` dock content height before system navigation/IME insets):
 * - Focused: `[ Cancel (40dp) · OvUnifiedAddressCapsule (weight 1f) ]`
 * - Unfocused (`< 560.dp`): `[ Back (40dp) · OvUnifiedAddressCapsule (weight 1f) · Tabs (40dp) · Menu (40dp) ]`
 * - Unfocused (`>= 560.dp`): `[ Back (44dp) · Forward (44dp) · OvUnifiedAddressCapsule (weight 1f) · Tabs (44dp) · Menu (44dp) ]`
 */
@Composable
private fun BottomControlDock(
    state: BrowserUiState,
    address: String,
    onAddressChange: (String) -> Unit,
    onSubmitAddress: () -> Unit,
    onClearAddress: () -> Unit,
    onCancelAddressEdit: () -> Unit,
    onSecurityClick: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val colors = TrapTheme.colors
    val focusManager = LocalFocusManager.current
    var addressFocused by remember { mutableStateOf(false) }
    val addressHint = stringResource(R.string.address_hint)

    val reloadRotation = if (state.isLoading) {
        val transition = rememberInfiniteTransition(label = "reload_spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "reload_angle",
        )
        angle
    } else {
        0f
    }

    val topBorderColor = colors.cardBorder
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding()
            .imePadding()
            .drawBehind {
                val strokePx = 1.dp.toPx()
                drawLine(
                    color = topBorderColor,
                    start = Offset(0f, strokePx / 2f),
                    end = Offset(size.width, strokePx / 2f),
                    strokeWidth = strokePx,
                )
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("bottom_control_dock"),
    ) {
        val isWide = maxWidth >= 560.dp
        val navCircleSize = if (isWide) 44.dp else 40.dp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (addressFocused) {
                OvNavCircleButton(
                    glyph = TrapGlyph.Close,
                    contentDescription = "Cancel editing address",
                    accent = colors.textMuted,
                    size = 40.dp,
                    onClick = {
                        onCancelAddressEdit()
                        focusManager.clearFocus()
                    },
                    testTag = "address_cancel",
                )
            } else {
                OvNavCircleButton(
                    glyph = TrapGlyph.Back,
                    contentDescription = "Back",
                    enabled = state.canGoBack,
                    accent = colors.amber,
                    size = navCircleSize,
                    onClick = onBack,
                    testTag = "nav_back",
                )
                if (isWide) {
                    OvNavCircleButton(
                        glyph = TrapGlyph.Forward,
                        contentDescription = "Forward",
                        enabled = state.canGoForward,
                        accent = colors.amber,
                        size = 44.dp,
                        onClick = onForward,
                        testTag = "nav_forward",
                    )
                }
            }

            key("unified_address_capsule") {
                OvUnifiedAddressCapsule(
                    address = address,
                    onAddressChange = onAddressChange,
                    onGo = onSubmitAddress,
                    onClear = onClearAddress,
                    onSecurityClick = onSecurityClick,
                    onReload = onReload,
                    isFocused = addressFocused,
                    onFocusChange = { addressFocused = it },
                    torReady = state.tor.ready,
                    isStartPage = state.isStartPage,
                    isLoading = state.isLoading,
                    reloadRotation = reloadRotation,
                    blocked = state.blocked,
                    hint = addressHint,
                    modifier = Modifier.weight(1f),
                )
            }

            if (!addressFocused) {
                OvNavCircleButton(
                    glyph = TrapGlyph.Tabs,
                    contentDescription = "Tabs",
                    accent = colors.teal,
                    size = navCircleSize,
                    badge = state.tabCount,
                    onClick = onOpenTabs,
                    testTag = "nav_tabs",
                )
                OvNavCircleButton(
                    glyph = TrapGlyph.Menu,
                    contentDescription = "Menu",
                    accent = colors.teal,
                    size = navCircleSize,
                    onClick = onOpenMenu,
                    testTag = "nav_menu",
                )
            }
        }
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
    kind in EDITABLE_FIELD_KINDS || isEditablePubKeyTarget()
