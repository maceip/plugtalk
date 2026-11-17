package dev.onionvault

import android.app.HandoffActivityData
import android.app.HandoffActivityDataRequestInfo
import android.app.PictureInPictureUiState
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.cefrium.CefriumPickerHandler
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
import dev.onionvault.ui.KeyInfo
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
 *   1. [TabSessionManager] (ephemeral `CefriumProfile.incognito()` multi-tab sessions),
 *   2. [CdpDomPgpController] (100% zero-JavaScript in-process CDP DOM OpenPGP scanner & mutator),
 *   3. [OnionBackStackManager] (PixelCopy snapshot stack powering predictive-back gestures),
 *   4. [OvNative] (Arti Tor SOCKS5/isolation client + Sequoia Keystore OpenPGP engine), and
 *   5. [TrapPreferences] (persisted `SharedPreferences` backing every user setting).
 */
class MainActivity : FragmentActivity() {

    private companion object {
        const val TAG = "Trap"
        const val STATE_URL = "url"
        const val ZOOM_STEP_PERCENT = 15

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
    private var hostSurfaceFrame: FrameLayout? = null
    private var isTransitioningToPipState: Boolean = false
    private var pipReceiverRegistered: Boolean = false
    private var pendingLanNavigationUrl: String? = null
    private var pendingHandoffScrollY: Int = 0
    private var pendingColorPickerCallback: CefriumPickerHandler.ColorCallback? = null
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
                callback?.set(color)
            } else {
                callback?.cancel()
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

    override fun onCreate(savedInstanceState: Bundle?) {
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
                onLoadLegalLines = { name ->
                    withContext(Dispatchers.IO) {
                        LegalNotices.read(this@MainActivity, name)
                    }
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
        syncPipParams()
    }

    override fun onStop() {
        if (::screenRecordingGuard.isInitialized) {
            screenRecordingGuard.onStop(this)
        }
        super.onStop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
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
                dismissTransientOverlaysForPip()
                if (controllersReady) {
                    tabSessionManager.setTouchSuppressed(false)
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
        if (isInPictureInPictureMode) {
            dismissTransientOverlaysForPip()
            if (controllersReady) {
                tabSessionManager.setTouchSuppressed(false)
            }
        }
        val hideChrome = PipController.resolveChromeHidden(
            isInPictureInPictureMode = isInPictureInPictureMode,
            isTransitioningToPip = isTransitioningToPipState,
        )
        uiState = uiState.copy(
            isInPipMode = isInPictureInPictureMode,
            isPipChromeHidden = hideChrome,
        )
        syncPipParams()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
        pendingColorPickerCallback?.cancel()
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
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    addOnLayoutChangeListener(viewportLayoutListener)
                }
            },
            update = { hostFrame ->
                hostSurfaceFrame = hostFrame
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
        startTorPolling()
    }

    private fun ensureControllersInitialized(initialUrl: String) {
        if (!controllersReady) {
            cdpController = CdpDomPgpController(
                onNodesDetected = { tabId, _, domHtml, _ ->
                    if (controllersReady) {
                        tabSessionManager.recordTabDomHtml(tabId, domHtml)
                    }
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
        val tabId = tabSessionManager.ensureInitialTab(
            if (isBlankOrStartUrl(startTarget)) "about:blank" else "about:blank",
        )
        if (prefs.defaultZoomPercent != 100) {
            tabSessionManager.setZoomPercent(prefs.defaultZoomPercent, tabId)
        }
        if (!isBlankOrStartUrl(startTarget)) {
            navigate(startTarget, tabId)
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
            ) { tabs, activeId, nodesByTab, _ ->
                ControllerSnapshot(
                    tabs = tabs,
                    activeTabId = activeId,
                    nodesByTab = nodesByTab,
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
        val currentUrl = uiState.url
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
        val updatedPrompt = if (uiState.clearnetPrompt == null &&
            pendingHost.isNotEmpty() &&
            !snapshot.hiddenServiceOnly
        ) {
            ClearnetPromptState(
                host = pendingHost,
                pendingUrl = null,
                suggestedPattern = OvNative.suggestGlob(pendingHost),
            )
        } else {
            uiState.clearnetPrompt
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

        activeSnapshotView = null
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
            if (cachedPrev != null) {
                activeSnapshotView = cachedPrev.toBackPreviewState()
                uiState = uiState.copy(
                    viewingSnapshot = true,
                    backPreview = activeSnapshotView,
                )
            }
            refreshTorStatusNow()
        } else if (cachedPrev != null) {
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
        tabSessionManager.selectTab(tabId)
        refreshTorStatusNow()
        syncPipParams()
    }

    private fun closeTab(tabId: String) {
        if (!controllersReady) return
        activeSnapshotView = null
        tabSessionManager.closeTab(tabId)
        refreshTorStatusNow()
        syncPipParams()
    }

    private fun goHome() {
        navigate("about:blank")
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
            return
        }
        val computed = PipController.computeViewportWindowRect(view) ?: return
        if (computed != viewportSourceRect) {
            viewportSourceRect = computed
            syncPipParams()
        }
    }

    private fun dismissTransientOverlaysForPip() {
        if (uiState.contextMenu != null ||
            uiState.selectionPgp != null ||
            uiState.clearnetPrompt != null ||
            uiState.pgp.encryptRequest != null ||
            uiState.pgp.decryptPassphraseRequest != null ||
            uiState.pgp.plaintext != null
        ) {
            uiState = uiState.copy(
                contextMenu = null,
                selectionPgp = null,
                clearnetPrompt = null,
                pgp = uiState.pgp.copy(
                    encryptRequest = null,
                    decryptPassphraseRequest = null,
                    plaintext = null,
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
        callback: CefriumPickerHandler.ColorCallback,
    ): Boolean {
        if (!WebPickersBridge.isEyeDropperAvailable(this)) {
            return false
        }
        pendingColorPickerCallback?.cancel()
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

    private fun applySystemBarsAppearance(mode: TrapThemeMode) {
        val lightBars = mode == TrapThemeMode.Light
        runCatching {
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
            setPgpError("Device authentication is required for secret keys, but no lock screen is enrolled.")
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
                    setPgpError(errString.toString())
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
    internal fun activeCefriumBrowserForTest(): com.cefrium.CefriumBrowser? =
        if (controllersReady) tabSessionManager.activeBrowser() else null

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
    )

private fun OnionBackStackManager.BackSnapshotEntry.toBackPreviewState(): BackPreviewState =
    BackPreviewState(
        url = url,
        title = title,
        scrollOffset = IntOffset(0, scrollOffsetY),
        fields = nodes.map { it.toPgpFieldState() },
        frameSnapshot = imageBitmap,
    )
