package dev.onionvault

import android.app.HandoffActivityData
import android.app.HandoffActivityDataRequestInfo
import android.app.PictureInPictureUiState
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.app.KeyguardManager
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dev.cefpack.android.CefpackSession
import dev.onionvault.platform.CrossDeviceHandoffController
import dev.onionvault.platform.LanAccessController
import dev.onionvault.platform.MediaDisplayNormalizer
import dev.onionvault.platform.PerformanceAndRenderController
import dev.onionvault.platform.ScreenRecordingGuard
import dev.onionvault.platform.SurfaceFilterMode
import dev.onionvault.platform.WebPickersBridge
import dev.onionvault.ui.BackPreviewState
import dev.onionvault.ui.BrowserScreen
import dev.onionvault.ui.BrowserUiState
import dev.onionvault.ui.ClearnetPromptState
import dev.onionvault.ui.ContextMenuUiState
import dev.onionvault.ui.DecryptPassphraseRequestState
import dev.onionvault.ui.EncryptRequestState
import dev.onionvault.ui.FindInPageState
import dev.onionvault.ui.KeyInfo
import dev.onionvault.ui.MoneroWalletTab
import dev.onionvault.ui.NormalizedBounds
import dev.onionvault.ui.OvCrashScreen
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.PgpPlaintextState
import dev.onionvault.ui.SelectionPgpUiState
import dev.onionvault.ui.TabItemState
import dev.onionvault.ui.TorBootstrapState
import dev.onionvault.ui.TrapPreferences
import dev.onionvault.ui.TrapTheme
import dev.onionvault.ui.TrapThemeFamily
import dev.onionvault.ui.TrapThemeMode
import dev.onionvault.ui.isBlankOrStartUrl
import dev.onionvault.ui.pods.isOnionHostUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Primary browser activity for Trap.
 *
 * Binds [BrowserScreen] and [OvCrashScreen] to:
 *   1. [TabSessionManager] (ephemeral off-the-record `CefpackSession` multi-tab sessions),
 *   2. [CdpDomPgpController] (100% zero-JavaScript in-process CDP DOM OpenPGP scanner & mutator),
 *   3. [OnionBackStackManager] (PixelCopy snapshot stack powering predictive-back gestures),
 *   4. [OvNative] (Arti Tor SOCKS5/isolation client + Sequoia Keystore OpenPGP engine), and
 *   5. [TrapPreferences] (persisted `SharedPreferences` backing every user setting).
 */
class MainActivity : FragmentActivity() {

    companion object {
        private const val TAG = "Trap"
        private const val STATE_URL = "url"
        private const val ZOOM_STEP_PERCENT = 15

        @VisibleForTesting
        const val EXTRA_SHOW_OVER_KEYGUARD_FOR_TEST = "dev.onionvault.SHOW_OVER_KEYGUARD_FOR_TEST"

        @Volatile
        var nativeRuntimeInitialized: Boolean = false

        fun isJvmUnitTestRuntime(): Boolean =
            Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
    }

    private lateinit var prefs: TrapPreferences
    private lateinit var pipController: PipController
    private lateinit var screenRecordingGuard: ScreenRecordingGuard
    private lateinit var performanceController: PerformanceAndRenderController
    private val mediaDisplayNormalizer = MediaDisplayNormalizer()
    private val lanAccessController = LanAccessController()
    private val backStackManager = OnionBackStackManager()
    private lateinit var cdpController: CdpDomPgpController
    private lateinit var tabSessionManager: TabSessionManager
    private var controllersReady: Boolean = false

    private var torPollingJob: Job? = null
    private var stateSyncJob: Job? = null

    private var viewportSourceRect: Rect? = null
    private var fullViewportWidthPx: Int = 0
    private var hostSurfaceFrame: FrameLayout? = null
    private var isTransitioningToPipState: Boolean = false
    private var pipReceiverRegistered: Boolean = false
    private var pendingLanNavigationUrl: String? = null
    private var pendingHandoffScrollY: Int = 0
    private var pendingColorPickerCallback: CefpackSession.ColorCallback? = null
    private var pendingContactsPickedCallback: ((Intent?) -> Unit)? = null

