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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import dev.onionvault.OvNative
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
 * (`0dp` top/bottom container bars, drawing all the way up behind the Android status bar)
 * beneath [MetaToolsBrowserShell]'s detached sculptural hardware pods (`PerimeterSteelRails`,
 * `TorKnurledOrbPod`, `RecessedCrtAddressPod`, `OpenPgpRockerPod`, `PhotoSoapSideRemote`,
 * `FindInPageHardwarePod`, and `HardwareModalPods`), with zero top snackbars or notifications.
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
    onOpenFindInPage: () -> Unit = {},
    onFindInPageQueryChange: (String) -> Unit = {},
    onFindInPageStep: (Int) -> Unit = {},
    onCloseFindInPage: () -> Unit = {},
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
    previewProgress: Float = 0f,
    showKeyManager: Boolean = false,
    showLegalNotices: Boolean = false,
    showMenu: Boolean = false,
    showTabs: Boolean = false,
    showSecurity: Boolean = false,
    showSettings: Boolean = false,
    showMoneroWallet: Boolean = false,
    showBtcXmrSwap: Boolean = false,
    showXmr402Modal: Boolean = false,
    showCrtDirectory: Boolean = false,
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
            onOpenFindInPage = onOpenFindInPage,
            onFindInPageQueryChange = onFindInPageQueryChange,
            onFindInPageStep = onFindInPageStep,
            onCloseFindInPage = onCloseFindInPage,
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
            onOpenMoneroWallet = onOpenMoneroWallet,
            onDismissMoneroWallet = onDismissMoneroWallet,
            onOpenBtcXmrSwap = onOpenBtcXmrSwap,
            onDismissBtcXmrSwap = onDismissBtcXmrSwap,
            onCreateXmrWallet = onCreateXmrWallet,
            onRestoreXmrWallet = onRestoreXmrWallet,
            onSyncXmrWallet = onSyncXmrWallet,
            onNewXmrSubaddress = onNewXmrSubaddress,
            onSendXmr = onSendXmr,
            onSetXmrDaemonEndpoint = onSetXmrDaemonEndpoint,
            onRevealXmrSeed = onRevealXmrSeed,
            onHideXmrSeed = onHideXmrSeed,
            onDismissNewXmrSeedBackup = onDismissNewXmrSeedBackup,
            onDeleteXmrWallet = onDeleteXmrWallet,
            onFetchBtcSwapQuotes = onFetchBtcSwapQuotes,
            onLaunchBtcSwapQuoteInBrowser = onLaunchBtcSwapQuoteInBrowser,
            onOpenTrackedSwapPage = onOpenTrackedSwapPage,
            onDismissTrackedSwap = onDismissTrackedSwap,
            onDismissActiveSwapHud = onDismissActiveSwapHud,
            onCopySiteSubaddress = onCopySiteSubaddress,
            onProveXmrTx = onProveXmrTx,
            onOpenXmr402Payment = onOpenXmr402Payment,
            onDismissXmr402Payment = onDismissXmr402Payment,
            onPayAndUnlockXmr402 = onPayAndUnlockXmr402,
            onUseXmr402PageDaemon = onUseXmr402PageDaemon,
            onSendXmrFromContextMenu = onSendXmrFromContextMenu,
            surface = surface,
            previewProgress = previewProgress,
            showKeyManager = showKeyManager,
            showLegalNotices = showLegalNotices,
            showMenu = showMenu,
            showTabs = showTabs,
            showSecurity = showSecurity,
            showSettings = showSettings,
            showMoneroWallet = showMoneroWallet,
            showBtcXmrSwap = showBtcXmrSwap,
            showXmr402Modal = showXmr402Modal,
            showCrtDirectory = showCrtDirectory,
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
    onOpenFindInPage: () -> Unit,
    onFindInPageQueryChange: (String) -> Unit,
    onFindInPageStep: (Int) -> Unit,
    onCloseFindInPage: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onDismissStatus: () -> Unit,
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
    onOpenMoneroWallet: () -> Unit,
    onDismissMoneroWallet: () -> Unit,
    onOpenBtcXmrSwap: () -> Unit,
    onDismissBtcXmrSwap: () -> Unit,
    onCreateXmrWallet: (label: String) -> Unit,
    onRestoreXmrWallet: (mnemonic: String, restoreHeight: Long) -> Unit,
    onSyncXmrWallet: () -> Unit,
    onNewXmrSubaddress: (label: String) -> Unit,
    onSendXmr: (destinationAddress: String, amountXmr: String, sweepAll: Boolean) -> Unit,
    onSetXmrDaemonEndpoint: (endpointUrl: String, mode: String) -> Unit,
    onRevealXmrSeed: () -> Unit,
    onHideXmrSeed: () -> Unit,
    onDismissNewXmrSeedBackup: () -> Unit,
    onDeleteXmrWallet: () -> Unit,
    onFetchBtcSwapQuotes: (btcAmount: String) -> Unit,
    onLaunchBtcSwapQuoteInBrowser: (quote: OvNative.BtcSwapQuoteItem, btcAmount: String) -> Unit,
    onOpenTrackedSwapPage: (OvNative.TrackedBtcXmrSwap) -> Unit,
    onDismissTrackedSwap: (swapId: String) -> Unit,
    onDismissActiveSwapHud: (swapId: String) -> Unit,
    onCopySiteSubaddress: (pageUrl: String) -> Unit,
    onProveXmrTx: (txid: String, recipientAddress: String, message: String) -> Unit,
    onOpenXmr402Payment: () -> Unit,
    onDismissXmr402Payment: () -> Unit,
    onPayAndUnlockXmr402: (OvNative.Xmr402Challenge) -> Unit,
    onUseXmr402PageDaemon: (daemonUrl: String) -> Unit,
    onSendXmrFromContextMenu: (addressOrUri: String, amountHint: String) -> Unit,
    surface: @Composable () -> Unit,
    previewProgress: Float,
    showKeyManager: Boolean,
    showLegalNotices: Boolean,
    showMenu: Boolean,
    showTabs: Boolean,
    showSecurity: Boolean,
    showSettings: Boolean,
    showMoneroWallet: Boolean,
    showBtcXmrSwap: Boolean,
    showXmr402Modal: Boolean,
    showCrtDirectory: Boolean,
) {
    val colors = TrapTheme.colors
    val scale = remember { Animatable(1f - previewProgress * 0.12f) }
    val shift = remember { Animatable(previewProgress * 96f) }
    var progress by remember { mutableFloatStateOf(previewProgress) }
    var shellOverlayOpen by remember { mutableStateOf(false) }
    var openTorCircuitCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val chromeHiddenForPip = state.isInPipMode || state.isPipChromeHidden

    PredictiveBackHandler(enabled = state.canGoBack && !shellOverlayOpen && !chromeHiddenForPip) { backFlow ->
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
        MetaToolsBrowserShell(
            state = state,
            podsRetracted = state.podsRetracted,
            onSetPodsRetracted = onSetPodsRetracted,
            initialCrtEditing = showCrtDirectory,
            initialRemoteExpanded = showMenu || showSecurity,
            initialRemoteMode = if (showSecurity) RemoteMode.TorCircuit else RemoteMode.Menu,
            openTorCircuitTrigger = openTorCircuitCount,
            showTabsModal = showTabs,
            showKeyringModal = showKeyManager,
            showSettingsModal = showSettings,
            showLegalModal = showLegalNotices,
            showMoneroWalletModal = showMoneroWallet,
            showBtcXmrSwapModal = showBtcXmrSwap,
            showXmr402Modal = showXmr402Modal,
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
            onOpenFindInPage = onOpenFindInPage,
            onFindInPageQueryChange = onFindInPageQueryChange,
            onFindInPageStep = onFindInPageStep,
            onCloseFindInPage = onCloseFindInPage,
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
            onOpenMoneroWallet = onOpenMoneroWallet,
            onDismissMoneroWallet = onDismissMoneroWallet,
            onOpenBtcXmrSwap = onOpenBtcXmrSwap,
            onDismissBtcXmrSwap = onDismissBtcXmrSwap,
            onCreateXmrWallet = onCreateXmrWallet,
            onRestoreXmrWallet = onRestoreXmrWallet,
            onSyncXmrWallet = onSyncXmrWallet,
            onNewXmrSubaddress = onNewXmrSubaddress,
            onSendXmr = onSendXmr,
            onSetXmrDaemonEndpoint = onSetXmrDaemonEndpoint,
            onRevealXmrSeed = onRevealXmrSeed,
            onHideXmrSeed = onHideXmrSeed,
            onDismissNewXmrSeedBackup = onDismissNewXmrSeedBackup,
            onDeleteXmrWallet = onDeleteXmrWallet,
            onFetchBtcSwapQuotes = onFetchBtcSwapQuotes,
            onLaunchBtcSwapQuoteInBrowser = onLaunchBtcSwapQuoteInBrowser,
            onOpenTrackedSwapPage = onOpenTrackedSwapPage,
            onDismissTrackedSwap = onDismissTrackedSwap,
            onDismissActiveSwapHud = onDismissActiveSwapHud,
            onCopySiteSubaddress = onCopySiteSubaddress,
            onProveXmrTx = onProveXmrTx,
            onOpenXmr402Payment = onOpenXmr402Payment,
            onDismissXmr402Payment = onDismissXmr402Payment,
            onPayAndUnlockXmr402 = onPayAndUnlockXmr402,
            onUseXmr402PageDaemon = onUseXmr402PageDaemon,
            onSendXmrFromContextMenu = onSendXmrFromContextMenu,
            surface = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0D0F12)),
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
                        // Draw edge-to-edge all the way up onto the top Android status bar (y = 0)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("webpage_viewport_host"),
                        ) {
                            // Cached dark fallback frame behind live SurfaceView so PiP transitions
                            // or connecting states never flash a blank white frame.
                            if (chromeHiddenForPip && state.pipFallbackBitmap != null) {
                                Image(
                                    bitmap = state.pipFallbackBitmap,
                                    contentDescription = state.title.ifEmpty { state.url },
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("pip_fallback_frame"),
                                )
                            }

                            surface()

                            if (state.viewingSnapshot && state.backPreview != null && progress == 0f) {
                                BackPreviewLayer(state = state, progress = 1f)
                            }

                            if (!chromeHiddenForPip &&
                                state.findInPage.visible &&
                                state.findInPage.activeBounds != null &&
                                progress == 0f
                            ) {
                                FindInPageMatchHighlight(bounds = state.findInPage.activeBounds)
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

                    // Bottom-docked snapshot chip ONLY when viewing a predictive-back cached frame.
                    // Never render snackbars or notifications at the top of the app!
                    if (!chromeHiddenForPip && state.viewingSnapshot) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 100.dp),
                        ) {
                            SnapshotReloadChip(onReloadLive = onReloadLive)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun FindInPageMatchHighlight(bounds: NormalizedBounds) {
    val colors = TrapTheme.colors
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("find_in_page_highlight"),
    ) {
        val left = maxWidth * bounds.left.coerceIn(0f, 1f)
        val top = maxHeight * bounds.top.coerceIn(0f, 1f)
        val width = (maxWidth * bounds.width).coerceAtLeast(16.dp)
        val height = (maxHeight * bounds.height).coerceAtLeast(16.dp)
        val shape = RoundedCornerShape(4.dp)
        Box(
            modifier = Modifier
                .offset(x = left, y = top)
                .width(width)
                .height(height)
                .clip(shape)
                .background(colors.amber.copy(alpha = 0.28f))
                .border(2.dp, colors.amber, shape),
        )
    }
}

