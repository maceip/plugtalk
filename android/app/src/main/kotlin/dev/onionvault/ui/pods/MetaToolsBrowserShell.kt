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
 * Top-level Zero-Chrome Floating Hardware Pods Orchestrator (`metatools_browser_shell`).
 *
 * Implements Spec Section 1–5:
 * - Full-bleed webpage [surface] with ZERO toolbar strips and ZERO backdrop blur; touches in all
 *   open gaps, rails, splatters, drips, and cast shadows pass straight through to [surface]!
 * - `3.dp` [PerimeterSteelRails] with solid `2.dp` acid-yellow (`#D8FF00`) top ridge load pulse.
 * - Detached Bottom Tri-Pod Cluster (`10.dp` above `navigationBarsPadding()`, open `12.dp`
 *   webpage gaps between [TorKnurledOrbPod], [RecessedCrtAddressPod], and [OpenPgpRockerPod]).
 * - Detached right-edge [PhotoSoapSideRemote] floating above the PGP rocker switch.
 * - **Scroll Retraction Choreography** ([podsRetracted] == `true`, `110ms` `CubicBezierEasing(0.2, 0.9, 0.3, 1.0)`):
 *   - Center CRT translates `+80.dp` Y,
 *   - Side Remote tucks `75%` (`+36.dp`) off right edge (LED column peeking out),
 *   - Tor Orb & PGP Switch scale to `0.75f` in bottom corners;
 *   - Upward flick ([podsRetracted] -> `false`) or tapping any retracted pod snaps back in `110ms`!
 * - **IME & CRT Edit Choreography** (`imeOpen || crtEditing`):
 *   - Hides Tor Orb, PGP Switch & Side Remote completely, docking CRT Capsule `6.dp` above
 *     IME keyboard at `94%` viewport width!
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

    // Sync local retraction state with caller's podsRetracted while allowing instant
    // snap-back when tapping any retracted pod!
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
        }
    }

    LaunchedEffect(openTorCircuitTrigger) {
        if (openTorCircuitTrigger > 0 && !chromeHiddenForPip) {
            restoreRetractedPods()
            remoteMode = RemoteMode.TorCircuit
            remoteExpanded = true
        }
    }

    // Automatically surface ContextMenu or SelectionPgp in the Side Remote
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

    // Collapse Side Remote whenever scroll retraction engages
    LaunchedEffect(retracted) {
        if (retracted && remoteExpanded) {
            remoteExpanded = false
        }
    }

    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeOpen = imeVisibleOverride ?: (imeBottomPx > 0)
    val hideClusterForImeOrEdit = crtEditing || imeOpen

    // Never read system ClipboardManager passively during composition (avoids unsolicited
    // Android 13–17 "Trap pasted from your clipboard" system privacy toast). Clipboard is
    // read ONLY on explicit user action (Paste key or OpenPGP Rocker latch pull), while
    // honoring [clipboardTextOverride] in deterministic unit tests.
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

    // 110ms Mechanical Retraction Animations (CubicBezierEasing(0.2, 0.9, 0.3, 1.0), Zero Overshoot)
    val crtRetractOffsetY by animateDpAsState(
        targetValue = if (retracted && !hideClusterForImeOrEdit) 80.dp else 0.dp,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.POD_SNAP_DURATION_MS,
            easing = MetaToolsTokens.PodSnapEasing,
        ),
        label = "crt_retract_y",
    )

    // 75% of 48dp collapsed remote width = +36dp off right bezel, leaving 12dp LED strip peeking out
    val remoteRetractOffsetX by animateDpAsState(
        targetValue = if (retracted && !remoteExpanded) 36.dp else 0.dp,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.POD_SNAP_DURATION_MS,
            easing = MetaToolsTokens.PodSnapEasing,
        ),
        label = "remote_retract_x",
    )

    val cornerPodScale by animateFloatAsState(
        targetValue = if (retracted && !hideClusterForImeOrEdit) 0.75f else 1.0f,
        animationSpec = tween(
            durationMillis = MetaToolsTokens.POD_SNAP_DURATION_MS,
            easing = MetaToolsTokens.PodSnapEasing,
        ),
        label = "corner_pod_scale",
    )

    val topStatusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("metatools_browser_shell"),
    ) {
        // 1. Full-bleed Webpage / StartPage Surface (100% unobstructed edge-to-edge, 0dp top/bottom container bars)
        Box(modifier = Modifier.fillMaxSize()) {
            surface()
        }

        if (!chromeHiddenForPip) {
            // 2. 3dp Cylindrical Brushed-Steel Frame + Street Overspray (100% Hit-Test Passthrough!)
            PerimeterSteelRails(
                isLoading = state.isLoading,
                loadProgress = state.loadProgress,
                topInset = topStatusBarInset,
            )

            // 3. Detached Right-Edge PhotoSoap Side Remote (hidden while CRT editing / IME open)
            if (!hideClusterForImeOrEdit) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 6.dp, bottom = 84.dp)
                        .offset(x = remoteRetractOffsetX),
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

            // 4. Bottom Detached Tri-Pod Cluster (10dp above navigationBarsPadding(), open 12dp gaps!)
            if (crtEditing) {
                // Docked 6dp above IME keyboard at 94% viewport width; Tor Orb, PGP Switch & Side Remote hidden!
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(bottom = 6.dp),
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
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.5.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.Bottom,
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
                        modifier = Modifier.offset(y = crtRetractOffsetY),
                    )

                    OpenPgpRockerPod(
                        hasPgpContent = hasPgpContent,
                        hasArmoredCiphertextOrKey = hasArmoredBlock,
                        onOpenPgpTools = {
                            if (retracted) {
                                restoreRetractedPods()
                            } else if (state.pgp.fields.isNotEmpty() || state.selectionPgp != null) {
                                remoteMode = RemoteMode.PgpActions
                                remoteExpanded = true
                            } else {
                                keyringOpen = true
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