    private val viewportLayoutListener =
        View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            updateViewportSourceRect(view)
        }
    private val pipActionReceiver = PipActionReceiver { command ->
        handlePipActionCommand(command)
    }

    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val targetUrl = pendingLanNavigationUrl
            pendingLanNavigationUrl = null
            if (granted && !targetUrl.isNullOrBlank() && controllersReady) {
                navigate(targetUrl)
            } else if (!granted) {
                showStatus(
                    "Local network permission denied; LAN navigation blocked.",
                    blocked = true,
                )
            }
        }

    private val eyeDropperLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingColorPickerCallback
            pendingColorPickerCallback = null
            val color = WebPickersBridge.extractEyeDropperColor(result.resultCode, result.data)
            if (color != null) {
                callback?.onColorSelected(color)
            } else {
                callback?.onDismissed()
            }
        }

    private val scopedContactPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingContactsPickedCallback
            pendingContactsPickedCallback = null
            callback?.invoke(if (result.resultCode == RESULT_OK) result.data else null)
        }

    /** Navigation queued while Arti is still binding its local 127.0.0.1 SOCKS5 port. */
    private var deferredNavigationUntilSocks: Pair<String, String>? = null

    /** Cached snapshot actively displayed after back navigation until live reload completes. */
    private var activeSnapshotView: BackPreviewState? = null

    private var uiState by mutableStateOf(BrowserUiState())
    private var crashReport by mutableStateOf<String?>(null)

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable && intent?.getBooleanExtra(EXTRA_SHOW_OVER_KEYGUARD_FOR_TEST, false) == true) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        prefs = TrapPreferences(this)
        pipController = PipController(this)
        performanceController = PerformanceAndRenderController(this)
        screenRecordingGuard = ScreenRecordingGuard(this) { active ->
            handleScreenRecordingStateChanged(active)
        }
        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            PipActionReceiver.intentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        pipReceiverRegistered = true
        applySystemBarsAppearance(prefs.themeMode)
        CrossDeviceHandoffController.syncActivityHandoffEnabled(this, prefs.handoffEnabled)

        val initiallyInPip = isInPictureInPictureMode
        applyPipWindowTranslucency(initiallyInPip)
        uiState = BrowserUiState(
            hiddenServiceOnly = prefs.hiddenServiceOnly,
            requireBiometric = prefs.requireBiometric,
            pipOnMinimizeEnabled = prefs.pipOnMinimizeEnabled,
            pipExcludePrivateTabs = prefs.pipExcludePrivateTabs,
            handoffEnabled = prefs.handoffEnabled,
            isInPipMode = initiallyInPip,
            isPipChromeHidden = PipController.resolveChromeHidden(
                isInPictureInPictureMode = initiallyInPip,
                isTransitioningToPip = false,
            ),
            zoomPercent = prefs.defaultZoomPercent,
            defaultZoomPercent = prefs.defaultZoomPercent,
            defaultKeyFingerprint = prefs.defaultKeyFingerprint,
            themeFamily = prefs.themeFamily,
            themeMode = prefs.themeMode,
            surfaceFilterMode = prefs.surfaceFilterMode,
        )

        val incomingHandoff = if (savedInstanceState == null) {
            CrossDeviceHandoffController.extractIncomingHandoff(intent)
        } else {
            null
        }
        if (incomingHandoff != null) {
            pendingHandoffScrollY = incomingHandoff.scrollY
        }

        crashReport = CrashReporter.pendingReport(this)
        if (crashReport == null) {
            val initialUrl = incomingHandoff?.url ?: resolveInitialUrl(savedInstanceState, intent)
            startEngineAndSessions(initialUrl)
        }
        consumeTextSearchIntent(intent)
        syncPipParams()

        setContent {
            LaunchedEffect(Unit) {
                val documents = withContext(Dispatchers.IO) {
                    LegalNotices.list(this@MainActivity)
                }
                uiState = uiState.copy(legalDocuments = documents)
            }

            val report = crashReport
            if (report != null) {
                TrapTheme(
                    family = uiState.themeFamily,
                    mode = uiState.themeMode,
                ) {
                    OvCrashScreen(
                        report = report,
                        onCopy = {
                            copyToClipboard(
                                label = "Trap Crash Trace",
                                text = report,
                                toastMessage = getString(R.string.crash_copied),
                            )
                        },
                        onRetry = { retryAfterCrash() },
                    )
                }
                return@setContent
            }

            BrowserScreen(
                state = uiState,
                onNavigate = { navigate(it) },
                onAllowClearnet = { prompt, pattern, ttlSeconds ->
                    allowClearnetAndProceed(prompt, pattern, ttlSeconds)
                },
                onBlockClearnet = { prompt ->
                    denyClearnetPrompt(prompt)
                },
                onRevokeClearnetRule = { pattern ->
                    revokeClearnetRule(pattern)
                },
                onClearClearnetRules = {
                    clearAllClearnetRules()
                },
                onFieldAction = { handleFieldAction(it) },
                onInsertMyPubKey = { insertOwnPublicKey(it) },
                onConfirmEncrypt = { request, plaintext, recipientFp, signerFp, passphrase ->
                    confirmEncrypt(request, plaintext, recipientFp, signerFp, passphrase)
                },
                onDismissEncrypt = {
                    uiState = uiState.copy(
                        pgp = uiState.pgp.copy(encryptRequest = null, error = null),
                    )
                },
                onSubmitDecryptPassphrase = { request, passphrase ->
                    attemptDecrypt(
                        armoredCiphertext = request.armoredCiphertext,
                        passphrase = passphrase,
                        nodeId = request.nodeId,
                        cdpNodeId = request.cdpNodeId,
                        backendNodeId = request.backendNodeId,
                        targetEditable = request.targetEditable,
                    )
                },
                onDismissDecryptPassphrase = {
                    uiState = uiState.copy(
                        pgp = uiState.pgp.copy(decryptPassphraseRequest = null, error = null),
                    )
                },
                onCopyPlaintext = { body ->
                    copyToClipboard(
                        label = "Decrypted Plaintext",
                        text = body,
                        toastMessage = "Plaintext copied to clipboard",
                    )
                },
                onInsertPlaintext = { insertPlaintextIntoField(it) },
                onDismissPlaintext = {
                    uiState = uiState.copy(
                        pgp = uiState.pgp.copy(plaintext = null),
                    )
                },
                onReloadLive = { reloadLive() },
                onBack = { goBack() },
                onForward = { goForward() },
                onReload = { reloadCurrentOrStop() },
                onNewTab = { createNewTab() },
                onNewPrivateTab = { createNewTab(isPrivate = true) },
                onSelectTab = { selectTab(it) },
                onSwitchAdjacentTab = { step -> switchAdjacentTab(step) },
                onCloseTab = { closeTab(it) },
                onHome = { goHome() },
                onGenerateKey = { userId, passphrase -> generateKey(userId, passphrase) },
                onImportKey = { armored, passphrase -> importKey(armored, passphrase) },
                onExportKey = { fp -> exportPublicKey(fp) },
                onSetDefaultKey = { fp -> setDefaultKey(fp) },
                onDeleteKey = { fp -> deleteKey(fp) },
                onToggleRouting = { enabled -> toggleHiddenServiceOnly(enabled) },
                onToggleRequireBiometric = { required -> toggleRequireBiometric(required) },
                onTogglePipOnMinimize = { enabled -> togglePipOnMinimize(enabled) },
                onTogglePipExcludePrivateTabs = { excluded -> togglePipExcludePrivateTabs(excluded) },
                onToggleHandoff = { enabled -> toggleHandoffEnabled(enabled) },
                onResetTorIsolation = { resetTorIsolationAndIdentity() },
                onSelectThemeFamily = { family -> updateThemeFamily(family) },
                onSelectThemeMode = { mode -> updateThemeMode(mode) },
                onSelectSurfaceFilterMode = { mode -> updateSurfaceFilterMode(mode) },
                onSelectDefaultZoom = { zoomPct -> updateDefaultZoom(zoomPct) },
                onClearSession = { clearEphemeralSession() },
                onZoomIn = { stepZoom(ZOOM_STEP_PERCENT) },
                onZoomOut = { stepZoom(-ZOOM_STEP_PERCENT) },
                onOpenFindInPage = { openFindInPage() },
                onFindInPageQueryChange = { query -> updateFindInPageQuery(query) },
                onFindInPageStep = { step -> stepFindInPage(step) },
                onCloseFindInPage = { closeFindInPage() },
                onDismissStatus = { clearStatusAndPgpError() },
                onContextMenuOpenNewTab = { targetUrl ->
                    dismissContextMenu()
                    createNewTab(targetUrl)
                },
                onContextMenuOpenCurrentTab = { targetUrl ->
                    dismissContextMenu()
                    navigate(targetUrl)
                },
                onCopyText = { text ->
                    copyToClipboard(
                        label = "Trap Copy",
                        text = text,
                        toastMessage = "Copied to clipboard",
                    )
                },
                onDecryptArmoredText = { armored ->
                    dismissContextMenu()
                    dismissSelectionPgp()
                    attemptDecrypt(
                        armoredCiphertext = armored,
                        passphrase = "",
                        nodeId = "selection",
                        cdpNodeId = 0,
                        backendNodeId = 0,
                        targetEditable = false,
                    )
                },
                onEncryptSelectedText = { selected ->
                    dismissContextMenu()
                    dismissSelectionPgp()
                    uiState = uiState.copy(
                        pgp = uiState.pgp.copy(
                            encryptRequest = EncryptRequestState(
                                nodeId = "selection",
                                cdpNodeId = 0,
                                backendNodeId = 0,
                                initialText = selected,
                                writeBackToField = false,
                            ),
                            error = null,
                        ),
                    )
                },
                onDismissContextMenu = { dismissContextMenu() },
                onDismissSelectionPgp = { dismissSelectionPgp() },
                onOverlayOpenChanged = { open ->
                    if (controllersReady) {
                        tabSessionManager.setTouchSuppressed(open)
                    }
                },
                onSetPodsRetracted = { retracted ->
                    if (controllersReady) {
                        tabSessionManager.setPodsRetracted(retracted)
                    }
                },
                onLoadLegalLines = { name ->
                    withContext(Dispatchers.IO) {
                        LegalNotices.read(this@MainActivity, name)
                    }
                },
                onOpenMoneroWallet = { openMoneroWalletModal(MoneroWalletTab.Receive) },
                onDismissMoneroWallet = { dismissMoneroWalletModal() },
                onOpenBtcXmrSwap = { openBtcXmrSwapModal() },
                onDismissBtcXmrSwap = { dismissBtcXmrSwapModal() },
                onCreateXmrWallet = { label -> createXmrWallet(label) },
                onRestoreXmrWallet = { mnemonic, restoreHeight ->
                    restoreXmrWallet(mnemonic, restoreHeight)
                },
                onSyncXmrWallet = { syncXmrWallet() },
                onNewXmrSubaddress = { label -> mintNewXmrSubaddress(label) },
                onSendXmr = { dest, amount, sweep -> sendXmr(dest, amount, sweep) },
                onSetXmrDaemonEndpoint = { url, mode -> setXmrDaemonEndpoint(url, mode) },
                onRevealXmrSeed = { revealXmrSeed() },
                onHideXmrSeed = {
                    uiState = uiState.copy(xmr = uiState.xmr.copy(revealedMnemonic = null))
                },
                onDismissNewXmrSeedBackup = {
                    uiState = uiState.copy(xmr = uiState.xmr.copy(newlyCreatedMnemonic = null))
                },
                onDeleteXmrWallet = { deleteXmrWallet() },
                onFetchBtcSwapQuotes = { btcAmount -> fetchBtcSwapQuotes(btcAmount) },
                onLaunchBtcSwapQuoteInBrowser = { quote, btcAmount ->
                    launchBtcSwapQuoteInBrowser(quote, btcAmount)
                },
                onOpenTrackedSwapPage = { swap -> openTrackedSwapPage(swap) },
                onDismissTrackedSwap = { swapId -> dismissTrackedSwap(swapId) },
                onDismissActiveSwapHud = { swapId ->
                    uiState = uiState.copy(
                        xmr = uiState.xmr.copy(
                            dismissedHudSwapIds = uiState.xmr.dismissedHudSwapIds + swapId,
                        ),
                    )
                },
                surface = {
                    ActiveBrowserSurfaceHost(activeTabId = uiState.activeTabId)
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (::screenRecordingGuard.isInitialized) {
            screenRecordingGuard.onStart(this)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode && controllersReady) {
            tabSessionManager.setPipScaleFactor(1.0f)
            applyPipWindowTranslucency(false)
        }
        syncPipParams()
    }

    override fun onPause() {
        super.onPause()
        if (isInPictureInPictureMode || isTransitioningToPipState) {
            capturePipFallbackBitmap()
            if (controllersReady) {
                tabSessionManager.ensureActiveTabRenderingForPip()
            }
        }
    }

    override fun onStop() {
        if (::screenRecordingGuard.isInitialized) {
            screenRecordingGuard.onStop(this)
        }
        super.onStop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        capturePipFallbackBitmap()
        if (::pipController.isInitialized) {
            pipController.handleUserLeaveHint(this, currentPipSnapshot())
        }
    }

    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= 35 && ::pipController.isInitialized) {
            val transitioning = pipController.isTransitioningToPipApi35(pipState)
            isTransitioningToPipState = transitioning
            if (transitioning) {
                capturePipFallbackBitmap()
                dismissTransientOverlaysForPip()
                if (controllersReady) {
                    tabSessionManager.setTouchSuppressed(false)
                    tabSessionManager.ensureActiveTabRenderingForPip()
                }
            }
            val hideChrome = PipController.resolveChromeHidden(
                isInPictureInPictureMode = isInPictureInPictureMode,
                isTransitioningToPip = transitioning,
            )
            if (uiState.isPipChromeHidden != hideChrome) {
                uiState = uiState.copy(isPipChromeHidden = hideChrome)
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode) {
            isTransitioningToPipState = false
        }
        applyPipWindowTranslucency(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            capturePipFallbackBitmap()
            dismissTransientOverlaysForPip()
            if (controllersReady) {
                tabSessionManager.setTouchSuppressed(false)
                tabSessionManager.ensureActiveTabRenderingForPip()
            }
        } else if (controllersReady) {
            tabSessionManager.setPipScaleFactor(1.0f)
        }
        val hideChrome = PipController.resolveChromeHidden(
            isInPictureInPictureMode = isInPictureInPictureMode,
            isTransitioningToPip = isTransitioningToPipState,
        )
        uiState = uiState.copy(
            isInPipMode = isInPictureInPictureMode,
            isPipChromeHidden = hideChrome,
            pipFallbackBitmap = if (isInPictureInPictureMode) uiState.pipFallbackBitmap else null,
        )
        hostSurfaceFrame?.let { updateViewportSourceRect(it) }
        syncPipParams()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (consumeTextSearchIntent(intent)) {
            return
        }
        val handoff = CrossDeviceHandoffController.extractIncomingHandoff(intent)
        if (handoff != null && crashReport == null && controllersReady) {
            pendingHandoffScrollY = handoff.scrollY
            if (isBlankOrStartUrl(uiState.url) && !uiState.isLoading) {
                navigate(handoff.url)
            } else {
                createNewTab(handoff.url)
            }
            return
        }
        val viewUrl = intent.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.trim()
        if (!viewUrl.isNullOrEmpty() && crashReport == null && controllersReady) {
            if (isBlankOrStartUrl(uiState.url) && !uiState.isLoading) {
                navigate(viewUrl)
            } else {
                createNewTab(viewUrl)
            }
        }
    }

    @RequiresApi(37)
    override fun onHandoffActivityDataRequested(
        requestInfo: HandoffActivityDataRequestInfo,
    ): HandoffActivityData? {
        val activeTab = if (controllersReady) tabSessionManager.activeTab() else null
        val payload = CrossDeviceHandoffController.buildPayloadIfEligible(
            handoffEnabled = uiState.handoffEnabled,
            activeUrl = activeTab?.url ?: uiState.url,
            activeTitle = activeTab?.title?.ifBlank { uiState.title } ?: uiState.title,
            isPrivateTab = activeTab?.isPrivate == true,
            scrollY = activeTab?.latestScrollY ?: 0,
        ) ?: return null
        return CrossDeviceHandoffController.buildHandoffActivityData(componentName, payload)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, uiState.url)
    }

    override fun onDestroy() {
        pendingColorPickerCallback?.onDismissed()
        pendingColorPickerCallback = null
        pendingContactsPickedCallback = null
        hostSurfaceFrame = null
        if (pipReceiverRegistered) {
            pipReceiverRegistered = false
            runCatching { unregisterReceiver(pipActionReceiver) }
        }
        torPollingJob?.cancel()
        stateSyncJob?.cancel()
        if (controllersReady) {
            tabSessionManager.destroyAll()
        } else {
            mediaDisplayNormalizer.releaseAll()
        }
        if (isFinishing && OvNative.isLoaded && nativeRuntimeInitialized) {
            nativeRuntimeInitialized = false
            runCatching { OvNative.nativeShutdown() }
        }
        super.onDestroy()
    }

    @Composable
    private fun ActiveBrowserSurfaceHost(activeTabId: String) {
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    setBackgroundColor(TabSessionManager.SURFACE_DARK_BG_COLOR)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    addOnLayoutChangeListener(viewportLayoutListener)
                }
            },
            update = { hostFrame ->
                hostSurfaceFrame = hostFrame
                hostFrame.setBackgroundColor(TabSessionManager.SURFACE_DARK_BG_COLOR)
                val targetSurface = if (controllersReady) {
                    tabSessionManager.surfaceContainerFor(activeTabId)
                } else {
                    null
                }
                val currentChild = if (hostFrame.childCount > 0) hostFrame.getChildAt(0) else null
                if (currentChild !== targetSurface) {
                    hostFrame.removeAllViews()
                    if (targetSurface != null) {
                        (targetSurface.parent as? ViewGroup)?.removeView(targetSurface)
                        hostFrame.addView(
                            targetSurface,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    }
                }
                val activeTab = if (controllersReady) tabSessionManager.activeTab() else null
                ScreenRecordingGuard.applyContentSensitivity(
                    view = hostFrame,
                    sensitive = activeTab?.isPrivate == true,
                )
                if (::performanceController.isInitialized) {
                    performanceController.applySurfaceFilter(hostFrame, uiState.surfaceFilterMode)
                }
                mediaDisplayNormalizer.applyWindowHdrHeadroom(
                    window = window,
                    rootSurfaceContainer = hostFrame,
                    hasEmbeddedMedia = activeTab?.hasActiveVideo == true || activeTab?.isPlayingAudio == true,
                    isFullscreenVideo = activeTab?.isFullscreenVideo == true,
                )
                applySurfaceAlpha(hostFrame, PipController.resolveWindowAlpha(uiState.isInPipMode))
                updateViewportSourceRect(hostFrame)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    private fun startEngineAndSessions(initialUrl: String) {
        if (!isJvmUnitTestRuntime()) {
            try {
                OvNative.load()
                TrapApplication.nativeLoadError?.let { loadErr ->
                    throw IllegalStateException(loadErr)
                }
                TrapApplication.engineInitError?.let { engineErr ->
                    throw IllegalStateException(engineErr)
                }
                if (!nativeRuntimeInitialized) {
                    val started = OvNative.nativeInit(
                        filesDir.absolutePath,
                        cacheDir.absolutePath,
                        prefs.hiddenServiceOnly,
                    )
                    if (!started) {
                        val reason = runCatching { OvNative.nativeLastInitError() }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: "Native runtime initialization failed"
                        throw IllegalStateException(reason)
                    }
                    nativeRuntimeInitialized = true
                }
                val bioApplied = OvNative.nativeSetRequireBiometric(prefs.requireBiometric)
                if (!bioApplied && prefs.requireBiometric) {
                    prefs.requireBiometric = false
                    uiState = uiState.copy(requireBiometric = false)
                } else {
                    val actualBio = runCatching { OvNative.nativeGetRequireBiometric() }
                        .getOrDefault(prefs.requireBiometric)
                    uiState = uiState.copy(requireBiometric = actualBio)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Trap startup failed", error)
                CrashReporter.record(this, error)
                crashReport = CrashReporter.pendingReport(this) ?: error.stackTraceToString()
                return
            }
        }

        ensureControllersInitialized(initialUrl)
        refreshKeys()
        refreshClearnetRules()
        refreshXmrStatus()
        startTorPolling()
    }

    private fun ensureControllersInitialized(initialUrl: String) {
        if (!controllersReady) {
            cdpController = CdpDomPgpController(
                onNodesDetected = { tabId, url, domHtml, nodes ->
                    if (controllersReady) {
                        tabSessionManager.recordTabDomHtml(tabId, domHtml)
                    }
                    maybeAttachDetectedBtcDepositToActiveSwap(tabId, url, nodes)
                },
            )
            tabSessionManager = TabSessionManager(
                context = this,
                cdpController = cdpController,
                backStackManager = backStackManager,
                initialHiddenServiceOnly = prefs.hiddenServiceOnly,
                lanAccessController = lanAccessController,
                mediaDisplayNormalizer = mediaDisplayNormalizer,
                onContextMenu = { target -> handleContextMenu(target) },
                onSelectionPgpAction = { action -> handleSelectionPgpAction(action) },
                onClearnetConsentRequired = { host, normalizedUrl, suggestedPattern ->
                    openClearnetConsentPrompt(
                        host = host,
                        pendingUrl = normalizedUrl,
                        suggestedPattern = suggestedPattern,
                    )
                },
                onLocalNetworkPermissionRequired = { targetUrl ->
                    requestLocalNetworkAccess(targetUrl)
                },
                onColorPickerRequested = { initialColor, callback ->
                    launchSystemEyeDropper(initialColor, callback)
                },
                onViewportInteraction = { active ->
                    if (::performanceController.isInitialized) {
                        performanceController.applyAdaptiveRefreshRate(
                            window = window,
                            isScrollingOrAnimating = active,
                        )
                    }
                },
                onUrlBlocked = { _, blockedUrl, reason ->
                    showStatus("$reason ($blockedUrl)", blocked = true)
                },
                onSecurityNotice = { notice ->
                    showStatus(notice, blocked = false)
                },
                onNavigationRecovered = { tabId ->
                    if (tabId == tabSessionManager.activeTabId.value &&
                        uiState.status.startsWith("Navigation failed")
                    ) {
                        uiState = uiState.copy(status = "", blocked = false)
                    }
                },
                onMoneroUriIntercepted = { payment ->
                    openMoneroSendWithPayment(payment)
                },
                onInternalRouteIntercepted = { route ->
                    when (route.lowercase()) {
                        "about:wallet" -> {
                            openMoneroWalletModal(MoneroWalletTab.Receive)
                            true
                        }

                        "about:swap" -> {
                            openBtcXmrSwapModal()
                            true
                        }

                        else -> false
                    }
                },
            )
            controllersReady = true
            startControllerStateSync()
        }

        if (OvNative.isLoaded) {
            val initialSocks = runCatching { OvNative.nativeSocksProxy() }.getOrDefault("").trim()
            if (initialSocks.isNotBlank()) {
                tabSessionManager.updateTorSocksProxy(initialSocks)
            }
        }

        val startTarget = initialUrl.trim().ifEmpty { getString(R.string.start_url) }
        val initFirstTab = {
            if (!isDestroyed && !isFinishing) {
                val tabId = tabSessionManager.ensureInitialTab("about:blank")
                if (prefs.defaultZoomPercent != 100) {
                    tabSessionManager.setZoomPercent(prefs.defaultZoomPercent, tabId)
                }
                if (!isBlankOrStartUrl(startTarget)) {
                    navigate(startTarget, tabId)
                }
            }
        }
        if (isJvmUnitTestRuntime()) {
            initFirstTab()
        } else {
            android.os.Looper.myQueue().addIdleHandler {
                initFirstTab()
                false
            }
        }
    }

    private fun startControllerStateSync() {
        stateSyncJob?.cancel()
        stateSyncJob = lifecycleScope.launch {
            combine(
                tabSessionManager.tabs,
                tabSessionManager.activeTabId,
                cdpController.activeNodes,
                backStackManager.revision,
                tabSessionManager.podsRetracted,
            ) { tabs, activeId, nodesByTab, _, podsRetracted ->
                ControllerSnapshot(
                    tabs = tabs,
                    activeTabId = activeId,
                    nodesByTab = nodesByTab,
                    podsRetracted = podsRetracted,
                )
            }.collect { snapshot ->
                val activeTab = snapshot.tabs.firstOrNull { it.id == snapshot.activeTabId }
                    ?: snapshot.tabs.firstOrNull()
                val effectiveActiveId = activeTab?.id ?: uiState.activeTabId

                val tabItems = if (snapshot.tabs.isEmpty()) {
                    listOf(
                        TabItemState(
                            id = effectiveActiveId,
                            title = "Trap",
                            url = "about:blank",
                            isActive = true,
                        ),
                    )
                } else {
                    snapshot.tabs.map { tab ->
                        TabItemState(
                            id = tab.id,
                            title = tab.title.ifBlank {
                                if (isBlankOrStartUrl(tab.url)) "Trap" else tab.url
                            },
                            url = tab.url,
                            isActive = tab.id == effectiveActiveId,
                            isLoading = tab.isLoading,
                            isOnion = tab.isOnion,
                            isPrivate = tab.isPrivate,
                        )
                    }
                }

                val liveNodes = if (activeTab != null) {
                    snapshot.nodesByTab[activeTab.id].orEmpty().map { it.toPgpFieldState() }
                } else {
                    emptyList()
                }

                // Clear cached snapshot overlay once live navigation finishes loading.
                if (activeSnapshotView != null && activeTab != null && !activeTab.isLoading) {
                    activeSnapshotView = null
                }

                // Restore incoming Android 17 Cross-Device Handoff scroll position once loaded.
                if (pendingHandoffScrollY > 0 &&
                    activeTab != null &&
                    !activeTab.isLoading &&
                    !isBlankOrStartUrl(activeTab.url)
                ) {
                    val scrollTarget = pendingHandoffScrollY
                    pendingHandoffScrollY = 0
                    tabSessionManager.scrollToVerticalOffset(scrollTarget, activeTab.id)
                }

                val previousEntry = if (activeTab != null) {
                    backStackManager.previousForUrl(activeTab.id, activeTab.url)
                } else {
                    null
                }
                val computedPreview = activeSnapshotView ?: previousEntry?.toBackPreviewState()
                val canNavigateBack = (activeTab?.canGoBack == true) || (previousEntry != null)

                uiState = uiState.copy(
                    url = activeTab?.url ?: "about:blank",
                    title = activeTab?.title.orEmpty(),
                    isLoading = activeTab?.isLoading == true,
                    loadProgress = activeTab?.loadProgress ?: 0,
                    canGoBack = canNavigateBack,
                    canGoForward = activeTab?.canGoForward == true,
                    viewingSnapshot = activeSnapshotView != null,
                    backPreview = computedPreview,
                    tabs = tabItems,
                    activeTabId = effectiveActiveId,
                    zoomPercent = activeTab?.zoomPercent ?: prefs.defaultZoomPercent,
                    podsRetracted = snapshot.podsRetracted,
                    pgp = uiState.pgp.copy(fields = liveNodes),
                )
                mediaDisplayNormalizer.applyWindowHdrHeadroom(
                    window = window,
                    rootSurfaceContainer = hostSurfaceFrame,
                    hasEmbeddedMedia = activeTab?.hasActiveVideo == true || activeTab?.isPlayingAudio == true,
                    isFullscreenVideo = activeTab?.isFullscreenVideo == true,
                )
                syncPipParams()
            }
        }
    }

    private fun startTorPolling() {
        torPollingJob?.cancel()
        if (!OvNative.isLoaded) return
        torPollingJob = lifecycleScope.launch {
            while (isActive) {
                pollTorStatusOnce()
                val activeTab = if (controllersReady) tabSessionManager.activeTab() else null
                val headroom = if (::performanceController.isInitialized) {
                    performanceController.sampleHeadroomIfDue(
                        tabCount = uiState.tabCount,
                        hasActiveMedia = activeTab?.hasActiveVideo == true ||
                            activeTab?.isPlayingAudio == true ||
                            activeTab?.hasWebRtcAudio == true,
                    )
                } else {
                    PerformanceAndRenderController.HeadroomSnapshot()
                }
                if (headroom.shouldThrottleBackground && controllersReady) {
                    tabSessionManager.throttleBackgroundTabs()
                }
                val delayMs = if (!uiState.tor.ready) {
                    500L
                } else {
                    headroom.recommendedPollIntervalMs
                }
                delay(delayMs)
            }
        }
    }

    private fun refreshTorStatusNow() {
        if (!OvNative.isLoaded) return
        lifecycleScope.launch {
            pollTorStatusOnce()
        }
    }

    private suspend fun pollTorStatusOnce() {
        if (!OvNative.isLoaded) return
        val currentUrl = (if (controllersReady) tabSessionManager.activeTab()?.url else null)
            ?.takeIf { it.isNotBlank() }
            ?: uiState.url
        val snapshot = withContext(Dispatchers.IO) {
            val json = runCatching { OvNative.nativeTorStatusJson(currentUrl) }.getOrDefault("")
            val parsed = OvNative.parseTorStatus(json)
            val resolvedSocks = if (parsed.socksAddr.isBlank()) {
                runCatching { OvNative.nativeSocksProxy() }.getOrDefault("")
            } else {
                parsed.socksAddr
            }
            parsed.copy(socksAddr = resolvedSocks.trim())
        }

        if (snapshot.socksAddr.isNotBlank() && controllersReady) {
            tabSessionManager.updateTorSocksProxy(snapshot.socksAddr)
            val queued = deferredNavigationUntilSocks
            if (queued != null) {
                deferredNavigationUntilSocks = null
                val decision = tabSessionManager.loadUrl(queued.second, queued.first)
                if (decision.allowed) {
                    clearStatusAndPgpError()
                }
            }
        }

        val pendingHost = snapshot.pendingClearnetHost.trim()
        val liveTabUrl = (if (controllersReady) tabSessionManager.activeTab()?.url else null)
            ?.takeIf { it.isNotBlank() }
            ?: uiState.url
        val onOnionMainFrame = isOnionHostUrl(currentUrl) || isOnionHostUrl(liveTabUrl)
        val staleSubresourceHost = if (onOnionMainFrame &&
            uiState.clearnetPrompt != null &&
            uiState.clearnetPrompt?.pendingUrl == null
        ) {
            uiState.clearnetPrompt?.host
        } else {
            null
        }
        if (onOnionMainFrame && (pendingHost.isNotEmpty() || !staleSubresourceHost.isNullOrBlank())) {
            // Never allow or nag for passive cross-realm clearnet subresources/preconnects
            // (e.g. clearnet favicons embedded inside DuckDuckGo .onion HTML); user-initiated
            // clearnet link clicks still prompt via [onClearnetConsentRequired] (`pendingUrl != null`).
            withContext(Dispatchers.IO) {
                if (pendingHost.isNotEmpty()) {
                    runCatching { OvNative.nativeDenyClearnet(pendingHost) }
                }
                if (!staleSubresourceHost.isNullOrBlank() && staleSubresourceHost != pendingHost) {
                    runCatching { OvNative.nativeDenyClearnet(staleSubresourceHost) }
                }
            }
        }
        val basePrompt = if (staleSubresourceHost != null) null else uiState.clearnetPrompt
        val updatedPrompt = if (basePrompt == null &&
            pendingHost.isNotEmpty() &&
            !onOnionMainFrame &&
            !snapshot.hiddenServiceOnly
        ) {
            ClearnetPromptState(
                host = pendingHost,
                pendingUrl = null,
                suggestedPattern = OvNative.suggestGlob(pendingHost),
            )
        } else {
            basePrompt
        }

        uiState = uiState.copy(
            tor = TorBootstrapState(
                percent = snapshot.bootstrapPercent.coerceIn(0, 100),
                stage = snapshot.bootstrapSummary,
                ready = snapshot.bootstrapped,
                socksAddr = snapshot.socksAddr,
                isolationToken = snapshot.isolationToken.takeIf { it.isNotBlank() },
                isolatedGroupCount = snapshot.isolatedGroupCount.coerceAtLeast(0),
            ),
            hiddenServiceOnly = snapshot.hiddenServiceOnly,
            clearnetPrompt = updatedPrompt,
        )
    }

    private fun retryAfterCrash() {
        CrashReporter.clear(this)
        crashReport = null
        startEngineAndSessions(getString(R.string.start_url))
    }

    private fun navigate(rawInput: String, targetTabId: String? = null) {
        if (!controllersReady) return
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return

        if (trimmed.equals("about:wallet", ignoreCase = true)) {
            openMoneroWalletModal(MoneroWalletTab.Receive)
            return
        }
        if (trimmed.equals("about:swap", ignoreCase = true)) {
            openBtcXmrSwapModal()
            return
        }
        if (trimmed.startsWith("monero:", ignoreCase = true)) {
            val payment = OvNative.parseMoneroUri(trimmed)
            if (payment != null) {
                openMoneroSendWithPayment(payment)
            } else {
                showStatus("Invalid Monero payment URI", blocked = true)
            }
            return
        }

        activeSnapshotView = null
        resetFindInPage()
        clearStatusAndPgpError()

        val effectiveTabId = targetTabId ?: tabSessionManager.activeTabId.value
            ?: tabSessionManager.ensureInitialTab()
        val decision = tabSessionManager.loadUrl(trimmed, effectiveTabId)
        if (!decision.allowed &&
            !decision.requiresClearnetConsent &&
            decision.normalizedUrl.isNotBlank() &&
            decision.normalizedUrl != "about:blank" &&
            tabSessionManager.activeSocksAddr.value.isBlank()
        ) {
            deferredNavigationUntilSocks = effectiveTabId to decision.normalizedUrl
        }
        refreshTorStatusNow()
    }

    private fun openClearnetConsentPrompt(
        host: String,
        pendingUrl: String?,
        suggestedPattern: String,
    ) {
        val cleanHost = host.trim().trimEnd('.').lowercase()
        if (cleanHost.isEmpty()) return
        val resolvedPattern = suggestedPattern.trim().ifBlank {
            OvNative.suggestGlob(cleanHost)
        }.ifBlank { cleanHost }
        uiState = uiState.copy(
            blocked = false,
            status = "",
            clearnetPrompt = ClearnetPromptState(
                host = cleanHost,
                pendingUrl = pendingUrl?.takeIf { it.isNotBlank() },
                suggestedPattern = resolvedPattern,
            ),
        )
    }

    private fun allowClearnetAndProceed(
        prompt: ClearnetPromptState,
        pattern: String,
        ttlSeconds: Long,
    ) {
        val cleanPattern = pattern.trim()
        if (cleanPattern.isEmpty()) return
        uiState = uiState.copy(
            clearnetPrompt = null,
            blocked = false,
            status = "",
        )
        if (!OvNative.isLoaded) return
        lifecycleScope.launch {
            val updatedRules = withContext(Dispatchers.IO) {
                val rulesJson = runCatching {
                    OvNative.nativeAllowClearnet(cleanPattern, ttlSeconds)
                }.getOrDefault("")
                val parsed = OvNative.parseClearnetRules(rulesJson)
                if (parsed.isNotEmpty() || rulesJson.trim().startsWith("[")) {
                    parsed
                } else {
                    val listJson = runCatching { OvNative.nativeListClearnetRulesJson() }.getOrDefault("")
                    OvNative.parseClearnetRules(listJson)
                }
            }
            uiState = uiState.copy(clearnetRules = updatedRules)
            val targetUrl = prompt.pendingUrl?.takeIf { it.isNotBlank() }
            if (targetUrl != null) {
                navigate(targetUrl)
            } else {
                refreshTorStatusNow()
            }
        }
    }

    private fun denyClearnetPrompt(prompt: ClearnetPromptState) {
        uiState = uiState.copy(clearnetPrompt = null)
        if (!OvNative.isLoaded) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { OvNative.nativeDenyClearnet(prompt.host) }
            }
            refreshTorStatusNow()
        }
    }

    private fun refreshClearnetRules() {
        if (!OvNative.isLoaded) return
        lifecycleScope.launch {
            val rules = withContext(Dispatchers.IO) {
                val json = runCatching { OvNative.nativeListClearnetRulesJson() }.getOrDefault("")
                OvNative.parseClearnetRules(json)
            }
            uiState = uiState.copy(clearnetRules = rules)
        }
    }

    private fun revokeClearnetRule(pattern: String) {
        if (!OvNative.isLoaded) return
        lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) {
                val json = runCatching { OvNative.nativeRevokeClearnetRule(pattern) }.getOrDefault("")
                OvNative.parseClearnetRules(json)
            }
            uiState = uiState.copy(clearnetRules = updated)
        }
    }

    private fun clearAllClearnetRules() {
        if (!OvNative.isLoaded) return
        lifecycleScope.launch {
            val cleared = withContext(Dispatchers.IO) {
                runCatching { OvNative.nativeClearClearnetRules() }.getOrDefault(false)
            }
            if (cleared) {
                uiState = uiState.copy(clearnetRules = emptyList())
            } else {
                refreshClearnetRules()
            }
        }
    }

    private fun reloadCurrentOrStop() {
        if (!controllersReady) return
        val active = tabSessionManager.activeTab() ?: return
        if (active.isLoading) {
            tabSessionManager.stopLoading(active.id)
        } else {
            reloadLive()
        }
    }

    private fun reloadLive() {
        if (!controllersReady) return
        val active = tabSessionManager.activeTab() ?: return
        val snapshotTarget = activeSnapshotView?.url
        activeSnapshotView = null
        clearStatusAndPgpError()
        uiState = uiState.copy(viewingSnapshot = false)
        if (!snapshotTarget.isNullOrBlank() && snapshotTarget != active.url) {
            navigate(snapshotTarget, active.id)
        } else if (!isBlankOrStartUrl(active.url)) {
            tabSessionManager.reload(active.id)
            refreshTorStatusNow()
        }
    }

    private fun goBack() {
        if (!controllersReady) return
        val active = tabSessionManager.activeTab() ?: return
        val cachedPrev = backStackManager.previousForUrl(active.id, active.url)
        val navigated = tabSessionManager.goBack(active.id)
        if (navigated) {
            val updated = tabSessionManager.activeTab()
            clearStatusAndPgpError()
            if (updated != null && isBlankOrStartUrl(updated.url)) {
                activeSnapshotView = null
                uiState = uiState.copy(
                    viewingSnapshot = false,
                    backPreview = null,
                )
            } else if (cachedPrev != null) {
                activeSnapshotView = cachedPrev.toBackPreviewState()
                uiState = uiState.copy(
                    viewingSnapshot = true,
                    backPreview = activeSnapshotView,
                )
            } else {
                activeSnapshotView = null
                uiState = uiState.copy(viewingSnapshot = false)
            }
            refreshTorStatusNow()
        } else if (cachedPrev != null) {
            clearStatusAndPgpError()
            backStackManager.popNewest(active.id)
            activeSnapshotView = cachedPrev.toBackPreviewState()
            uiState = uiState.copy(
                url = cachedPrev.url,
                title = cachedPrev.title,
                viewingSnapshot = true,
                backPreview = activeSnapshotView,
            )
        }
    }

    private fun goForward() {
        if (!controllersReady) return
        activeSnapshotView = null
        if (tabSessionManager.goForward()) {
            refreshTorStatusNow()
        }
    }

    private fun createNewTab(
        initialUrl: String = "about:blank",
        isPrivate: Boolean = false,
    ) {
        if (!controllersReady) return
        activeSnapshotView = null
        resetFindInPage()
        clearStatusAndPgpError()
        val hasInitialTarget = !isBlankOrStartUrl(initialUrl)
        val tabId = tabSessionManager.createTab(
            initialUrl = "about:blank",
            select = true,
            loadInitialBlank = !hasInitialTarget,
            isPrivate = isPrivate,
        )
        if (prefs.defaultZoomPercent != 100) {
            tabSessionManager.setZoomPercent(prefs.defaultZoomPercent, tabId)
        }
        if (hasInitialTarget) {
            navigate(initialUrl, tabId)
        }
        syncPipParams()
    }

    private fun selectTab(tabId: String) {
        if (!controllersReady) return
        activeSnapshotView = null
        resetFindInPage()
        tabSessionManager.selectTab(tabId)
        refreshTorStatusNow()
        syncPipParams()
    }

    private fun switchAdjacentTab(step: Int) {
        if (!controllersReady || step == 0) return
        val currentTabs = tabSessionManager.tabs.value
        if (currentTabs.size <= 1) return
        val currentIndex = currentTabs.indexOfFirst { it.id == tabSessionManager.activeTabId.value }
            .coerceAtLeast(0)
        val nextIndex = Math.floorMod(currentIndex + step, currentTabs.size)
        selectTab(currentTabs[nextIndex].id)
    }

    private fun closeTab(tabId: String) {
        if (!controllersReady) return
        activeSnapshotView = null
        resetFindInPage()
        tabSessionManager.closeTab(tabId)
        refreshTorStatusNow()
        syncPipParams()
    }

    private fun goHome() {
        navigate("about:blank")
    }

    private fun openFindInPage() {
        uiState = uiState.copy(
            findInPage = uiState.findInPage.copy(visible = true),
        )
        val existingQuery = uiState.findInPage.query
        if (existingQuery.isNotBlank()) {
            runFindInPage(existingQuery, stepDelta = 0)
        }
    }

    private fun updateFindInPageQuery(query: String) {
        uiState = uiState.copy(
            findInPage = uiState.findInPage.copy(
                visible = true,
                query = query,
            ),
        )
        runFindInPage(query, stepDelta = 0)
    }

    private fun stepFindInPage(stepDelta: Int) {
        val currentQuery = uiState.findInPage.query
        if (currentQuery.isBlank()) return
        runFindInPage(currentQuery, stepDelta = stepDelta)
    }

    private fun closeFindInPage() {
        resetFindInPage()
    }

    private fun resetFindInPage() {
        if (controllersReady) {
            tabSessionManager.clearFindInPage()
        }
        if (uiState.findInPage.visible ||
            uiState.findInPage.query.isNotEmpty() ||
            uiState.findInPage.matchCount != 0
        ) {
            uiState = uiState.copy(findInPage = FindInPageState())
        }
    }

    private fun runFindInPage(query: String, stepDelta: Int) {
        if (query.isBlank() || !controllersReady) {
            if (controllersReady) {
                tabSessionManager.clearFindInPage()
            }
            uiState = uiState.copy(
                findInPage = uiState.findInPage.copy(
                    query = query,
                    matchCount = 0,
                    activeIndex = 0,
                    activeBounds = null,
                ),
            )
            return
        }
        tabSessionManager.findInPage(query = query, stepDelta = stepDelta) { outcome ->
            if (!uiState.findInPage.visible || uiState.findInPage.query != query) {
                return@findInPage
            }
            uiState = uiState.copy(
                findInPage = uiState.findInPage.copy(
                    matchCount = outcome.matchCount,
                    activeIndex = outcome.activeIndex,
                    activeBounds = outcome.activeBounds?.let { box ->
                        NormalizedBounds(
                            left = box.left,
                            top = box.top,
                            right = box.right,
                            bottom = box.bottom,
                        )
                    },
                ),
            )
        }
    }

    private fun consumeTextSearchIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val triggered = intent.action == PipActionReceiver.ACTION_PIP_TEXT_SEARCH ||
            intent.getBooleanExtra(PipController.EXTRA_OPEN_TEXT_SEARCH, false)
        if (!triggered) return false
        intent.removeExtra(PipController.EXTRA_OPEN_TEXT_SEARCH)
        if (intent.action == PipActionReceiver.ACTION_PIP_TEXT_SEARCH) {
            intent.action = null
        }
        openFindInPage()
        return true
    }

    private fun stepZoom(deltaPercent: Int) {
        if (!controllersReady) return
        val updated = tabSessionManager.stepZoom(deltaPercent)
        uiState = uiState.copy(zoomPercent = updated)
    }

    private fun toggleHiddenServiceOnly(enabled: Boolean) {
        prefs.hiddenServiceOnly = enabled
        if (controllersReady) {
            tabSessionManager.setHiddenServiceOnly(enabled)
        } else if (OvNative.isLoaded) {
            runCatching { OvNative.nativeSetHiddenServiceOnly(enabled) }
        }
        uiState = uiState.copy(
            hiddenServiceOnly = enabled,
            blocked = false,
            status = if (enabled) {
                getString(R.string.hidden_service_only_on)
            } else {
                getString(R.string.hidden_service_only_off)
            },
        )
        refreshTorStatusNow()
    }

    private fun toggleRequireBiometric(required: Boolean) {
        if (!OvNative.isLoaded) {
            prefs.requireBiometric = required
            uiState = uiState.copy(requireBiometric = required)
            return
        }

        val applyNativeToggle = {
            lifecycleScope.launch {
                val (ok, actual) = withContext(Dispatchers.IO) {
                    val setOk = runCatching { OvNative.nativeSetRequireBiometric(required) }.getOrDefault(false)
                    val current = runCatching { OvNative.nativeGetRequireBiometric() }.getOrDefault(false)
                    setOk to current
                }
                prefs.requireBiometric = actual
                uiState = uiState.copy(
                    requireBiometric = actual,
                    status = if (!ok && required) {
                        "Hardware biometric key lock could not be enabled on this device."
                    } else if (actual) {
                        "Biometric / device unlock required for secret-key operations."
                    } else {
                        "Biometric / device unlock requirement disabled."
                    },
                    blocked = false,
                )
            }
        }

        if (!isJvmUnitTestRuntime() && (required || uiState.requireBiometric)) {
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            val authStatus = BiometricManager.from(this).canAuthenticate(authenticators)
            if (authStatus != BiometricManager.BIOMETRIC_SUCCESS) {
                showStatus(
                    "Configure a screen lock or biometric credential in Android Settings first.",
                    blocked = false,
                )
                return
            }

            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        applyNativeToggle()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        showStatus(errString.toString(), blocked = false)
                    }
                },
            )
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.authenticate_for_key))
                .setSubtitle(
                    if (required) {
                        "Authenticate to wrap secret keys with biometric protection"
                    } else {
                        "Authenticate to unlock secret keys and disable biometric lock"
                    },
                )
                .setAllowedAuthenticators(authenticators)
                .build()
            prompt.authenticate(promptInfo)
        } else {
            applyNativeToggle()
        }
    }

    private fun togglePipOnMinimize(enabled: Boolean) {
        if (enabled && !pipController.isDevicePipSupported()) {
            prefs.pipOnMinimizeEnabled = false
            uiState = uiState.copy(pipOnMinimizeEnabled = false)
            showStatus("Picture-in-Picture is not supported on this device.", blocked = false)
            syncPipParams()
            return
        }

        prefs.pipOnMinimizeEnabled = enabled
        uiState = uiState.copy(pipOnMinimizeEnabled = enabled)

        if (enabled && !pipController.isPipPermissionGranted() && !isJvmUnitTestRuntime()) {
            showStatus(
                "Allow Picture-in-Picture in Android Settings to float pages on minimize.",
                blocked = false,
            )
            val settingsIntent = pipController.buildPipSettingsIntent(packageName)
            runCatching { startActivity(settingsIntent) }
        }
        syncPipParams()
    }

    private fun togglePipExcludePrivateTabs(excluded: Boolean) {
        prefs.pipExcludePrivateTabs = excluded
        uiState = uiState.copy(pipExcludePrivateTabs = excluded)
        syncPipParams()
    }

    private fun updateViewportSourceRect(view: View) {
        if (isInPictureInPictureMode || isTransitioningToPipState) {
            if (controllersReady && view.width > 0) {
                val referenceWidth = fullViewportWidthPx.takeIf { it > 0 }
                    ?: resources.displayMetrics.widthPixels
                val scale = PipController.computePipScaleFactor(view.width, referenceWidth)
                tabSessionManager.setPipScaleFactor(scale)
            }
            return
        }
        if (view.width > 0) {
            fullViewportWidthPx = view.width
        }
        if (controllersReady) {
            tabSessionManager.setPipScaleFactor(1.0f)
        }
        val computed = PipController.computeViewportWindowRect(view) ?: return
        if (computed != viewportSourceRect) {
            viewportSourceRect = computed
            syncPipParams()
        }
    }

    private fun capturePipFallbackBitmap() {
        if (!controllersReady) return
        val activeTab = tabSessionManager.activeTab() ?: return
        if (activeTab.isPrivate || isBlankOrStartUrl(activeTab.url)) {
            if (uiState.pipFallbackBitmap != null) {
                uiState = uiState.copy(pipFallbackBitmap = null)
            }
            return
        }
        backStackManager.latest(activeTab.id)?.imageBitmap?.let { cached ->
            if (uiState.pipFallbackBitmap == null) {
                uiState = uiState.copy(pipFallbackBitmap = cached)
            }
        }
        val browser = tabSessionManager.activeBrowser() ?: return
        runCatching {
            browser.captureBitmap { captured ->
                val safeCopy = captured?.let { bmp ->
                    runCatching {
                        if (bmp.isRecycled) null else bmp.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                }
                val imageBitmap = safeCopy?.let { runCatching { it.asImageBitmap() }.getOrNull() }
                if (imageBitmap != null && (isInPictureInPictureMode || isTransitioningToPipState)) {
                    uiState = uiState.copy(pipFallbackBitmap = imageBitmap)
                }
            }
        }
    }

    private fun applyPipWindowTranslucency(inPip: Boolean) {
        val alpha = PipController.resolveWindowAlpha(inPip)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setTranslucent(inPip)
            }
        }
        runCatching {
            val attrs = window.attributes
            if (attrs.alpha != alpha) {
                attrs.alpha = alpha
                window.attributes = attrs
            }
        }
        hostSurfaceFrame?.let { applySurfaceAlpha(it, alpha) }
    }

    private fun applySurfaceAlpha(root: View, alpha: Float) {
        root.alpha = alpha
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (child is SurfaceView || child is ViewGroup) {
                    applySurfaceAlpha(child, alpha)
                }
            }
        }
    }

    private fun dismissTransientOverlaysForPip() {
        if (uiState.contextMenu != null ||
            uiState.selectionPgp != null ||
            uiState.clearnetPrompt != null ||
            uiState.findInPage.visible ||
            uiState.pgp.encryptRequest != null ||
            uiState.pgp.decryptPassphraseRequest != null ||
            uiState.pgp.plaintext != null ||
            uiState.xmr.walletSheetOpen ||
            uiState.xmr.swapSheetOpen
        ) {
            uiState = uiState.copy(
                contextMenu = null,
                selectionPgp = null,
                clearnetPrompt = null,
                findInPage = uiState.findInPage.copy(visible = false),
                pgp = uiState.pgp.copy(
                    encryptRequest = null,
                    decryptPassphraseRequest = null,
                    plaintext = null,
                ),
                xmr = uiState.xmr.copy(
                    walletSheetOpen = false,
                    swapSheetOpen = false,
                    revealedMnemonic = null,
                ),
            )
        }
    }

    private fun currentPipSnapshot(): PipController.PipSnapshot {
        val activeTab = if (controllersReady) tabSessionManager.activeTab() else null
        val url = activeTab?.url ?: uiState.url
        val title = activeTab?.title?.ifBlank { uiState.title } ?: uiState.title
        val tabCount = if (controllersReady) {
            tabSessionManager.tabs.value.size.coerceAtLeast(1)
        } else {
            uiState.tabCount
        }
        return PipController.PipSnapshot(
            pipOnMinimizeEnabled = uiState.pipOnMinimizeEnabled,
            pipExcludePrivateTabs = uiState.pipExcludePrivateTabs,
            crashScreenVisible = crashReport != null,
            activeUrl = url,
            activeTitle = title,
            isActiveTabPrivate = activeTab?.isPrivate == true,
            isFullscreenVideo = activeTab?.isFullscreenVideo == true,
            hasActiveVideo = activeTab?.hasActiveVideo == true,
            isPlayingAudio = activeTab?.isPlayingAudio == true,
            hasWebRtcAudio = activeTab?.hasWebRtcAudio == true,
            isMediaPausedByUser = activeTab?.isMediaPausedByUser == true,
            isAudioMuted = activeTab?.isAudioMuted == true,
            isLoading = activeTab?.isLoading ?: uiState.isLoading,
            canGoBack = (activeTab?.canGoBack ?: uiState.canGoBack) || uiState.viewingSnapshot,
            atDocumentBottom = activeTab?.atDocumentBottom == true,
            tabCount = tabCount,
            videoWidth = activeTab?.videoWidth ?: 0,
            videoHeight = activeTab?.videoHeight ?: 0,
            sourceRectHint = viewportSourceRect,
        )
    }

    private fun syncPipParams() {
        if (!::pipController.isInitialized) return
        pipController.syncPictureInPictureParams(this, currentPipSnapshot())
    }

    private fun handlePipActionCommand(command: PipActionReceiver.Command) {
        when (command) {
            PipActionReceiver.Command.PLAY_PAUSE -> {
                if (controllersReady) {
                    tabSessionManager.toggleActiveMediaPlayPause()
                }
                syncPipParams()
            }

            PipActionReceiver.Command.TOGGLE_MUTE -> {
                if (controllersReady) {
                    tabSessionManager.toggleActiveAudioMute()
                }
                syncPipParams()
            }

            PipActionReceiver.Command.SEEK_FORWARD -> {
                if (controllersReady) {
                    tabSessionManager.seekActiveMediaForward()
                }
                syncPipParams()
            }

            PipActionReceiver.Command.SCROLL_VIEWPORT -> {
                if (controllersReady) {
                    tabSessionManager.scrollActiveViewportFromPip {
                        syncPipParams()
                    }
                }
            }

            PipActionReceiver.Command.RELOAD_OR_STOP -> {
                reloadCurrentOrStop()
                syncPipParams()
            }

            PipActionReceiver.Command.SWITCH_TAB -> {
                if (controllersReady) {
                    val preferAudible = pipController.resolveOverlayModel(currentPipSnapshot()).mode ==
                        PipController.OverlayMode.MEDIA_WEBRTC
                    activeSnapshotView = null
                    tabSessionManager.cycleTabFromPip(preferAudible = preferAudible)
                }
                syncPipParams()
            }

            PipActionReceiver.Command.HISTORY_BACK -> {
                goBack()
                syncPipParams()
            }

            PipActionReceiver.Command.TEXT_SEARCH -> {
                openFindInPage()
                syncPipParams()
            }

            PipActionReceiver.Command.CLOSE_PIP -> {
                if (controllersReady) {
                    tabSessionManager.pauseAndStopActiveMediaForPipClose()
                }
                syncPipParams()
                if (!moveTaskToBack(true)) {
                    finish()
                }
            }
        }
    }

    private fun resetTorIsolationAndIdentity() {
        if (!controllersReady) return
        activeSnapshotView = null
        deferredNavigationUntilSocks = null
        resetFindInPage()
        val clearedGroups = tabSessionManager.resetAllSessionsAndIdentity("about:blank")
        val activeId = tabSessionManager.activeTabId.value
        if (activeId != null && prefs.defaultZoomPercent != 100) {
            tabSessionManager.setZoomPercent(prefs.defaultZoomPercent, activeId)
        }
        showStatus(
            if (clearedGroups > 0) {
                "Rotated Tor identity ($clearedGroups isolated circuit group(s) cleared)."
            } else {
                "Rotated Tor identity and reset ephemeral browser profile."
            },
            blocked = false,
        )
        refreshTorStatusNow()
        syncPipParams()
    }

    private fun clearEphemeralSession() {
        resetTorIsolationAndIdentity()
    }

    private fun updateThemeFamily(family: TrapThemeFamily) {
        prefs.themeFamily = family
        uiState = uiState.copy(themeFamily = family)
    }

    private fun updateThemeMode(mode: TrapThemeMode) {
        prefs.themeMode = mode
        applySystemBarsAppearance(mode)
        uiState = uiState.copy(themeMode = mode)
    }

    private fun updateDefaultZoom(zoomPercent: Int) {
        val clamped = zoomPercent.coerceIn(TrapPreferences.ZOOM_MIN, TrapPreferences.ZOOM_MAX)
        prefs.defaultZoomPercent = clamped
        if (controllersReady) {
            tabSessionManager.setZoomPercent(clamped)
        }
        uiState = uiState.copy(
            defaultZoomPercent = clamped,
            zoomPercent = clamped,
        )
    }

    private fun updateSurfaceFilterMode(mode: SurfaceFilterMode) {
        prefs.surfaceFilterMode = mode
        uiState = uiState.copy(surfaceFilterMode = mode)
        hostSurfaceFrame?.let { frame ->
            if (::performanceController.isInitialized) {
                performanceController.applySurfaceFilter(frame, mode)
            }
        }
    }

    private fun toggleHandoffEnabled(enabled: Boolean) {
        prefs.handoffEnabled = enabled
        uiState = uiState.copy(handoffEnabled = enabled)
        CrossDeviceHandoffController.syncActivityHandoffEnabled(this, enabled)
        if (enabled && !CrossDeviceHandoffController.hasCompanionAssociation(this)) {
            showStatus(
                "Cross-device tab handoff enabled; companion devices can resume active non-private tabs.",
                blocked = false,
            )
        }
    }

    private fun handleScreenRecordingStateChanged(active: Boolean) {
        uiState = uiState.copy(
            isScreenRecordingActive = active,
            pgp = if (active) {
                uiState.pgp.copy(
                    encryptRequest = null,
                    decryptPassphraseRequest = null,
                    plaintext = null,
                )
            } else {
                uiState.pgp
            },
            xmr = if (active) {
                uiState.xmr.copy(
                    walletSheetOpen = false,
                    swapSheetOpen = false,
                    revealedMnemonic = null,
                    newlyCreatedMnemonic = null,
                )
            } else {
                uiState.xmr
            },
        )
    }

    private fun requestLocalNetworkAccess(targetUrl: String) {
        pendingLanNavigationUrl = targetUrl
        if (isJvmUnitTestRuntime()) {
            showStatus("Local network permission required ($targetUrl)", blocked = false)
            return
        }
        runCatching {
            localNetworkPermissionLauncher.launch(LanAccessController.PERMISSION_ACCESS_LOCAL_NETWORK)
        }.onFailure {
            pendingLanNavigationUrl = null
            showStatus("Local network permission unavailable ($targetUrl)", blocked = true)
        }
    }

    private fun launchSystemEyeDropper(
        @Suppress("UNUSED_PARAMETER") initialColor: Int,
        callback: CefpackSession.ColorCallback,
    ): Boolean {
        if (!WebPickersBridge.isEyeDropperAvailable(this)) {
            return false
        }
        pendingColorPickerCallback?.onDismissed()
        pendingColorPickerCallback = callback
        return runCatching {
            eyeDropperLauncher.launch(WebPickersBridge.buildEyeDropperIntent())
            true
        }.getOrElse {
            pendingColorPickerCallback = null
            false
        }
    }

    fun launchScopedContactPicker(
        requestedMimeDataFields: List<String> = listOf(
            "vnd.android.cursor.item/name",
            "vnd.android.cursor.item/email_v2",
        ),
        selectionLimit: Int = 1,
        onResult: (Intent?) -> Unit,
    ): Boolean {
        val intent = WebPickersBridge.buildScopedContactPickerIntent(
            requestedMimeDataFields = requestedMimeDataFields,
            selectionLimit = selectionLimit,
        )
        pendingContactsPickedCallback = onResult
        return runCatching {
            scopedContactPickerLauncher.launch(intent)
            true
        }.getOrElse {
            pendingContactsPickedCallback = null
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarsAppearance(mode: TrapThemeMode) {
        val lightBars = mode == TrapThemeMode.Light
        runCatching {
            window.decorView.setBackgroundColor(TabSessionManager.SURFACE_DARK_BG_COLOR)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                window.attributes = attrs
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = attrs
            }
            if (Build.VERSION.SDK_INT >= 29) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = lightBars
            controller.isAppearanceLightNavigationBars = lightBars
        }
    }

    // --- OpenPGP Keyring & Zero-JS CDP DOM Actions ---

    private fun refreshKeys() {
        if (!OvNative.isLoaded) return
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val json = runCatching { OvNative.nativeListKeysJson() }.getOrDefault("")
                OvNative.parseKeys(json)
            }
            val mapped = entries.map { entry ->
                KeyInfo(
                    fingerprint = entry.fingerprint,
                    userId = entry.userId,
                    hasSecret = entry.hasSecret,
                    createdEpochSecs = entry.createdEpochSecs,
                    canSign = entry.canSign,
                    canEncrypt = entry.canEncrypt,
                    isOwnKey = entry.isOwnKey,
                )
            }
            val storedDefault = prefs.defaultKeyFingerprint
            val resolvedDefault = when {
                mapped.any { it.fingerprint == storedDefault && it.hasSecret } -> storedDefault
                else -> mapped.firstOrNull { it.hasSecret }?.fingerprint.orEmpty().also {
                    prefs.defaultKeyFingerprint = it
                }
            }
            uiState = uiState.copy(
                keys = mapped,
                defaultKeyFingerprint = resolvedDefault,
            )
        }
    }

    private fun generateKey(userId: String, passphrase: String) {
        if (!OvNative.isLoaded) {
            setPgpError("Native OpenPGP keyring is unavailable.")
            return
        }
        val cleanUserId = userId.trim()
        if (cleanUserId.isEmpty()) {
            setPgpError("Enter a User ID before generating a key.")
            return
        }
        withSecretKeyAuth(subtitle = "Unlock Keystore to wrap new OpenPGP secret key") {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val json = runCatching {
                        OvNative.nativeGenerateKey(cleanUserId, passphrase)
                    }.getOrDefault("")
                    OvNative.parseGenerateKey(json)
                }
                if (result.ok && result.fingerprint.isNotBlank()) {
                    if (prefs.defaultKeyFingerprint.isBlank()) {
                        prefs.defaultKeyFingerprint = result.fingerprint
                    }
                    refreshKeys()
                    showStatus("Generated OpenPGP key ${result.fingerprint.takeLast(8)}", blocked = false)
                } else {
                    setPgpError(result.error.ifBlank { "OpenPGP key generation failed." })
                }
            }
        }
    }

    private fun importKey(armored: String, passphrase: String) {
        if (!OvNative.isLoaded) {
            setPgpError("Native OpenPGP keyring is unavailable.")
            return
        }
        val trimmed = armored.trim()
        if (trimmed.isEmpty()) {
            setPgpError("Paste an ASCII-armored OpenPGP key block first.")
            return
        }
        val executeImport = {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val json = runCatching {
                        OvNative.nativeImportKey(trimmed, passphrase)
                    }.getOrDefault("")
                    OvNative.parseImportKey(json)
                }
                if (result.ok && result.fingerprint.isNotBlank()) {
                    if (result.hasSecret && prefs.defaultKeyFingerprint.isBlank()) {
                        prefs.defaultKeyFingerprint = result.fingerprint
                    }
                    refreshKeys()
                    val label = result.userId.ifBlank { result.fingerprint.takeLast(8) }
                    showStatus("Imported OpenPGP key ($label)", blocked = false)
                } else {
                    setPgpError(result.error.ifBlank { "OpenPGP key import failed." })
                }
            }
        }

        if (trimmed.contains("PRIVATE KEY", ignoreCase = true)) {
            withSecretKeyAuth(subtitle = "Unlock Keystore to import OpenPGP secret key") {
                executeImport()
            }
        } else {
            executeImport()
        }
    }

    private fun exportPublicKey(fingerprint: String) {
        if (!OvNative.isLoaded) {
            setPgpError("Native OpenPGP keyring is unavailable.")
            return
        }
        lifecycleScope.launch {
            val armored = withContext(Dispatchers.IO) {
                runCatching { OvNative.nativeExportPublicKey(fingerprint) }.getOrDefault("")
            }
            if (armored.isNotBlank()) {
                copyToClipboard(
                    label = "OpenPGP Public Key",
                    text = armored,
                    toastMessage = getString(R.string.key_copied),
                )
            } else {
                setPgpError("Could not export public key for ${fingerprint.takeLast(8)}.")
            }
        }
    }

    private fun setDefaultKey(fingerprint: String) {
        prefs.defaultKeyFingerprint = fingerprint
        uiState = uiState.copy(
            defaultKeyFingerprint = fingerprint,
            status = "Default key set to ${fingerprint.takeLast(8)}",
            blocked = false,
        )
    }

    private fun deleteKey(fingerprint: String) {
        if (!OvNative.isLoaded) return
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching { OvNative.nativeDeleteKey(fingerprint) }.getOrDefault(false)
            }
            if (deleted) {
                if (prefs.defaultKeyFingerprint == fingerprint) {
                    prefs.defaultKeyFingerprint = ""
                }
                refreshKeys()
                showStatus("Deleted key ${fingerprint.takeLast(8)}", blocked = false)
            } else {
                setPgpError("Could not delete key ${fingerprint.takeLast(8)}.")
            }
        }
    }

    private fun handleFieldAction(field: PgpFieldState) {
        when (field.kind) {
            "pgp_textarea", "textarea", "text_input", "editable" -> {
                uiState = uiState.copy(
                    pgp = uiState.pgp.copy(
                        encryptRequest = EncryptRequestState(
                            nodeId = field.nodeId,
                            cdpNodeId = field.cdpNodeId,
                            backendNodeId = field.backendNodeId,
                            initialText = field.armored ?: field.preview,
                            writeBackToField = true,
                        ),
                        error = null,
                    ),
                )
            }

            "pgp_message" -> {
                val ciphertext = field.armored?.takeIf { it.isNotBlank() } ?: field.preview
                attemptDecrypt(
                    armoredCiphertext = ciphertext,
                    passphrase = "",
                    nodeId = field.nodeId,
                    cdpNodeId = field.cdpNodeId,
                    backendNodeId = field.backendNodeId,
                    targetEditable = false,
                )
            }

            "pgp_public_key" -> {
                if (field.label.equals("Insert My PubKey", ignoreCase = true)) {
                    insertOwnPublicKey(field)
                } else {
                    val keyBlock = field.armored?.takeIf { it.isNotBlank() } ?: field.preview
                    importKey(keyBlock, "")
                }
            }

            "xmr_address_input" -> {
                insertXmrSubaddressIntoField(field)
            }

            "btc_deposit_address" -> {
                val btcAddr = (field.armored?.takeIf { it.isNotBlank() } ?: field.preview).trim()
                if (btcAddr.isNotEmpty()) {
                    copyToClipboard(
                        label = "BTC Deposit Address",
                        text = btcAddr,
                        toastMessage = "BTC deposit address copied to clipboard",
                    )
                }
            }

            "monero_uri" -> {
                val rawUri = (field.armored?.takeIf { it.isNotBlank() } ?: field.preview).trim()
                val payment = OvNative.parseMoneroUri(rawUri)
                if (payment != null) {
                    openMoneroSendWithPayment(payment)
                } else {
                    showStatus("Invalid Monero payment URI", blocked = true)
                }
            }
        }
    }

    private fun insertOwnPublicKey(field: PgpFieldState) {
        if (!OvNative.isLoaded) {
            setPgpError("Native OpenPGP keyring is unavailable.")
            return
        }
        val ownFp = uiState.defaultKeyFingerprint.ifBlank {
            uiState.keys.firstOrNull { it.hasSecret }?.fingerprint.orEmpty()
        }
        lifecycleScope.launch {
            val armored = withContext(Dispatchers.IO) {
                runCatching { OvNative.nativeExportPublicKey(ownFp) }.getOrDefault("")
            }
            if (armored.isBlank()) {
                setPgpError("Generate or import an own OpenPGP key before inserting.")
                return@launch
            }
            writeValueIntoActiveTabField(
                cdpNodeId = field.cdpNodeId,
                backendNodeId = field.backendNodeId,
                newValue = armored,
                successNotice = "Inserted public key and verified DOM field",
            )
        }
    }

    private fun confirmEncrypt(
        request: EncryptRequestState,
        plaintext: String,
        recipientFingerprint: String,
        signerFingerprintOrEmpty: String,
        passphrase: String,
    ) {
        if (!OvNative.isLoaded) {
            setPgpError("Native OpenPGP engine is unavailable.")
            return
        }
        if (plaintext.isBlank()) {
            setPgpError("Enter message text before encrypting.")
            return
        }
        if (recipientFingerprint.isBlank()) {
            setPgpError("Select a recipient public key first.")
            return
        }

        val executeEncrypt = {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val json = runCatching {
                        OvNative.nativeEncrypt(
                            plaintext,
                            recipientFingerprint,
                            signerFingerprintOrEmpty,
                            passphrase,
                        )
                    }.getOrDefault("")
                    OvNative.parseEncrypt(json)
                }
                if (!result.ok || result.ciphertext.isBlank()) {
                    setPgpError(result.error.ifBlank { "OpenPGP encryption failed." })
                    return@launch
                }

                uiState = uiState.copy(
                    pgp = uiState.pgp.copy(encryptRequest = null, error = null),
                )

                if (request.writeBackToField && (request.cdpNodeId > 0 || request.backendNodeId > 0)) {
                    writeValueIntoActiveTabField(
                        cdpNodeId = request.cdpNodeId,
                        backendNodeId = request.backendNodeId,
                        newValue = result.ciphertext,
                        successNotice = if (result.signed) {
                            "Signed, encrypted, and verified ciphertext in field"
                        } else {
                            "Encrypted and verified ciphertext in field"
                        },
                    )
                } else {
                    copyToClipboard(
                        label = "OpenPGP Ciphertext",
                        text = result.ciphertext,
                        toastMessage = "Encrypted OpenPGP message copied to clipboard",
                    )
                    showStatus("Encrypted OpenPGP message copied to clipboard", blocked = false)
                }
            }
            Unit
        }

        if (signerFingerprintOrEmpty.isNotBlank()) {
            withSecretKeyAuth(
                subtitle = "Unlock secret key to sign OpenPGP message",
                onAuthorized = executeEncrypt,
            )
        } else {
            executeEncrypt()
        }
    }

    private fun attemptDecrypt(
        armoredCiphertext: String,
        passphrase: String,
        nodeId: String,
        cdpNodeId: Int,
        backendNodeId: Int,
        targetEditable: Boolean,
    ) {
        if (!OvNative.isLoaded) {
            setPgpError("Native OpenPGP engine is unavailable.")
            return
        }
        val cleanArmor = armoredCiphertext.trim()
        if (cleanArmor.isEmpty()) {
            setPgpError("No OpenPGP message block found to decrypt.")
            return
        }

        withSecretKeyAuth(
            subtitle = "Unlock secret key to decrypt OpenPGP message",
        ) {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val json = runCatching {
                        OvNative.nativeDecrypt(cleanArmor, passphrase)
                    }.getOrDefault("")
                    OvNative.parseDecrypt(json)
                }
                when {
                    result.needsPassphrase -> {
                        uiState = uiState.copy(
                            pgp = uiState.pgp.copy(
                                decryptPassphraseRequest = DecryptPassphraseRequestState(
                                    nodeId = nodeId,
                                    cdpNodeId = cdpNodeId,
                                    backendNodeId = backendNodeId,
                                    armoredCiphertext = cleanArmor,
                                    targetEditable = targetEditable,
                                    error = if (passphrase.isNotEmpty()) {
                                        result.error.ifBlank { "Incorrect passphrase." }
                                    } else {
                                        null
                                    },
                                ),
                                error = null,
                            ),
                        )
                    }

                    result.ok -> {
                        uiState = uiState.copy(
                            pgp = uiState.pgp.copy(
                                decryptPassphraseRequest = null,
                                plaintext = PgpPlaintextState(
                                    nodeId = nodeId,
                                    title = "Decrypted Message",
                                    body = result.plaintext,
                                    signatureVerified = result.signatureStatus == "good",
                                    signatureStatus = result.signatureStatus.ifBlank { "unsigned" },
                                    signerFingerprint = result.signerFingerprint.takeIf { it.isNotBlank() },
                                    signerUserId = result.signerUserId.takeIf { it.isNotBlank() },
                                    cdpNodeId = cdpNodeId,
                                    backendNodeId = backendNodeId,
                                    targetEditable = targetEditable,
                                ),
                                error = null,
                            ),
                        )
                    }

                    else -> {
                        val message = result.error.ifBlank { "OpenPGP decryption failed." }
                        val existingPrompt = uiState.pgp.decryptPassphraseRequest
                        if (existingPrompt != null) {
                            uiState = uiState.copy(
                                pgp = uiState.pgp.copy(
                                    decryptPassphraseRequest = existingPrompt.copy(error = message),
                                ),
                            )
                        } else {
                            setPgpError(message)
                        }
                    }
                }
            }
        }
    }

    private fun insertPlaintextIntoField(plaintextState: PgpPlaintextState) {
        uiState = uiState.copy(
            pgp = uiState.pgp.copy(plaintext = null),
        )
        if (!plaintextState.targetEditable ||
            (plaintextState.cdpNodeId <= 0 && plaintextState.backendNodeId <= 0)
        ) {
            return
        }
        writeValueIntoActiveTabField(
            cdpNodeId = plaintextState.cdpNodeId,
            backendNodeId = plaintextState.backendNodeId,
            newValue = plaintextState.body,
            successNotice = "Inserted plaintext and verified DOM field",
        )
    }

    private fun writeValueIntoActiveTabField(
        cdpNodeId: Int,
        backendNodeId: Int,
        newValue: String,
        successNotice: String,
    ) {
        if (!controllersReady) return
        val activeTabId = tabSessionManager.activeTabId.value
        val browser = tabSessionManager.activeBrowser()
        if (browser == null) {
            copyToClipboard(
                label = "OpenPGP Output",
                text = newValue,
                toastMessage = "No active page surface; copied output to clipboard",
            )
            return
        }
        cdpController.writeFieldValue(
            browser = browser,
            nodeId = cdpNodeId,
            backendNodeId = backendNodeId,
            newValue = newValue,
            tabId = activeTabId,
        ) { outcome ->
            if (outcome.ok && outcome.verified) {
                showStatus(successNotice, blocked = false)
            } else {
                copyToClipboard(
                    label = "OpenPGP Output",
                    text = newValue,
                    toastMessage = "Field verification failed; copied output to clipboard",
                )
                val detail = outcome.error.ifBlank { "DOM read-back did not match" }
                setPgpError("Could not verify page field write ($detail); output copied to clipboard.")
            }
        }
    }

    private fun handleContextMenu(target: TabSessionManager.ContextMenuTarget) {
        uiState = uiState.copy(
            contextMenu = ContextMenuUiState(
                tabId = target.tabId,
                linkUrl = target.linkUrl,
                mediaUrl = target.mediaUrl,
                selectedText = target.selectedText,
                classificationKind = target.textClassification?.kind ?: "plain",
                armoredPayload = target.textClassification?.armored.orEmpty(),
            ),
        )
    }

    private fun handleSelectionPgpAction(action: TabSessionManager.SelectionPgpAction) {
        uiState = uiState.copy(
            selectionPgp = SelectionPgpUiState(
                tabId = action.tabId,
                selectedText = action.selectedText,
                kind = action.classification.kind,
                actionLabel = action.classification.actionLabel,
                armored = action.classification.armored,
                fingerprintHint = action.classification.fingerprintHint,
            ),
        )
    }

    private fun dismissContextMenu() {
        if (uiState.contextMenu != null) {
            uiState = uiState.copy(contextMenu = null)
        }
    }

    private fun dismissSelectionPgp() {
        if (uiState.selectionPgp != null) {
            uiState = uiState.copy(selectionPgp = null)
        }
    }

    private fun withSecretKeyAuth(
        subtitle: String,
        onError: (String) -> Unit = ::setPgpError,
        onAuthorized: () -> Unit,
    ) {
        if (!uiState.requireBiometric || isJvmUnitTestRuntime()) {
            onAuthorized()
            return
        }

        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuth = BiometricManager.from(this).canAuthenticate(authenticators)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            onError("Device authentication is required for secret keys, but no lock screen is enrolled.")
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAuthorized()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.authenticate_for_key))
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(promptInfo)
    }

    private fun authenticateBiometricIfRequired(
        subtitle: String,
        onAuthorized: () -> Unit,
    ) {
        withSecretKeyAuth(
            subtitle = subtitle,
            onError = ::setXmrError,
            onAuthorized = onAuthorized,
        )
    }

    // --- Monero Light Wallet (`crates/ov-xmr`) & Browser-Wired BTC -> XMR Swap Actions ---

    private fun openMoneroWalletModal(initialTab: MoneroWalletTab) {
        uiState = uiState.copy(
            xmr = uiState.xmr.copy(
                walletSheetOpen = true,
                walletInitialTab = initialTab,
                swapSheetOpen = false,
                error = null,
            ),
        )
        refreshXmrStatus()
    }

    private fun dismissMoneroWalletModal() {
        uiState = uiState.copy(
            xmr = uiState.xmr.copy(
                walletSheetOpen = false,
                revealedMnemonic = null,
                error = null,
            ),
        )
    }

    private fun openBtcXmrSwapModal() {
        uiState = uiState.copy(
            xmr = uiState.xmr.copy(
                swapSheetOpen = true,
                walletSheetOpen = false,
                error = null,
            ),
        )
        refreshXmrStatus()
    }

    private fun dismissBtcXmrSwapModal() {
        uiState = uiState.copy(
            xmr = uiState.xmr.copy(
                swapSheetOpen = false,
                error = null,
            ),
        )
    }

    private fun openMoneroSendWithPayment(payment: OvNative.MoneroPaymentRequest) {
        uiState = uiState.copy(
            xmr = uiState.xmr.copy(
                walletSheetOpen = true,
                swapSheetOpen = false,
                walletInitialTab = MoneroWalletTab.Send,
                prefillSendAddress = payment.address,
                prefillSendAmount = payment.amountXmr,
                prefillSendDescription = payment.txDescription,
                error = null,
            ),
        )
        refreshXmrStatus()
    }

    private fun refreshXmrStatus() {
        if (!OvNative.isLoaded) return
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                val json = runCatching { OvNative.nativeXmrStatusJson() }.getOrDefault("")
                OvNative.parseXmrStatus(json)
            }
            uiState = uiState.copy(
                xmr = uiState.xmr.copy(status = status),
            )
        }
    }

    private fun createXmrWallet(label: String) {
        if (!OvNative.isLoaded) {
            setXmrError("Native Monero wallet engine is unavailable.")
            return
        }
        val cleanLabel = label.trim().ifEmpty { "Trap Light Wallet" }
        authenticateBiometricIfRequired(subtitle = "Unlock Keystore to wrap new Monero spend key") {
            uiState = uiState.copy(
                xmr = uiState.xmr.copy(creatingOrRestoring = true, error = null),
            )
            lifecycleScope.launch {
                val (seedRes, statusSnap) = withContext(Dispatchers.IO) {
                    val rawSeed = runCatching {
                        OvNative.nativeXmrCreateWalletJson(cleanLabel)
                    }.getOrDefault("")
                    val parsedSeed = OvNative.parseXmrSeedResult(rawSeed)
                    val rawStatus = runCatching { OvNative.nativeXmrStatusJson() }.getOrDefault("")
                    parsedSeed to OvNative.parseXmrStatus(rawStatus)
                }
                if (seedRes.ok) {
                    uiState = uiState.copy(
                        xmr = uiState.xmr.copy(
                            status = statusSnap,
                            creatingOrRestoring = false,
                            newlyCreatedMnemonic = seedRes.mnemonic.takeIf { it.isNotBlank() },
                            error = null,
                        ),
                    )
                    showStatus("Created Monero light wallet", blocked = false)
                } else {
                    uiState = uiState.copy(
                        xmr = uiState.xmr.copy(
                            status = statusSnap,
                            creatingOrRestoring = false,
                            error = seedRes.error.ifBlank { "Monero wallet creation failed." },
                        ),
                    )
                }
            }
        }
    }

    private fun restoreXmrWallet(mnemonic: String, restoreHeight: Long) {
        if (!OvNative.isLoaded) {
            setXmrError("Native Monero wallet engine is unavailable.")
            return
        }
        val cleanMnemonic = mnemonic.trim()
        if (cleanMnemonic.isEmpty()) {
            setXmrError("Enter your 25-word Monero recovery seed.")
            return
        }
        authenticateBiometricIfRequired(subtitle = "Unlock Keystore to wrap restored Monero spend key") {
            uiState = uiState.copy(
                xmr = uiState.xmr.copy(creatingOrRestoring = true, error = null),
            )
            lifecycleScope.launch {
                val (seedRes, statusSnap) = withContext(Dispatchers.IO) {
                    val rawSeed = runCatching {
                        OvNative.nativeXmrRestoreWalletJson(
                            cleanMnemonic,
                            restoreHeight.coerceAtLeast(0L),
                        )
                    }.getOrDefault("")
                    val parsedSeed = OvNative.parseXmrSeedResult(rawSeed)
                    val rawStatus = runCatching { OvNative.nativeXmrStatusJson() }.getOrDefault("")
                    parsedSeed to OvNative.parseXmrStatus(rawStatus)
                }
                if (seedRes.ok) {
                    uiState = uiState.copy(
                        xmr = uiState.xmr.copy(
                            status = statusSnap,
                            creatingOrRestoring = false,
                            newlyCreatedMnemonic = null,
                            revealedMnemonic = null,
                            error = null,
                        ),
                    )
                    showStatus("Restored Monero light wallet", blocked = false)
                } else {
                    uiState = uiState.copy(
                        xmr = uiState.xmr.copy(
                            status = statusSnap,
                            creatingOrRestoring = false,
                            error = seedRes.error.ifBlank { "Monero seed restore failed." },
                        ),
                    )
                }
            }
        }
    }

    private fun syncXmrWallet() {
        if (!OvNative.isLoaded) {
            setXmrError("Native Monero wallet engine is unavailable.")
            return
        }
        if (uiState.xmr.syncing) return
        uiState = uiState.copy(
            xmr = uiState.xmr.copy(syncing = true, error = null),
        )
        lifecycleScope.launch {
            val synced = withContext(Dispatchers.IO) {
                val json = runCatching { OvNative.nativeXmrSyncJson() }.getOrDefault("")
                OvNative.parseXmrStatus(json)
            }
            uiState = uiState.copy(
                xmr = uiState.xmr.copy(
                    status = if (synced.hasWallet || uiState.xmr.status.hasWallet.not()) {
                        synced
                    } else {
                        uiState.xmr.status
                    },
                    syncing = false,
                    error = if (!synced.ok) {
                        synced.error.ifBlank { "Tor Monero sync failed." }
                    } else {
                        null
                    },
                ),
            )
        }
    }

    private fun mintNewXmrSubaddress(label: String) {
        if (!OvNative.isLoaded) {
            setXmrError("Native Monero wallet engine is unavailable.")
            return
        }
        lifecycleScope.launch {
            val (subRes, statusSnap) = withContext(Dispatchers.IO) {
                val subJson = runCatching {
                    OvNative.nativeXmrNewSubaddressJson(label.trim())
                }.getOrDefault("")
                val parsedSub = OvNative.parseXmrNewSubaddressResult(subJson)
                val statusJson = runCatching { OvNative.nativeXmrStatusJson() }.getOrDefault("")
                parsedSub to OvNative.parseXmrStatus(statusJson)
            }
            if (subRes.ok && subRes.address.isNotBlank()) {
                uiState = uiState.copy(
                    xmr = uiState.xmr.copy(status = statusSnap, error = null),
                )
                copyToClipboard(
                    label = "Monero Subaddress",
                    text = subRes.address,
                    toastMessage = "Minted & copied 8… subaddress #${subRes.index}",
                )
            } else {
                setXmrError(subRes.error.ifBlank { "Could not mint Monero subaddress." })
            }
        }
    }

    private fun sendXmr(destination: String, amountXmr: String, sweepAll: Boolean) {
        if (!OvNative.isLoaded) {
            setXmrError("Native Monero wallet engine is unavailable.")
            return
        }
        val cleanDest = destination.trim()
        if (!OvNative.isValidMoneroAddress(cleanDest)) {
            setXmrError("Enter a valid Monero 4… or 8… mainnet address.")
            return
        }
        authenticateBiometricIfRequired(subtitle = "Authenticate to sign and broadcast Monero transaction") {
            uiState = uiState.copy(
                xmr = uiState.xmr.copy(
                    sending = true,
                    lastTxid = null,
                    lastFeeXmr = null,
                    error = null,
                ),
            )
            lifecycleScope.launch {
                val (sendRes, statusSnap) = withContext(Dispatchers.IO) {
                    val rawSend = runCatching {
                        OvNative.nativeXmrSendJson(cleanDest, amountXmr.trim(), sweepAll)
                    }.getOrDefault("")
                    val parsedSend = OvNative.parseXmrSendResult(rawSend)
                    val rawStatus = runCatching { OvNative.nativeXmrStatusJson() }.getOrDefault("")
                    parsedSend to OvNative.parseXmrStatus(rawStatus)
                }
                if (sendRes.ok && sendRes.txid.isNotBlank()) {
                    uiState = uiState.copy(
                        xmr = uiState.xmr.copy(
                            status = statusSnap,
                            sending = false,
                            prefillSendAddress = "",
                            prefillSendAmount = "",
                            prefillSendDescription = "",
                            lastTxid = sendRes.txid,
                            lastFeeXmr = sendRes.feeXmr,
                            error = null,
                        ),
                    )
                    showStatus("Broadcast Monero tx ${sendRes.txid.take(12)}…", blocked = false)
                } else {
                    uiState = uiState.copy(
                        xmr = uiState.xmr.copy(
                            status = statusSnap,
                            sending = false,
                            error = sendRes.error.ifBlank { "Monero transaction failed." },
                        ),
                    )
                }
            }
        }
    }

    private fun setXmrDaemonEndpoint(endpointUrl: String, mode: String) {
        if (!OvNative.isLoaded) {
            setXmrError("Native Monero wallet engine is unavailable.")
            return
        }
        lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) {
                val json = runCatching {
                    OvNative.nativeXmrSetDaemonEndpointJson(endpointUrl.trim(), mode.trim())
                }.getOrDefault("")
                OvNative.parseXmrStatus(json)
            }
            if (updated.ok) {
                uiState = uiState.copy(
                    xmr = uiState.xmr.copy(status = updated, error = null),
                )
                showStatus("Saved Monero Tor sync endpoint", blocked = false)
            } else {
                setXmrError(updated.error.ifBlank { "Invalid Monero daemon endpoint." })
            }
        }
    }

    private fun revealXmrSeed() {
        if (!OvNative.isLoaded) {
            setXmrError("Native Monero wallet engine is unavailable.")
            return
        }
        authenticateBiometricIfRequired(subtitle = "Authenticate to reveal 25-word Monero recovery seed") {
            lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) {
                    val json = runCatching { OvNative.nativeXmrExportSeedJson() }.getOrDefault("")
                    OvNative.parseXmrSeedResult(json)
                }
                if (res.ok && res.mnemonic.isNotBlank()) {
                    uiState = uiState.copy(
                        xmr = uiState.xmr.copy(revealedMnemonic = res.mnemonic, error = null),
                    )
                } else {
                    setXmrError(res.error.ifBlank { "Could not export Monero recovery seed." })
                }
            }
        }
    }

    private fun deleteXmrWallet() {
        if (!OvNative.isLoaded) return
        authenticateBiometricIfRequired(subtitle = "Authenticate to delete Monero wallet from device") {
            lifecycleScope.launch {
                val (deleted, statusSnap) = withContext(Dispatchers.IO) {
                    val ok = runCatching { OvNative.nativeXmrDeleteWallet() }.getOrDefault(false)
                    val json = runCatching { OvNative.nativeXmrStatusJson() }.getOrDefault("")
                    ok to OvNative.parseXmrStatus(json)
                }
                if (deleted) {
                    uiState = uiState.copy(
                        xmr = uiState.xmr.copy(
                            status = statusSnap,
                            revealedMnemonic = null,
                            newlyCreatedMnemonic = null,
                            lastTxid = null,
                            lastFeeXmr = null,
                            activeSwapTabMap = emptyMap(),
                            error = null,
                        ),
                    )
                    showStatus("Deleted Monero wallet", blocked = false)
                } else {
                    setXmrError("Could not delete Monero wallet.")
                }
            }
        }
    }

    private fun fetchBtcSwapQuotes(btcAmount: String) {
        if (!OvNative.isLoaded) {
            setXmrError("Native swap quote aggregator is unavailable.")
            return
        }
        val cleanAmount = btcAmount.trim()
        if (cleanAmount.isEmpty()) {
            setXmrError("Enter a BTC amount first.")
            return
        }
        uiState = uiState.copy(
            xmr = uiState.xmr.copy(
                quotesBtcAmount = cleanAmount,
                quotesLoading = true,
                error = null,
            ),
        )
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                val json = runCatching {
                    OvNative.nativeXmrFetchBtcSwapQuotesJson(cleanAmount)
                }.getOrDefault("")
                OvNative.parseBtcSwapQuotes(json)
            }
            uiState = uiState.copy(
                xmr = uiState.xmr.copy(
                    quotes = if (res.ok) res.quotes else emptyList(),
                    quotesSourceOnion = res.sourceOnion,
                    quotesFetchedAtEpochSecs = res.fetchedAtEpochSecs,
                    quotesHasFetched = true,
                    quotesLoading = false,
                    error = if (!res.ok) {
                        res.error.ifBlank { "Could not fetch Tor BTC -> XMR quotes." }
                    } else {
                        null
                    },
                ),
            )
        }
    }

    private fun launchBtcSwapQuoteInBrowser(
        quote: OvNative.BtcSwapQuoteItem,
        btcAmount: String,
    ) {
        if (!OvNative.isLoaded || !controllersReady) return
        val cleanAmount = btcAmount.trim().ifEmpty { uiState.xmr.quotesBtcAmount.ifBlank { "0.01" } }
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                var createdMnemonic: String? = null
                val initialStatus = OvNative.parseXmrStatus(
                    runCatching { OvNative.nativeXmrStatusJson() }.getOrDefault(""),
                )
                if (!initialStatus.hasWallet) {
                    val created = OvNative.parseXmrSeedResult(
                        runCatching {
                            OvNative.nativeXmrCreateWalletJson("Trap Light Wallet")
                        }.getOrDefault(""),
                    )
                    if (!created.ok) {
                        return@withContext Triple(null, initialStatus, created.error)
                    }
                    createdMnemonic = created.mnemonic.takeIf { it.isNotBlank() }
                }
                val actionJson = OvNative.buildSwapCreateActionJson(
                    provider = quote.provider,
                    btcAmount = cleanAmount,
                    quotedXmr = quote.estimatedXmr,
                    launchUrl = quote.launchUrl,
                )
                val swapRes = OvNative.parseXmrSwapsAction(
                    runCatching { OvNative.nativeXmrSwapsActionJson(actionJson) }.getOrDefault(""),
                )
                val updatedStatus = OvNative.parseXmrStatus(
                    runCatching { OvNative.nativeXmrStatusJson() }.getOrDefault(""),
                )
                if (!swapRes.ok || swapRes.swap == null) {
                    Triple(null, updatedStatus, swapRes.error.ifBlank { "Could not create swap subaddress." })
                } else {
                    Triple(swapRes.swap to createdMnemonic, updatedStatus, "")
                }
            }

            val createdPair = outcome.first
            val updatedStatus = outcome.second
            val err = outcome.third
            if (createdPair == null) {
                uiState = uiState.copy(
                    xmr = uiState.xmr.copy(
                        status = updatedStatus,
                        error = err,
                    ),
                )
                return@launch
            }

            val trackedSwap = createdPair.first
            val createdSeed = createdPair.second
            val targetTabId = tabSessionManager.activeTabId.value ?: tabSessionManager.ensureInitialTab()
            uiState = uiState.copy(
                xmr = uiState.xmr.copy(
                    status = updatedStatus,
                    swapSheetOpen = false,
                    walletSheetOpen = false,
                    newlyCreatedMnemonic = createdSeed ?: uiState.xmr.newlyCreatedMnemonic,
                    activeSwapTabMap = uiState.xmr.activeSwapTabMap + (targetTabId to trackedSwap.swapId),
                    dismissedHudSwapIds = uiState.xmr.dismissedHudSwapIds - trackedSwap.swapId,
                    error = null,
                ),
            )
            navigate(quote.launchUrl, targetTabId)
        }
    }

    private fun openTrackedSwapPage(swap: OvNative.TrackedBtcXmrSwap) {
        if (!controllersReady) return
        val targetUrl = swap.orderUrl.ifBlank { swap.launchUrl }.trim()
        if (targetUrl.isEmpty()) return
        val targetTabId = tabSessionManager.activeTabId.value ?: tabSessionManager.ensureInitialTab()
        uiState = uiState.copy(
            xmr = uiState.xmr.copy(
                swapSheetOpen = false,
                activeSwapTabMap = uiState.xmr.activeSwapTabMap + (targetTabId to swap.swapId),
                dismissedHudSwapIds = uiState.xmr.dismissedHudSwapIds - swap.swapId,
            ),
        )
        navigate(targetUrl, targetTabId)
    }

    private fun dismissTrackedSwap(swapId: String) {
        if (!OvNative.isLoaded || swapId.isBlank()) return
        lifecycleScope.launch {
            val updatedStatus = withContext(Dispatchers.IO) {
                runCatching {
                    OvNative.nativeXmrSwapsActionJson(OvNative.buildSwapDismissActionJson(swapId))
                }
                val json = runCatching { OvNative.nativeXmrStatusJson() }.getOrDefault("")
                OvNative.parseXmrStatus(json)
            }
            uiState = uiState.copy(
                xmr = uiState.xmr.copy(
                    status = updatedStatus,
                    activeSwapTabMap = uiState.xmr.activeSwapTabMap.filterValues { it != swapId },
                    dismissedHudSwapIds = uiState.xmr.dismissedHudSwapIds + swapId,
                ),
            )
        }
    }

    private fun insertXmrSubaddressIntoField(field: PgpFieldState) {
        if (!OvNative.isLoaded) {
            showStatus("Native Monero wallet engine is unavailable.", blocked = true)
            return
        }
        val activeTabId = if (controllersReady) tabSessionManager.activeTabId.value.orEmpty() else ""
        val mappedSwapSubaddress = uiState.xmr.activeSwapForTab(activeTabId)
            ?.subaddress
            ?.takeIf { it.isNotBlank() }

        lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                if (!mappedSwapSubaddress.isNullOrBlank()) {
                    val statusSnap = OvNative.parseXmrStatus(
                        runCatching { OvNative.nativeXmrStatusJson() }.getOrDefault(""),
                    )
                    return@withContext Triple(mappedSwapSubaddress, null as String?, statusSnap)
                }

                var createdSeed: String? = null
                var statusSnap = OvNative.parseXmrStatus(
                    runCatching { OvNative.nativeXmrStatusJson() }.getOrDefault(""),
                )
                if (!statusSnap.hasWallet) {
                    val seedRes = OvNative.parseXmrSeedResult(
                        runCatching {
                            OvNative.nativeXmrCreateWalletJson("Trap Light Wallet")
                        }.getOrDefault(""),
                    )
                    if (!seedRes.ok) {
                        return@withContext Triple("", null, statusSnap)
                    }
                    createdSeed = seedRes.mnemonic.takeIf { it.isNotBlank() }
                    statusSnap = OvNative.parseXmrStatus(
                        runCatching { OvNative.nativeXmrStatusJson() }.getOrDefault(""),
                    )
                }

                var subaddress = statusSnap.activeSubaddress.trim()
                if (subaddress.isEmpty() || !subaddress.startsWith("8")) {
                    val minted = OvNative.parseXmrNewSubaddressResult(
                        runCatching {
                            OvNative.nativeXmrNewSubaddressJson("Swap Payout")
                        }.getOrDefault(""),
                    )
                    if (minted.ok && minted.address.isNotBlank()) {
                        subaddress = minted.address
                    }
                    statusSnap = OvNative.parseXmrStatus(
                        runCatching { OvNative.nativeXmrStatusJson() }.getOrDefault(""),
                    )
                }
                Triple(subaddress.ifBlank { statusSnap.primaryAddress }, createdSeed, statusSnap)
            }

            val targetSubaddress = resolved.first
            val createdSeed = resolved.second
            val statusSnap = resolved.third
            uiState = uiState.copy(
                xmr = uiState.xmr.copy(
                    status = statusSnap,
                    newlyCreatedMnemonic = createdSeed ?: uiState.xmr.newlyCreatedMnemonic,
                ),
            )
            if (targetSubaddress.isBlank()) {
                showStatus("Could not generate Monero 8… subaddress.", blocked = true)
                return@launch
            }
            writeValueIntoActiveTabField(
                cdpNodeId = field.cdpNodeId,
                backendNodeId = field.backendNodeId,
                newValue = targetSubaddress,
                successNotice = "Inserted 8… Monero subaddress and verified DOM field",
            )
        }
    }

    private fun maybeAttachDetectedBtcDepositToActiveSwap(
        tabId: String,
        url: String,
        nodes: List<CdpDomPgpController.DetectedPgpNode>,
    ) {
        if (!OvNative.isLoaded || nodes.isEmpty()) return
        val btcNode = nodes.firstOrNull {
            it.kind == "btc_deposit_address" && it.text.isNotBlank()
        } ?: return

        val candidateSwap = uiState.xmr.activeSwapForTab(tabId)
            ?.takeIf { it.status == "awaiting_form" || it.status == "awaiting_btc_deposit" }
            ?: return

        val detectedAddr = btcNode.text.trim()
        val detectedAmt = btcNode.fingerprintHint.trim()
        val cleanOrderUrl = url.trim()
        if (candidateSwap.status == "awaiting_btc_deposit" &&
            candidateSwap.btcDepositAddress == detectedAddr &&
            (cleanOrderUrl.isEmpty() || candidateSwap.orderUrl == cleanOrderUrl)
        ) {
            return
        }

        lifecycleScope.launch {
            val updatedStatus = withContext(Dispatchers.IO) {
                val actionJson = OvNative.buildSwapUpdateOrderActionJson(
                    swapId = candidateSwap.swapId,
                    btcDepositAddress = detectedAddr,
                    btcDepositAmount = detectedAmt.ifBlank { candidateSwap.btcAmount },
                    providerOrderId = candidateSwap.providerOrderId,
                    orderUrl = cleanOrderUrl.ifBlank { candidateSwap.orderUrl },
                    status = "awaiting_btc_deposit",
                )
                runCatching { OvNative.nativeXmrSwapsActionJson(actionJson) }
                val rawStatus = runCatching { OvNative.nativeXmrStatusJson() }.getOrDefault("")
                OvNative.parseXmrStatus(rawStatus)
            }
            uiState = uiState.copy(
                xmr = uiState.xmr.copy(
                    status = updatedStatus,
                    dismissedHudSwapIds = uiState.xmr.dismissedHudSwapIds - candidateSwap.swapId,
                ),
            )
        }
    }

    private fun setXmrError(message: String) {
        uiState = uiState.copy(
            xmr = uiState.xmr.copy(error = message),
        )
    }

    private fun resolveInitialUrl(savedInstanceState: Bundle?, launchIntent: Intent?): String {
        val restored = savedInstanceState?.getString(STATE_URL)?.trim()
        if (!restored.isNullOrEmpty()) return restored
        val actionViewUrl = launchIntent
            ?.takeIf { it.action == Intent.ACTION_VIEW }
            ?.dataString
            ?.trim()
        if (!actionViewUrl.isNullOrEmpty()) return actionViewUrl
        return getString(R.string.start_url)
    }

    private fun showStatus(message: String, blocked: Boolean) {
        uiState = uiState.copy(
            status = message,
            blocked = blocked,
            pgp = uiState.pgp.copy(error = null),
        )
    }

    private fun setPgpError(message: String) {
        uiState = uiState.copy(
            pgp = uiState.pgp.copy(error = message),
        )
    }

    private fun clearStatusAndPgpError() {
        if (uiState.status.isNotEmpty() || uiState.blocked || uiState.pgp.error != null) {
            uiState = uiState.copy(
                status = "",
                blocked = false,
                pgp = uiState.pgp.copy(error = null),
            )
        }
    }

    private fun copyToClipboard(label: String, text: String, toastMessage: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
    }

    @VisibleForTesting
    internal fun activeCefpackSessionForTest(): CefpackSession? =
        if (controllersReady) tabSessionManager.activeBrowser() else null

    @VisibleForTesting
    internal fun activeTabIdForTest(): String? =
        if (controllersReady) tabSessionManager.activeTabId.value else null

    @VisibleForTesting
    internal fun cdpControllerForTest(): CdpDomPgpController? =
        if (controllersReady) cdpController else null

    @VisibleForTesting
    internal fun loadZeroJsDocumentForTest(
        html: String,
        displayUrl: String,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (!controllersReady) {
            onComplete(false)
            return
        }
        activeSnapshotView = null
        clearStatusAndPgpError()
        tabSessionManager.loadInlineHtmlForTest(
            html = html,
            displayUrl = displayUrl,
            onComplete = onComplete,
        )
    }

    private data class ControllerSnapshot(
        val tabs: List<TabSessionManager.TabState>,
        val activeTabId: String?,
        val nodesByTab: Map<String, List<CdpDomPgpController.DetectedPgpNode>>,
        val podsRetracted: Boolean,
    )
}

private fun CdpDomPgpController.DetectedPgpNode.toPgpFieldState(): PgpFieldState =
    PgpFieldState(
        nodeId = key,
        kind = kind,
        label = actionLabel,
        bounds = NormalizedBounds(
            left = normalizedLeft,
            top = normalizedTop,
            right = normalizedRight,
            bottom = normalizedBottom,
        ),
        preview = text.take(240),
        armored = text,
        cdpNodeId = nodeId,
        backendNodeId = backendNodeId,
        fingerprintHint = fingerprintHint,
        isSearchLike = isSearchLike,
    )

private fun OnionBackStackManager.BackSnapshotEntry.toBackPreviewState(): BackPreviewState =
    BackPreviewState(
        url = url,
        title = title,
        scrollOffset = IntOffset(0, scrollOffsetY),
        fields = nodes.map { it.toPgpFieldState() },
        frameSnapshot = imageBitmap,
    )
