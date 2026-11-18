package dev.onionvault.ui.pods

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.onionvault.platform.SurfaceFilterMode
import dev.onionvault.ui.BrowserUiState
import dev.onionvault.ui.ClearnetPromptState
import dev.onionvault.ui.DecryptPassphraseRequestState
import dev.onionvault.ui.EncryptRequestState
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.PgpPlaintextState
import dev.onionvault.ui.TrapThemeFamily
import dev.onionvault.ui.TrapThemeMode

/**
 * Top-level `1.5x` Scaled Zero-Chrome Floating Hardware Pods Orchestrator (`metatools_browser_shell`).
 *
 * Implements Browser-First Ergonomic Arrangement:
 * - Full-bleed webpage [surface] with ZERO toolbar strips and ZERO backdrop blur; touches in all
 *   open gaps, rails, splatters, drips, and cast shadows pass straight through to [surface]!
 * - `4.5.dp` [PerimeterSteelRails] with solid `3.dp` acid-yellow (`#D8FF00`) top ridge load pulse.
 * - **Bottom Primary Browser Row** (`10.dp` above `navigationBarsPadding()`, `8.dp` horizontal
 *   screen padding, `8.dp` open webpage gaps between all 3 pods):
 *   `[ KptBackButtonPod (74dp) ]  (8dp)  [ RecessedCrtAddressPod (weight 1f × 74dp) ]  (8dp)  [ TorKnurledOrbPod (86dp) ]`
 * - **Right-Bezel KPT Tool Dock** above `TorKnurledOrbPod` (`12.dp` vertical gaps, zero collision):
 *   `OpenPgpRockerPod` (`80.dp x 92.dp`, `-8deg` tilt) + `PhotoSoapSideRemote` (`70.dp x 154.dp`).
 *   - On Homepage (`state.isStartPage`) or when pods are summoned: docked cleanly at `100%` along
 *     the right edge above the Tor Orb.
 *   - On live webpages (`!state.isStartPage`) when `podsRetracted == true` (scrolling) OR when no
 *     active PGP block is present, auto-tucks `72%` into the right bezel so only the glowing
 *     `10.5.dp` LED bead column / ruby edge peeks out — tapping slides right out to `100%` in
 *     `120ms`, and when `hasPgpContent == true`, `OpenPgpRockerPod` automatically slides out!
 * - **IME & CRT Edit Choreography** (`imeOpen || crtEditing`):
 *   - Hides Back Pod, Tor Orb, PGP Switch & Side Remote completely, docking CRT Monitor `8.dp`
 *     above IME keyboard at `95%` viewport width (`68.dp` height, `17.sp` input)!
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
    initialRemoteExpanded: Boolean = false,
    initialRemoteMode: RemoteMode = RemoteMode.Menu,
    openTorCircuitTrigger: Int = 0,
    showTabsModal: Boolean = false,
    showKeyringModal: Boolean = false,
    showSettingsModal: Boolean = false,
    showLegalModal: Boolean = false,
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
    surface: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val chromeHiddenForPip = state.isInPipMode || state.isPipChromeHidden

    var crtEditing by remember { mutableStateOf(false) }
    var remoteExpanded by remember(initialRemoteExpanded) { mutableStateOf(initialRemoteExpanded) }
    var remoteMode by remember(initialRemoteMode) { mutableStateOf(initialRemoteMode) }
    var tabsOpen by remember(showTabsModal) { mutableStateOf(showTabsModal) }
    var keyringOpen by remember(showKeyringModal) { mutableStateOf(showKeyringModal) }
    var settingsOpen by remember(showSettingsModal) { mutableStateOf(showSettingsModal) }
    var legalOpen by remember(showLegalModal) { mutableStateOf(showLegalModal) }

    var retracted by remember(podsRetracted) { mutableStateOf(podsRetracted) }
    var wasRetracted by remember { mutableStateOf(podsRetracted) }
    var rightDockSummoned by remember(state.url) { mutableStateOf(false) }

    LaunchedEffect(podsRetracted) {
        if (podsRetracted) {
            rightDockSummoned = false
        } else if (wasRetracted) {
            // Upward flick after scrolling summons the full hardware tool dock!
            rightDockSummoned = true
        }
        wasRetracted = podsRetracted
    }

    fun restoreRetractedPods() {
        retracted = false
        rightDockSummoned = true
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
    val hideClusterForImeOrEdit = crtEditing || imeOpen

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
            remoteExpanded ||
            tabsOpen ||
            keyringOpen ||
            settingsOpen ||
            legalOpen ||
            state.clearnetPrompt != null ||
            state.pgp.plaintext != null ||
            state.pgp.encryptRequest != null ||
            state.pgp.decryptPassphraseRequest != null
        )

    LaunchedEffect(anyOverlayOpen) {
        onOverlayOpenChanged(anyOverlayOpen)
    }

    // 110ms Mechanical Retraction Animations for Bottom Primary Row
    val crtRetractOffsetY by animateDpAsState(
        targetValue = if (retracted && !hideClusterForImeOrEdit) 96.dp else 0.dp,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.POD_SNAP_DURATION_MS,
            easing = MetaToolsTokens.PodSnapEasing,
        ),
        label = "crt_retract_y",
    )

    val cornerPodScale by animateFloatAsState(
        targetValue = if (retracted && !hideClusterForImeOrEdit) 0.75f else 1.0f,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.POD_SNAP_DURATION_MS,
            easing = MetaToolsTokens.PodSnapEasing,
        ),
        label = "corner_pod_scale",
    )

    // 120ms Right-Bezel KPT Tool Dock Auto-Tuck (72% into right bezel on live webpages unless summoned / PGP active)
    val remoteShouldTuck = !remoteExpanded && (
        retracted || (!state.isStartPage && !rightDockSummoned)
        )
    // 72% of 70dp = 50.4dp -> +50.dp off right bezel so glowing 10.5dp green LED column peeks out
    val remoteDockOffsetX by animateDpAsState(
        targetValue = if (remoteShouldTuck) 50.dp else 0.dp,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.RIGHT_DOCK_SLIDE_DURATION_MS,
            easing = MetaToolsTokens.PodSnapEasing,
        ),
        label = "remote_dock_offset_x",
    )

    val pgpShouldTuck = retracted || (
        !state.isStartPage && !hasPgpContent && !rightDockSummoned
        )
    // 72% of 80dp = 57.6dp -> +58.dp off right bezel so glowing ruby polycarbonate edge peeks out
    val pgpDockOffsetX by animateDpAsState(
        targetValue = if (pgpShouldTuck) 58.dp else 0.dp,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.RIGHT_DOCK_SLIDE_DURATION_MS,
            easing = MetaToolsTokens.PodSnapEasing,
        ),
        label = "pgp_dock_offset_x",
    )

    val topStatusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("metatools_browser_shell"),
    ) {
        // 1. Full-bleed Webpage / StartPage Surface (100% unobstructed edge-to-edge)
        Box(modifier = Modifier.fillMaxSize()) {
            surface()
        }

        if (!chromeHiddenForPip) {
            // 2. 4.5dp Cylindrical Brushed-Steel Frame + Street Overspray (100% Hit-Test Passthrough!)
            PerimeterSteelRails(
                isLoading = state.isLoading,
                loadProgress = state.loadProgress,
                topInset = topStatusBarInset,
            )

            // 3. Right-Bezel KPT Tool Dock above TorKnurledOrbPod (hidden while CRT editing / IME open)
            if (!hideClusterForImeOrEdit) {
                // 3a. OpenPgpRockerPod (80x92dp, -8deg): docked cleanly above 86dp Tor Orb (>= 12dp visual & unclipped gap)
                //     Hidden while Side Remote drawer is expanded to 268dp so they never overlap!
                if (!remoteExpanded) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = 6.dp, bottom = 116.dp)
                            .offset(x = pgpDockOffsetX),
                    ) {
                        OpenPgpRockerPod(
                            hasPgpContent = hasPgpContent,
                            hasArmoredCiphertextOrKey = hasArmoredBlock,
                            onOpenPgpTools = {
                                if (retracted) {
                                    restoreRetractedPods()
                                } else {
                                    rightDockSummoned = true
                                    if (state.pgp.fields.isNotEmpty() || state.selectionPgp != null) {
                                        remoteMode = RemoteMode.PgpActions
                                        remoteExpanded = true
                                    } else {
                                        keyringOpen = true
                                    }
                                }
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

                // 3b. PhotoSoapSideRemote (70x154dp collapsed above PGP Rocker at 230dp; 268dp wide at 104dp when expanded)
                val remoteBottomPadding = if (remoteExpanded) 104.dp else 230.dp
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 6.dp, bottom = remoteBottomPadding)
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
                                rightDockSummoned = true
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
                    )
                }
            }

            // 4. Bottom Primary Browser Row (10dp above navigationBarsPadding(), 8dp horizontal padding, 8dp gaps)
            if (crtEditing) {
                // Docked 8dp above IME keyboard at 95% viewport width (68dp height, 17sp input text)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
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
            } else if (!imeOpen) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
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
                            scaleX = cornerPodScale
                            scaleY = cornerPodScale
                            transformOrigin = TransformOrigin(0f, 1f)
                        },
                    )

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
                        modifier = Modifier
                            .weight(1f)
                            .offset(y = crtRetractOffsetY),
                    )

                    TorKnurledOrbPod(
                        torReady = state.tor.ready,
                        bootstrapPercent = state.tor.percent,
                        onToggleTorCircuitRemote = {
                            if (retracted) {
                                restoreRetractedPods()
                            } else if (remoteExpanded && remoteMode == RemoteMode.TorCircuit) {
                                remoteExpanded = false
                            } else {
                                rightDockSummoned = true
                                remoteMode = RemoteMode.TorCircuit
                                remoteExpanded = true
                            }
                        },
                        onRotateTorIdentity = {
                            restoreRetractedPods()
                            onResetTorIsolation()
                        },
                        modifier = Modifier.graphicsLayer {
                            scaleX = cornerPodScale
                            scaleY = cornerPodScale
                            transformOrigin = TransformOrigin(1f, 1f)
                        },
                    )
                }
            }

            // 5. Sculptural Hardware Modal Pods
            val managementModalOpen = tabsOpen || keyringOpen || settingsOpen || legalOpen

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
