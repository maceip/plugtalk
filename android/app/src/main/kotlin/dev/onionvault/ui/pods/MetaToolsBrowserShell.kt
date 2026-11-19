package dev.onionvault.ui.pods

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.onionvault.OvNative
import dev.onionvault.platform.SurfaceFilterMode
import dev.onionvault.ui.BrowserUiState
import dev.onionvault.ui.ClearnetPromptState
import dev.onionvault.ui.DecryptPassphraseRequestState
import dev.onionvault.ui.EncryptRequestState
import dev.onionvault.ui.FindInPageState
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.PgpPlaintextState
import dev.onionvault.ui.TrapGlyph
import dev.onionvault.ui.TrapIcon
import dev.onionvault.ui.TrapThemeFamily
import dev.onionvault.ui.TrapThemeMode
import dev.onionvault.ui.TrustedOnionDirectorySheet

/**
 * Top-level Zero-Chrome 1997 MetaTools Floating Hardware Pods Orchestrator (`metatools_browser_shell`) —
 * Exact 1:1 Spatial Layout matching `metatools-reference.jpg`:
 *
 * - **Bottom-Left (`tor_orb_pod`)**: `TorKnurledOrbPod` (`104dp` purple orb — ONLY ONE ROUND BALL!).
 * - **Left Above Purple Orb (`kpt_back_pod`)**: `KptBackButtonPod` (`64dp x 42dp` rectangular
 *   chamfered cast-pewter `◀` tactile switch — strictly non-round!).
 * - **Center-Lower (`crt_address_pod`)**: `RecessedCrtAddressPod` (`156dp x 130dp` chunky `~5:4`
 *   brushed-pewter CRT TV floating over the typewriter zine sheet).
 * - **Mid-Right (`pgp_rocker_pod`)**: `+20 deg` clockwise translucent red switchblade
 *   (`1 click = New Tab`, `2 clicks = OpenPGP Keyring + Page PGP Actions`, upward pull = quick decrypt).
 * - **Bottom-Right (`side_remote_pod` / `side_remote_collapsed_face`)**: `+20 deg` clockwise
 *   `ZK "TOR"` LED remote with live 2x5 LED glow + `"Onion Sites"` directory mode.
 * - **Perimeter (`perimeter_steel_rails`)**: `12dp` brushed-steel vertical rails + outer spray-paint
 *   accents (`100%` touch passthrough).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetaToolsBrowserShell(
    state: BrowserUiState,
    modifier: Modifier = Modifier,
    podsRetracted: Boolean = state.podsRetracted,
    onSetPodsRetracted: (Boolean) -> Unit = {},
    clipboardTextOverride: String? = null,
    imeVisibleOverride: Boolean? = null,
    initialCrtEditing: Boolean = false,
    initialRemoteExpanded: Boolean = false,
    initialRemoteMode: RemoteMode = RemoteMode.Menu,
    openTorCircuitTrigger: Int = 0,
    showTabsModal: Boolean = false,
    showKeyringModal: Boolean = false,
    showSettingsModal: Boolean = false,
    showLegalModal: Boolean = false,
    showMoneroWalletModal: Boolean = false,
    showBtcXmrSwapModal: Boolean = false,
    showXmr402Modal: Boolean = false,
    onNavigate: (String) -> Unit = {},
    onSwitchAdjacentTab: (delta: Int) -> Unit = {},
    onSelectTab: (String) -> Unit = {},
    onCloseTab: (String) -> Unit = {},
    onNewTab: () -> Unit = {},
    onNewPrivateTab: () -> Unit = {},
    onBack: () -> Unit = {},
    onForward: () -> Unit = {},
    onReload: () -> Unit = {},
    onHome: () -> Unit = {},
    onZoomIn: () -> Unit = {},
    onZoomOut: () -> Unit = {},
    onOpenFindInPage: () -> Unit = {},
    onFindInPageQueryChange: (String) -> Unit = {},
    onFindInPageStep: (Int) -> Unit = {},
    onCloseFindInPage: () -> Unit = {},
    onAllowClearnet: (prompt: ClearnetPromptState, pattern: String, ttlSeconds: Long) -> Unit = { _, _, _ -> },
    onBlockClearnet: (prompt: ClearnetPromptState) -> Unit = {},
    onRevokeClearnetRule: (String) -> Unit = {},
    onClearClearnetRules: () -> Unit = {},
    onToggleRouting: (Boolean) -> Unit = {},
    onResetTorIsolation: () -> Unit = {},
    onFieldAction: (PgpFieldState) -> Unit = {},
    onInsertMyPubKey: (PgpFieldState) -> Unit = {},
    onQuickVerifyOrDecrypt: (() -> Unit)? = null,
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
    onGenerateKey: (userId: String, passphrase: String) -> Unit = { _, _ -> },
    onImportKey: (armored: String, passphrase: String) -> Unit = { _, _ -> },
    onExportKey: (String) -> Unit = {},
    onSetDefaultKey: (String) -> Unit = {},
    onDeleteKey: (String) -> Unit = {},
    onToggleRequireBiometric: (Boolean) -> Unit = {},
    onTogglePipOnMinimize: (Boolean) -> Unit = {},
    onTogglePipExcludePrivateTabs: (Boolean) -> Unit = {},
    onSelectSurfaceFilterMode: (SurfaceFilterMode) -> Unit = {},
    onToggleHandoff: (Boolean) -> Unit = {},
    onSelectThemeFamily: (TrapThemeFamily) -> Unit = {},
    onSelectThemeMode: (TrapThemeMode) -> Unit = {},
    onSelectDefaultZoom: (Int) -> Unit = {},
    onClearSession: () -> Unit = {},
    onContextMenuOpenNewTab: (String) -> Unit = {},
    onContextMenuOpenCurrentTab: (String) -> Unit = {},
    onCopyText: (String) -> Unit = {},
    onDecryptArmoredText: (String) -> Unit = {},
    onEncryptSelectedText: (String) -> Unit = {},
    onDismissContextMenu: () -> Unit = {},
    onDismissSelectionPgp: () -> Unit = {},
    onOverlayOpenChanged: (Boolean) -> Unit = {},
    onLoadLegalLines: suspend (String) -> List<String> = { emptyList() },
    onOpenMoneroWallet: () -> Unit = {},
    onDismissMoneroWallet: () -> Unit = {},
    onOpenBtcXmrSwap: () -> Unit = {},
    onDismissBtcXmrSwap: () -> Unit = {},
    onCreateXmrWallet: (label: String) -> Unit = {},
    onRestoreXmrWallet: (mnemonic: String, restoreHeight: Long) -> Unit = { _, _ -> },
    onSyncXmrWallet: () -> Unit = {},
    onNewXmrSubaddress: (label: String) -> Unit = {},
    onSendXmr: (destinationAddress: String, amountXmr: String, sweepAll: Boolean) -> Unit = { _, _, _ -> },
    onSetXmrDaemonEndpoint: (endpointUrl: String, mode: String) -> Unit = { _, _ -> },
    onRevealXmrSeed: () -> Unit = {},
    onHideXmrSeed: () -> Unit = {},
    onDismissNewXmrSeedBackup: () -> Unit = {},
    onDeleteXmrWallet: () -> Unit = {},
    onFetchBtcSwapQuotes: (btcAmount: String) -> Unit = {},
    onLaunchBtcSwapQuoteInBrowser: (quote: OvNative.BtcSwapQuoteItem, btcAmount: String) -> Unit = { _, _ -> },
    onOpenTrackedSwapPage: (OvNative.TrackedBtcXmrSwap) -> Unit = {},
    onDismissTrackedSwap: (swapId: String) -> Unit = {},
    onDismissActiveSwapHud: (swapId: String) -> Unit = {},
    onCopySiteSubaddress: (pageUrl: String) -> Unit = {},
    onProveXmrTx: (txid: String, recipientAddress: String, message: String) -> Unit = { _, _, _ -> },
    onOpenXmr402Payment: () -> Unit = {},
    onDismissXmr402Payment: () -> Unit = {},
    onPayAndUnlockXmr402: (OvNative.Xmr402Challenge) -> Unit = {},
    onUseXmr402PageDaemon: (daemonUrl: String) -> Unit = {},
    onSendXmrFromContextMenu: (addressOrUri: String, amountHint: String) -> Unit = { _, _ -> },
    surface: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val chromeHiddenForPip = state.isInPipMode || state.isPipChromeHidden

    var crtEditing by remember(initialCrtEditing) { mutableStateOf(initialCrtEditing) }
    var remoteExpanded by remember(initialRemoteExpanded) { mutableStateOf(initialRemoteExpanded) }
    var remoteMode by remember(initialRemoteMode) { mutableStateOf(initialRemoteMode) }
    var tabsOpen by remember(showTabsModal) { mutableStateOf(showTabsModal) }
    var keyringOpen by remember(showKeyringModal) { mutableStateOf(showKeyringModal) }
    var settingsOpen by remember(showSettingsModal) { mutableStateOf(showSettingsModal) }
    var legalOpen by remember(showLegalModal) { mutableStateOf(showLegalModal) }
    var localWalletOpen by remember(showMoneroWalletModal) { mutableStateOf(showMoneroWalletModal) }
    var localSwapOpen by remember(showBtcXmrSwapModal) { mutableStateOf(showBtcXmrSwapModal) }
    var localXmr402Open by remember(showXmr402Modal) { mutableStateOf(showXmr402Modal) }
    var localDismissedHudIds by remember { mutableStateOf(emptySet<String>()) }

    val active402Challenge = state.xmr.challengeForTab(state.activeTabId)
    val walletModalOpen = localWalletOpen || state.xmr.walletSheetOpen
    val swapModalOpen = localSwapOpen || state.xmr.swapSheetOpen
    val xmr402ModalOpen = active402Challenge != null &&
        !walletModalOpen &&
        !swapModalOpen &&
        (localXmr402Open || state.xmr.xmr402SheetOpen)

    LaunchedEffect(active402Challenge) {
        if (active402Challenge == null) {
            localXmr402Open = false
        }
    }

    var retracted by remember(podsRetracted) { mutableStateOf(podsRetracted) }

    fun restoreRetractedPods() {
        retracted = false
        onSetPodsRetracted(false)
    }

    LaunchedEffect(chromeHiddenForPip) {
        if (chromeHiddenForPip) {
            crtEditing = false
            remoteExpanded = false
            tabsOpen = false
            keyringOpen = false
            settingsOpen = false
            legalOpen = false
            localWalletOpen = false
            localSwapOpen = false
            localXmr402Open = false
        }
    }

    LaunchedEffect(openTorCircuitTrigger) {
        if (openTorCircuitTrigger > 0 && !chromeHiddenForPip) {
            restoreRetractedPods()
            remoteMode = RemoteMode.TorCircuit
            remoteExpanded = true
        }
    }

    LaunchedEffect(state.contextMenu) {
        if (state.contextMenu != null) {
            restoreRetractedPods()
            remoteMode = RemoteMode.ContextMenu
            remoteExpanded = true
        } else if (remoteMode == RemoteMode.ContextMenu) {
            remoteExpanded = false
            remoteMode = RemoteMode.Menu
        }
    }

    LaunchedEffect(state.selectionPgp) {
        if (state.selectionPgp != null) {
            restoreRetractedPods()
            remoteMode = RemoteMode.PgpActions
            remoteExpanded = true
        } else if (remoteMode == RemoteMode.PgpActions && state.pgp.fields.isEmpty()) {
            remoteExpanded = false
            remoteMode = RemoteMode.Menu
        }
    }

    LaunchedEffect(retracted) {
        if (retracted && remoteExpanded) {
            remoteExpanded = false
        }
    }

    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeOpen = imeVisibleOverride ?: (imeBottomPx > 0)
    val hideClusterForImeOrEdit = crtEditing || imeOpen || state.findInPage.visible

    val hasPgpContent = remember(state.pgp, state.selectionPgp, state.contextMenu, clipboardTextOverride) {
        detectContextualPgpPresence(
            pgp = state.pgp,
            selectionPgp = state.selectionPgp,
            contextMenu = state.contextMenu,
            clipboardText = clipboardTextOverride,
        )
    }
    val hasArmoredBlock = remember(state.pgp, state.selectionPgp, state.contextMenu, clipboardTextOverride) {
        detectArmoredBlockPresent(
            pgp = state.pgp,
            selectionPgp = state.selectionPgp,
            contextMenu = state.contextMenu,
            clipboardText = clipboardTextOverride,
        )
    }

    val anyOverlayOpen = !chromeHiddenForPip && (
        crtEditing ||
            state.findInPage.visible ||
            remoteExpanded ||
            tabsOpen ||
            keyringOpen ||
            settingsOpen ||
            legalOpen ||
            walletModalOpen ||
            swapModalOpen ||
            xmr402ModalOpen ||
            state.clearnetPrompt != null ||
            state.pgp.plaintext != null ||
            state.pgp.encryptRequest != null ||
            state.pgp.decryptPassphraseRequest != null
        )

    LaunchedEffect(anyOverlayOpen) {
        onOverlayOpenChanged(anyOverlayOpen)
    }

    // 110ms Mechanical Retraction Animations during active downward webpage scroll
    val crtRetractOffsetY by animateDpAsState(
        targetValue = if (retracted && !hideClusterForImeOrEdit) 96.dp else 0.dp,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.POD_SNAP_DURATION_MS,
            easing = MetaToolsTokens.PodSnapEasing,
        ),
        label = "crt_retract_y",
    )

    val leftPodScale by animateFloatAsState(
        targetValue = if (retracted && !hideClusterForImeOrEdit) 0.78f else 1.0f,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.POD_SNAP_DURATION_MS,
            easing = MetaToolsTokens.PodSnapEasing,
        ),
        label = "left_pod_scale",
    )

    val remoteDockOffsetX by animateDpAsState(
        targetValue = if (!remoteExpanded && retracted) 50.dp else 0.dp,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.RIGHT_DOCK_SLIDE_DURATION_MS,
            easing = MetaToolsTokens.PodSnapEasing,
        ),
        label = "remote_dock_offset_x",
    )

    val pgpDockOffsetX by animateDpAsState(
        targetValue = if (retracted) 58.dp else 0.dp,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.RIGHT_DOCK_SLIDE_DURATION_MS,
            easing = MetaToolsTokens.PodSnapEasing,
        ),
        label = "pgp_dock_offset_x",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("metatools_browser_shell"),
    ) {
        // 1. Full-bleed Webpage / StartPage Surface
        Box(modifier = Modifier.fillMaxSize()) {
            surface()
        }

        if (!chromeHiddenForPip) {
            // 2. 12dp Brushed-Steel Vertical Rails + Outer Street Overspray (100% Hit-Test Passthrough!)
            PerimeterSteelRails(
                isLoading = state.isLoading,
                loadProgress = state.loadProgress,
                topInset = 0.dp,
            )

            // 3. Exact `metatools-reference.jpg` Spatial Hardware Pod Cluster
            if (crtEditing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xB8050608))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                focusManager.clearFocus()
                                crtEditing = false
                            },
                        )
                        .testTag("crt_editor_scrim"),
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        TrustedOnionDirectorySheet(
                            onSelectEntry = { entry ->
                                focusManager.clearFocus()
                                crtEditing = false
                                onNavigate(entry.url)
                            },
                        )
                    }

                    RecessedCrtAddressPod(
                        url = state.url,
                        isLoading = state.isLoading,
                        isEditing = true,
                        onEditingChange = { editing -> crtEditing = editing },
                        onNavigate = onNavigate,
                        onSwitchAdjacentTab = { delta ->
                            switchAdjacentTabFromList(state, delta, onSwitchAdjacentTab, onSelectTab)
                        },
                        onOpenTabSwitcher = { tabsOpen = true },
                        isImeVisibleOverride = imeVisibleOverride,
                    )
                }
            } else if (!hideClusterForImeOrEdit) {
                // 3a. Bottom-Left — ONLY ONE ROUND BALL (`TorKnurledOrbPod`, 104dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 8.dp, bottom = 52.dp),
                ) {
                    TorKnurledOrbPod(
                        torReady = state.tor.ready,
                        bootstrapPercent = state.tor.percent,
                        onToggleTorCircuitRemote = {
                            if (retracted) {
                                restoreRetractedPods()
                            } else if (remoteExpanded && remoteMode == RemoteMode.TorCircuit) {
                                remoteExpanded = false
                            } else {
                                remoteMode = RemoteMode.TorCircuit
                                remoteExpanded = true
                            }
                        },
                        onRotateTorIdentity = {
                            restoreRetractedPods()
                            onResetTorIsolation()
                        },
                        modifier = Modifier.graphicsLayer {
                            scaleX = leftPodScale
                            scaleY = leftPodScale
                            transformOrigin = TransformOrigin(0f, 1f)
                        },
                    )
                }

                // 3b. Left Above Purple Orb — Rectangular Chamfered Pewter `◀` Key (`KptBackButtonPod`, 62x40dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 22.dp, bottom = 168.dp),
                ) {
                    KptBackButtonPod(
                        canGoBack = state.canGoBack,
                        canGoForward = state.canGoForward,
                        canReturnToStartPage = !state.isStartPage,
                        isRetracted = retracted,
                        onRestoreRetracted = { restoreRetractedPods() },
                        onBack = onBack,
                        onReturnToStartPage = onHome,
                        onForward = {
                            restoreRetractedPods()
                            onForward()
                        },
                        modifier = Modifier.graphicsLayer {
                            scaleX = leftPodScale
                            scaleY = leftPodScale
                            transformOrigin = TransformOrigin(0f, 1f)
                        },
                    )
                }

                // 3c. Center-Lower — Chunky ~5:4 Brushed-Pewter CRT TV (`RecessedCrtAddressPod`, 156x135dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 132.dp)
                        .offset(x = (-4).dp, y = crtRetractOffsetY),
                ) {
                    RecessedCrtAddressPod(
                        url = state.url,
                        isLoading = state.isLoading,
                        isEditing = false,
                        onEditingChange = { editing ->
                            if (retracted) {
                                restoreRetractedPods()
                            } else {
                                remoteExpanded = false
                                crtEditing = editing
                            }
                        },
                        onNavigate = onNavigate,
                        onSwitchAdjacentTab = { delta ->
                            switchAdjacentTabFromList(state, delta, onSwitchAdjacentTab, onSelectTab)
                        },
                        onOpenTabSwitcher = {
                            restoreRetractedPods()
                            tabsOpen = true
                        },
                        isImeVisibleOverride = imeVisibleOverride,
                    )
                }

                // 3d. Mid-Right — +20 deg Clockwise Translucent Red Switchblade (`OpenPgpRockerPod`, 82x124dp)
                if (!remoteExpanded) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = 16.dp, bottom = 224.dp)
                            .offset(x = pgpDockOffsetX),
                    ) {
                        OpenPgpRockerPod(
                            hasPgpContent = hasPgpContent,
                            hasArmoredCiphertextOrKey = hasArmoredBlock,
                            onNewTab = {
                                restoreRetractedPods()
                                onNewTab()
                            },
                            onOpenPgpTools = {
                                restoreRetractedPods()
                                keyringOpen = true
                            },
                            onQuickVerifyOrDecrypt = {
                                restoreRetractedPods()
                                if (onQuickVerifyOrDecrypt != null) {
                                    onQuickVerifyOrDecrypt()
                                } else {
                                    val explicitClipText = clipboardTextOverride
                                        ?: runCatching { clipboard.getText()?.text }.getOrNull()
                                    triggerDefaultQuickPgpAction(
                                        state = state,
                                        clipboardText = explicitClipText,
                                        onFieldAction = onFieldAction,
                                        onDecryptArmoredText = onDecryptArmoredText,
                                        onImportKey = onImportKey,
                                        onOpenKeyring = { keyringOpen = true },
                                    )
                                }
                            },
                        )
                    }
                }

                // 3e. Bottom-Right — +20 deg Clockwise `ZK "TOR"` LED Remote (`PhotoSoapSideRemote`, 60x186dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = if (remoteExpanded) 10.dp else 22.dp, bottom = 18.dp)
                        .offset(x = remoteDockOffsetX),
                ) {
                    PhotoSoapSideRemote(
                        state = state,
                        expanded = remoteExpanded,
                        mode = remoteMode,
                        hasPgpContent = hasPgpContent,
                        onExpandMenu = {
                            if (retracted) {
                                restoreRetractedPods()
                            } else {
                                remoteMode = RemoteMode.Menu
                                remoteExpanded = true
                            }
                        },
                        onSelectMode = { nextMode -> remoteMode = nextMode },
                        onCollapse = {
                            remoteExpanded = false
                            if (remoteMode == RemoteMode.ContextMenu) onDismissContextMenu()
                            if (remoteMode == RemoteMode.PgpActions) onDismissSelectionPgp()
                        },
                        onNavigate = onNavigate,
                        onBack = onBack,
                        onForward = onForward,
                        onReload = onReload,
                        onNewTab = onNewTab,
                        onOpenTabs = { tabsOpen = true },
                        onCopyUrl = { onCopyText(state.url) },
                        onHome = onHome,
                        onOpenKeyring = { keyringOpen = true },
                        onOpenSettings = { settingsOpen = true },
                        onOpenLicences = { legalOpen = true },
                        onToggleHiddenServiceOnly = onToggleRouting,
                        onResetTorIsolation = onResetTorIsolation,
                        onRevokeClearnetRule = onRevokeClearnetRule,
                        onClearClearnetRules = onClearClearnetRules,
                        onFieldAction = onFieldAction,
                        onInsertMyPubKey = onInsertMyPubKey,
                        onContextMenuOpenNewTab = onContextMenuOpenNewTab,
                        onContextMenuOpenCurrentTab = onContextMenuOpenCurrentTab,
                        onCopyText = onCopyText,
                        onDecryptArmoredText = onDecryptArmoredText,
                        onImportArmoredKey = { armored -> onImportKey(armored, "") },
                        onEncryptSelectedText = onEncryptSelectedText,
                        onZoomIn = onZoomIn,
                        onZoomOut = onZoomOut,
                        onOpenFindInPage = {
                            restoreRetractedPods()
                            onOpenFindInPage()
                        },
                        onOpenMoneroWallet = {
                            restoreRetractedPods()
                            localSwapOpen = false
                            localXmr402Open = false
                            localWalletOpen = true
                            onOpenMoneroWallet()
                        },
                        onOpenBtcXmrSwap = {
                            restoreRetractedPods()
                            localWalletOpen = false
                            localXmr402Open = false
                            localSwapOpen = true
                            onOpenBtcXmrSwap()
                        },
                        onCreateXmrWallet = onCreateXmrWallet,
                        onCopySiteSubaddress = onCopySiteSubaddress,
                        onSyncXmrWallet = onSyncXmrWallet,
                        onOpenXmr402Payment = {
                            restoreRetractedPods()
                            localWalletOpen = false
                            localSwapOpen = false
                            localXmr402Open = true
                            onOpenXmr402Payment()
                        },
                        onSendXmrFromSelection = { addrOrUri, amountHint ->
                            restoreRetractedPods()
                            localSwapOpen = false
                            localXmr402Open = false
                            localWalletOpen = true
                            onSendXmrFromContextMenu(addrOrUri, amountHint)
                        },
                    )
                }

                // 3f. Compact Bottom-Docked Active Swap Browser HUD & XMR402 Reopen Chip (NEVER on StartPage!)
                val activeSwap = state.xmr.activeSwapForTab(state.activeTabId)
                    ?.takeIf { it.swapId !in localDismissedHudIds }
                val show402Banner = !state.isStartPage &&
                    !state.isScreenRecordingActive &&
                    active402Challenge != null &&
                    !xmr402ModalOpen &&
                    !walletModalOpen &&
                    !swapModalOpen
                val showSwapHud = !state.isStartPage &&
                    !state.isScreenRecordingActive &&
                    activeSwap != null
                if (show402Banner || showSwapHud) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = if (retracted) 18.dp else 354.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (show402Banner) {
                            Xmr402ReopenBanner(
                                challenge = active402Challenge,
                                onReopen = {
                                    restoreRetractedPods()
                                    localWalletOpen = false
                                    localSwapOpen = false
                                    localXmr402Open = true
                                    onOpenXmr402Payment()
                                },
                            )
                        }
                        if (showSwapHud) {
                            ActiveSwapBrowserHud(
                                swap = activeSwap,
                                syncing = state.xmr.syncing,
                                onCopyText = onCopyText,
                                onSyncPayout = onSyncXmrWallet,
                                onOpenWallet = {
                                    restoreRetractedPods()
                                    localSwapOpen = false
                                    localXmr402Open = false
                                    localWalletOpen = true
                                    onOpenMoneroWallet()
                                },
                                onDismiss = {
                                    localDismissedHudIds = localDismissedHudIds + activeSwap.swapId
                                    onDismissActiveSwapHud(activeSwap.swapId)
                                },
                            )
                        }
                    }
                }
            }

            if (state.findInPage.visible && !crtEditing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(bottom = if (imeOpen) 8.dp else 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    FindInPageHardwarePod(
                        findState = state.findInPage,
                        onQueryChange = onFindInPageQueryChange,
                        onStepMatch = onFindInPageStep,
                        onClose = onCloseFindInPage,
                    )
                }
            }

            // 4. Sculptural Hardware Modal Pods
            val managementModalOpen = tabsOpen ||
                keyringOpen ||
                settingsOpen ||
                legalOpen ||
                walletModalOpen ||
                swapModalOpen ||
                xmr402ModalOpen

            if (!managementModalOpen) {
                state.clearnetPrompt?.let { prompt ->
                    ClearnetConsentHardwarePod(
                        prompt = prompt,
                        onAllow = onAllowClearnet,
                        onBlock = onBlockClearnet,
                    )
                }
            }

            if (tabsOpen) {
                SpatialTabSwitcherPod(
                    tabs = state.tabs,
                    onSelectTab = onSelectTab,
                    onCloseTab = onCloseTab,
                    onNewTab = onNewTab,
                    onNewPrivateTab = onNewPrivateTab,
                    onDismiss = { tabsOpen = false },
                )
            }

            if (keyringOpen && !state.isScreenRecordingActive) {
                KeyringHardwarePod(
                    keys = state.keys,
                    defaultKeyFingerprint = state.defaultKeyFingerprint,
                    errorMessage = state.pgp.error,
                    onGenerateKey = onGenerateKey,
                    onImportKey = onImportKey,
                    onExportKey = onExportKey,
                    onSetDefaultKey = onSetDefaultKey,
                    onDeleteKey = onDeleteKey,
                    onDismiss = { keyringOpen = false },
                    pgpFields = state.pgp.fields,
                    selectionPgp = state.selectionPgp,
                    onFieldAction = onFieldAction,
                    onInsertMyPubKey = onInsertMyPubKey,
                    onDecryptArmoredText = onDecryptArmoredText,
                    onEncryptSelectedText = onEncryptSelectedText,
                )
            }

            if (walletModalOpen && !state.isScreenRecordingActive) {
                MoneroWalletHardwarePod(
                    xmr = state.xmr,
                    onCreateWallet = onCreateXmrWallet,
                    onRestoreWallet = onRestoreXmrWallet,
                    onSyncWallet = onSyncXmrWallet,
                    onNewSubaddress = onNewXmrSubaddress,
                    onSendXmr = onSendXmr,
                    onSetDaemonEndpoint = onSetXmrDaemonEndpoint,
                    onRevealSeed = onRevealXmrSeed,
                    onHideSeed = onHideXmrSeed,
                    onDismissNewSeedBackup = onDismissNewXmrSeedBackup,
                    onDeleteWallet = onDeleteXmrWallet,
                    onProveXmrTx = onProveXmrTx,
                    onOpenSwap = {
                        localWalletOpen = false
                        onDismissMoneroWallet()
                        localSwapOpen = true
                        onOpenBtcXmrSwap()
                    },
                    onCopyText = onCopyText,
                    onDismiss = {
                        localWalletOpen = false
                        onDismissMoneroWallet()
                    },
                )
            }

            if (swapModalOpen && !state.isScreenRecordingActive) {
                BtcToXmrSwapHardwarePod(
                    xmr = state.xmr,
                    onFetchQuotes = onFetchBtcSwapQuotes,
                    onLaunchQuoteInBrowser = { quote, amount ->
                        localSwapOpen = false
                        onDismissBtcXmrSwap()
                        onLaunchBtcSwapQuoteInBrowser(quote, amount)
                    },
                    onOpenTrackedSwapPage = { tracked ->
                        localSwapOpen = false
                        onDismissBtcXmrSwap()
                        onOpenTrackedSwapPage(tracked)
                    },
                    onDismissSwap = onDismissTrackedSwap,
                    onSyncWallet = onSyncXmrWallet,
                    onOpenWallet = {
                        localSwapOpen = false
                        onDismissBtcXmrSwap()
                        localWalletOpen = true
                        onOpenMoneroWallet()
                    },
                    onCopyText = onCopyText,
                    onDismiss = {
                        localSwapOpen = false
                        onDismissBtcXmrSwap()
                    },
                )
            }

            if (xmr402ModalOpen && !state.isScreenRecordingActive) {
                Xmr402PaymentHardwarePod(
                    challenge = active402Challenge,
                    xmr = state.xmr,
                    onPayAndUnlock = onPayAndUnlockXmr402,
                    onUsePageDaemon = onUseXmr402PageDaemon,
                    onOpenSwap = {
                        localXmr402Open = false
                        onDismissXmr402Payment()
                        localSwapOpen = true
                        onOpenBtcXmrSwap()
                    },
                    onSyncWallet = onSyncXmrWallet,
                    onCreateWallet = onCreateXmrWallet,
                    onCopyText = onCopyText,
                    onDismiss = {
                        localXmr402Open = false
                        onDismissXmr402Payment()
                    },
                )
            }

            if (settingsOpen) {
                SettingsHardwarePod(
                    state = state,
                    onSelectThemeFamily = onSelectThemeFamily,
                    onSelectThemeMode = onSelectThemeMode,
                    onToggleHiddenServiceOnly = onToggleRouting,
                    onToggleRequireBiometric = onToggleRequireBiometric,
                    onSelectDefaultZoom = onSelectDefaultZoom,
                    onRevokeClearnetRule = onRevokeClearnetRule,
                    onClearClearnetRules = onClearClearnetRules,
                    onTogglePipOnMinimize = onTogglePipOnMinimize,
                    onTogglePipExcludePrivateTabs = onTogglePipExcludePrivateTabs,
                    onSelectSurfaceFilterMode = onSelectSurfaceFilterMode,
                    onToggleHandoff = onToggleHandoff,
                    onClearSession = onClearSession,
                    onOpenLegalNotices = { legalOpen = true },
                    onDismiss = { settingsOpen = false },
                )
            }

            if (legalOpen) {
                LegalNoticesHardwarePod(
                    documents = state.legalDocuments,
                    onLoadLegalLines = onLoadLegalLines,
                    onDismiss = { legalOpen = false },
                )
            }

            if (!state.isScreenRecordingActive) {
                state.pgp.encryptRequest?.let { req ->
                    EncryptRecipientHardwarePod(
                        request = req,
                        keys = state.keys,
                        defaultKeyFingerprint = state.defaultKeyFingerprint,
                        errorMessage = state.pgp.error,
                        onImportKey = onImportKey,
                        onOpenKeyring = { keyringOpen = true },
                        onConfirmEncrypt = { plaintext, recipientFp, signerFp, passphrase ->
                            onConfirmEncrypt(req, plaintext, recipientFp, signerFp, passphrase)
                        },
                        onDismiss = onDismissEncrypt,
                    )
                }

                state.pgp.decryptPassphraseRequest?.let { req ->
                    PassphraseUnlockHardwarePod(
                        request = req,
                        onSubmitPassphrase = { passphrase -> onSubmitDecryptPassphrase(req, passphrase) },
                        onDismiss = onDismissDecryptPassphrase,
                    )
                }

                state.pgp.plaintext?.let { plaintext ->
                    DecryptedPlaintextHardwarePod(
                        plaintext = plaintext,
                        onCopy = onCopyPlaintext,
                        onInsert = onInsertPlaintext,
                        onClose = onDismissPlaintext,
                    )
                }
            }
        }
    }
}

@Composable
private fun FindInPageHardwarePod(
    findState: FindInPageState,
    onQueryChange: (String) -> Unit,
    onStepMatch: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    val shape = RoundedCornerShape(18.dp)
    val countLabel = when {
        findState.query.isBlank() -> "0/0"
        findState.matchCount <= 0 -> "0/0"
        else -> "${findState.activeIndex.coerceAtLeast(1)}/${findState.matchCount}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .metaToolsDropShadow(cornerRadius = 18.dp)
            .clip(shape)
            .background(MetaToolsTokens.CrtBlackGlass)
            .border(1.5.dp, MetaToolsTokens.GrooveHighlight, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("find_in_page_pod"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TrapIcon(
            glyph = TrapGlyph.Search,
            tint = MetaToolsTokens.PhosphorClearnetGreen,
            size = 18.dp,
            forceVector = true,
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (findState.query.isEmpty()) {
                Text(
                    text = "Search text on page…",
                    color = MetaToolsTokens.PhosphorPathDim,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = findState.query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = MetaToolsTokens.PhosphorOnionGreen,
                    fontSize = 13.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(MetaToolsTokens.PhosphorOnionGreen),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { onStepMatch(1) },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag("find_in_page_input"),
            )
        }
        Text(
            text = countLabel,
            color = if (findState.matchCount > 0) {
                MetaToolsTokens.PhosphorClearnetGreen
            } else {
                MetaToolsTokens.StencilInk
            },
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("find_in_page_count"),
        )
        FindPodMiniButton(
            label = "▲",
            enabled = findState.matchCount > 0,
            onClick = { onStepMatch(-1) },
            testTag = "find_in_page_prev",
        )
        FindPodMiniButton(
            label = "▼",
            enabled = findState.matchCount > 0,
            onClick = { onStepMatch(1) },
            testTag = "find_in_page_next",
        )
        FindPodMiniButton(
            label = "✕",
            enabled = true,
            onClick = onClose,
            testTag = "find_in_page_close",
        )
    }
}

@Composable
private fun FindPodMiniButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(shape)
            .background(MetaToolsTokens.AnodizedRecessedWell)
            .border(1.dp, MetaToolsTokens.GrooveHighlight, shape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) Color.White else MetaToolsTokens.PhosphorPathDim,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun switchAdjacentTabFromList(
    state: BrowserUiState,
    delta: Int,
    onSwitchAdjacentTab: (Int) -> Unit,
    onSelectTab: (String) -> Unit,
) {
    onSwitchAdjacentTab(delta)
    if (state.tabs.size > 1) {
        val currentIdx = state.tabs.indexOfFirst { it.id == state.activeTabId || it.isActive }
            .coerceAtLeast(0)
        val nextIdx = (currentIdx + delta).mod(state.tabs.size)
        onSelectTab(state.tabs[nextIdx].id)
    }
}

private fun triggerDefaultQuickPgpAction(
    state: BrowserUiState,
    clipboardText: String?,
    onFieldAction: (PgpFieldState) -> Unit,
    onDecryptArmoredText: (String) -> Unit,
    onImportKey: (String, String) -> Unit,
    onOpenKeyring: () -> Unit,
) {
    val primaryArmored = state.pgp.primaryArmoredBlock
    if (primaryArmored != null) {
        onFieldAction(primaryArmored)
        return
    }
    val editableField = state.pgp.editablePgpFields.firstOrNull()
    if (editableField != null) {
        onFieldAction(editableField)
        return
    }
    val sel = state.selectionPgp
    if (sel != null && sel.kind == "pgp_message") {
        onDecryptArmoredText(sel.armored.ifBlank { sel.selectedText })
        return
    }
    if (!clipboardText.isNullOrBlank()) {
        when {
            clipboardText.contains("-----BEGIN PGP MESSAGE-----") ||
                clipboardText.contains("-----BEGIN PGP SIGNED MESSAGE-----") -> {
                onDecryptArmoredText(clipboardText)
                return
            }

            clipboardText.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----") -> {
                onImportKey(clipboardText, "")
                return
            }
        }
    }
    onOpenKeyring()
}