@Composable
private fun SnapshotReloadChip(onReloadLive: () -> Unit) {
    val colors = TrapTheme.colors
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(colors.surface.copy(alpha = 0.94f))
            .border(1.dp, colors.teal, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("snapshot_reload_banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Cached snapshot",
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
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

        var renderedXmrInputPill = false
        var renderedBtcDepositPill = false
        var renderedMoneroUriPill = false

        fields.forEachIndexed { index, field ->
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

                    "xmr_address_input" -> {
                        val tag = if (!renderedXmrInputPill) {
                            renderedXmrInputPill = true
                            "xmr_insert_subaddress_pill"
                        } else {
                            "xmr_insert_subaddress_pill_$index"
                        }
                        OvPgpPill(
                            text = "Insert XMR Subaddress",
                            kind = PgpPillKind.InsertXmrSubaddress,
                            onClick = { onFieldAction(field) },
                            modifier = Modifier.testTag(tag),
                        )
                    }

                    "btc_deposit_address" -> {
                        val tag = if (!renderedBtcDepositPill) {
                            renderedBtcDepositPill = true
                            "btc_copy_deposit_pill"
                        } else {
                            "btc_copy_deposit_pill_$index"
                        }
                        OvPgpPill(
                            text = "Copy BTC Deposit",
                            kind = PgpPillKind.CopyBtcDeposit,
                            onClick = { onFieldAction(field) },
                            modifier = Modifier.testTag(tag),
                        )
                    }

                    "monero_uri" -> {
                        val tag = if (!renderedMoneroUriPill) {
                            renderedMoneroUriPill = true
                            "xmr_pay_uri_pill"
                        } else {
                            "xmr_pay_uri_pill_$index"
                        }
                        OvPgpPill(
                            text = "Pay XMR",
                            kind = PgpPillKind.PayXmrUri,
                            onClick = { onFieldAction(field) },
                            modifier = Modifier.testTag(tag),
                        )
                    }
                }
            }
        }
    }
}

private val EDITABLE_FIELD_KINDS = setOf(
    "pgp_textarea",
    "textarea",
    "text_input",
    "editable",
    "xmr_address_input",
)

private fun PgpFieldState.isEditablePubKeyTarget(): Boolean =
    kind == "pgp_public_key" && label.equals("Insert My PubKey", ignoreCase = true)

private fun PgpFieldState.isEditableTarget(): Boolean =
    !isSearchLike && (kind in EDITABLE_FIELD_KINDS || isEditablePubKeyTarget())
