package dev.onionvault

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.ContactsPickerSessionContract
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import dev.onionvault.platform.CrossDeviceHandoffController
import dev.onionvault.platform.DiagnosticProfilingController
import dev.onionvault.platform.LanAccessController
import dev.onionvault.platform.MediaDisplayNormalizer
import dev.onionvault.platform.PerformanceAndRenderController
import dev.onionvault.platform.ScreenRecordingGuard
import dev.onionvault.platform.SurfaceFilterMode
import dev.onionvault.platform.TouchpadPointerBridge
import dev.onionvault.platform.WebPickersBridge
import dev.onionvault.ui.BrowserScreen
import dev.onionvault.ui.BrowserUiState
import dev.onionvault.ui.EncryptRequestState
import dev.onionvault.ui.NormalizedBounds
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.PgpOverlayState
import dev.onionvault.ui.SettingsSheet
import dev.onionvault.ui.TrapPreferences
import dev.onionvault.ui.TrapTheme
import java.io.File
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class PlatformUpgradesTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(TrapPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun manifest_and_build_declare_api37_permissions_16kb_alignment_and_zero_read_contacts() {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("android/app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile }
        assertNotNull("Expected AndroidManifest.xml", manifestFile)

        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = dbf.newDocumentBuilder().parse(manifestFile!!)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val permissions = mutableSetOf<String>()
        val permNodes = doc.getElementsByTagName("uses-permission")
        for (i in 0 until permNodes.length) {
            val el = permNodes.item(i) as Element
            permissions.add(el.getAttributeNS(androidNs, "name"))
        }

        assertTrue(permissions.contains("android.permission.DETECT_SCREEN_RECORDING"))
        assertTrue(permissions.contains("android.permission.ACCESS_LOCAL_NETWORK"))
        assertFalse(
            "Scoped ACTION_PICK_CONTACTS must never declare READ_CONTACTS",
            permissions.contains("android.permission.READ_CONTACTS"),
        )

        val gradleFile = listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
            File("android/app/build.gradle.kts"),
        ).firstOrNull { it.isFile && it.readText().contains("targetSdk") }
        assertNotNull("Expected app build.gradle.kts", gradleFile)
        val gradleSource = gradleFile!!.readText()
        assertTrue(gradleSource.contains("compileSdk = 37"))
        assertTrue(gradleSource.contains("targetSdk = 37"))

        val buildRsFile = listOf(
            File("../build.rs"),
            File("../../build.rs"),
            File("build.rs"),
        ).firstOrNull { it.isFile }
        assertNotNull("Expected workspace build.rs", buildRsFile)
        assertTrue(buildRsFile!!.readText().contains("-Wl,-z,max-page-size=16384"))
    }

    @Test
    fun screen_recording_guard_catches_preexisting_recording_and_unregisters_on_stop() {
        var registeredCallback: Consumer<Int>? = null
        var unregisterCount = 0
        val observedStates = mutableListOf<Boolean>()

        val fakeRegistrar = object : ScreenRecordingGuard.CallbackRegistrar {
            override fun register(executor: Executor, callback: Consumer<Int>): Int {
                registeredCallback = callback
                return ScreenRecordingGuard.STATE_VISIBLE
            }

            override fun unregister(callback: Consumer<Int>) {
                if (callback === registeredCallback) {
                    unregisterCount++
                    registeredCallback = null
                }
            }
        }

        val guard = ScreenRecordingGuard(
            sdkIntProvider = { 35 },
            registrar = fakeRegistrar,
            onStateChanged = { observedStates.add(it) },
        )

        guard.onStart(context)
        assertTrue("Immediate return value STATE_VISIBLE must be caught on launch", guard.isRecordingVisible)
        assertEquals(true, observedStates.last())

        registeredCallback!!.accept(ScreenRecordingGuard.STATE_NOT_VISIBLE)
        assertFalse(guard.isRecordingVisible)

        registeredCallback!!.accept(ScreenRecordingGuard.STATE_VISIBLE)
        assertTrue(guard.isRecordingVisible)

        guard.onStop(context)
        assertEquals(1, unregisterCount)
        assertFalse(guard.isRecordingVisible)
    }

    @Test
    fun browser_screen_displays_recording_warning_and_suspends_pgp_overlays_while_recording() {
        val sampleField = PgpFieldState(
            nodeId = "f1",
            kind = "textarea",
            label = "Encrypt Field",
            bounds = NormalizedBounds(0.1f, 0.2f, 0.8f, 0.4f),
            preview = "hello",
            armored = null,
            cdpNodeId = 10,
            backendNodeId = 20,
        )
        val encryptReq = EncryptRequestState(
            nodeId = "f1",
            cdpNodeId = 10,
            backendNodeId = 20,
            initialText = "secret message",
            writeBackToField = true,
        )

        var recordingActive by mutableStateOf(true)
        compose.setContent {
            TrapTheme {
                BrowserScreen(
                    state = BrowserUiState(
                        url = "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/",
                        title = "DuckDuckGo",
                        isScreenRecordingActive = recordingActive,
                        pgp = PgpOverlayState(
                            fields = listOf(sampleField),
                            encryptRequest = encryptReq,
                        ),
                    ),
                    onNavigate = {},
                    onAllowClearnet = { _, _, _ -> },
                    onBlockClearnet = {},
                    onRevokeClearnetRule = {},
                    onClearClearnetRules = {},
                    onFieldAction = {},
                    onInsertMyPubKey = {},
                    onConfirmEncrypt = { _, _, _, _, _ -> },
                    onDismissEncrypt = {},
                    onSubmitDecryptPassphrase = { _, _ -> },
                    onDismissDecryptPassphrase = {},
                    onCopyPlaintext = {},
                    onInsertPlaintext = {},
                    onDismissPlaintext = {},
                    onReloadLive = {},
                    onBack = {},
                    onForward = {},
                    onReload = {},
                    onNewTab = {},
                    onSelectTab = {},
                    onCloseTab = {},
                    onHome = {},
                    onGenerateKey = { _, _ -> },
                    onImportKey = { _, _ -> },
                    onExportKey = {},
                    onSetDefaultKey = {},
                    onDeleteKey = {},
                    onToggleRouting = {},
                    onToggleRequireBiometric = {},
                    onResetTorIsolation = {},
                    onSelectThemeFamily = {},
                    onSelectThemeMode = {},
                    onSelectDefaultZoom = {},
                    onClearSession = {},
                    onZoomIn = {},
                    onZoomOut = {},
                    onDismissStatus = {},
                    onContextMenuOpenNewTab = {},
                    onContextMenuOpenCurrentTab = {},
                    onCopyText = {},
                    onDecryptArmoredText = {},
                    onEncryptSelectedText = {},
                    onDismissContextMenu = {},
                    onDismissSelectionPgp = {},
                    onOverlayOpenChanged = {},
                    onLoadLegalLines = { emptyList() },
                )
            }
        }

        compose.onNodeWithTag("screen_recording_warning_banner").assertExists()
        compose.onAllNodesWithTag("pgp_field_overlay").assertCountEquals(0)
        compose.onAllNodesWithTag("encrypt_picker_sheet").assertCountEquals(0)

        compose.runOnIdle {
            recordingActive = false
        }
        compose.waitForIdle()

        compose.onAllNodesWithTag("screen_recording_warning_banner").assertCountEquals(0)
        compose.onNodeWithTag("pgp_field_overlay").assertExists()
        compose.onNodeWithTag("encrypt_picker_sheet").assertExists()
    }

    @Test
    fun media_display_normalizer_caps_mixed_hdr_headroom_and_manages_loudness_controllers() {
        var createdCount = 0
        var closedCount = 0
        val normalizer = MediaDisplayNormalizer(
            sdkIntProvider = { 35 },
            controllerFactory = {
                createdCount++
                AutoCloseable { closedCount++ }
            },
        )

        assertEquals(
            MediaDisplayNormalizer.SDR_PAGE_HEADROOM,
            normalizer.resolveDesiredHdrHeadroom(hasEmbeddedMedia = false, isFullscreenVideo = false),
            0.001f,
        )
        assertEquals(
            MediaDisplayNormalizer.MIXED_PAGE_HDR_HEADROOM_CAP,
            normalizer.resolveDesiredHdrHeadroom(hasEmbeddedMedia = true, isFullscreenVideo = false),
            0.001f,
        )
        assertEquals(
            MediaDisplayNormalizer.FULLSCREEN_VIDEO_HDR_HEADROOM,
            normalizer.resolveDesiredHdrHeadroom(hasEmbeddedMedia = true, isFullscreenVideo = true),
            0.001f,
        )

        assertTrue(normalizer.attachAudioSession(42))
        assertTrue(normalizer.attachAudioSession(42)) // deduplicated
        assertEquals(1, createdCount)
        assertEquals(setOf(42), normalizer.activeSessionIds)

        assertTrue(normalizer.attachAudioSession(77))
        assertEquals(2, createdCount)

        normalizer.releaseAudioSession(42)
        assertEquals(1, closedCount)
        assertEquals(setOf(77), normalizer.activeSessionIds)

        normalizer.releaseAll()
        assertEquals(2, closedCount)
        assertTrue(normalizer.activeSessionIds.isEmpty())
    }

    @Test
    fun performance_controller_throttles_on_low_cpu_gpu_headroom_steps_arr_and_defines_agsl_filters() {
        var fakeNowMs = 10_000L
        var fakeCpu = 0.45f
        var fakeGpu = 0.50f
        val fakeEnv = object : PerformanceAndRenderController.Environment {
            override val sdkInt: Int = 36
            override fun nowElapsedRealtimeMs(): Long = fakeNowMs
            override fun minCpuHeadroomIntervalMs(): Long = 2_000L
            override fun minGpuHeadroomIntervalMs(): Long = 2_000L
            override fun queryCpuHeadroom(): Float = fakeCpu
            override fun queryGpuHeadroom(): Float = fakeGpu
            override fun hasArrSupport(window: Window): Boolean = true
            override fun suggestedFrameRate(window: Window, highCategory: Boolean): Float =
                if (highCategory) 120f else 60f
        }
        val controller = PerformanceAndRenderController(fakeEnv)

        // Single idle tab does not sample or throttle.
        val idleSnap = controller.sampleHeadroomIfDue(tabCount = 1, hasActiveMedia = false)
        assertFalse(idleSnap.shouldThrottleBackground)

        // Multi-tab healthy headroom -> normal poll interval, no throttling.
        val healthy = controller.sampleHeadroomIfDue(tabCount = 3, hasActiveMedia = false)
        assertFalse(healthy.shouldThrottleBackground)
        assertEquals(PerformanceAndRenderController.NORMAL_POLL_INTERVAL_MS, healthy.recommendedPollIntervalMs)

        // Advance past min interval with tight GPU headroom -> triggers background throttling.
        fakeNowMs += 2_500L
        fakeGpu = 0.10f
        val tight = controller.sampleHeadroomIfDue(tabCount = 3, hasActiveMedia = false)
        assertTrue(tight.shouldThrottleBackground)
        assertEquals(PerformanceAndRenderController.THROTTLED_POLL_INTERVAL_MS, tight.recommendedPollIntervalMs)

        // ARR steps up to 120Hz on fling/scroll and steps down to 60Hz on static reading pages.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertEquals(120f, controller.applyAdaptiveRefreshRate(activity.window, isScrollingOrAnimating = true), 0.01f)
        assertEquals(120f, activity.window.attributes.preferredRefreshRate, 0.01f)
        assertEquals(60f, controller.applyAdaptiveRefreshRate(activity.window, isScrollingOrAnimating = false), 0.01f)
        assertEquals(60f, activity.window.attributes.preferredRefreshRate, 0.01f)

        // AGSL shader & preference persistence check.
        assertNull(PerformanceAndRenderController.agslShaderFor(SurfaceFilterMode.None))
        listOf(
            SurfaceFilterMode.ReaderWarm,
            SurfaceFilterMode.EInkGrayscale,
            SurfaceFilterMode.DarkContrast,
        ).forEach { mode ->
            val shader = PerformanceAndRenderController.agslShaderFor(mode)
            assertNotNull("Expected AGSL shader for $mode", shader)
            assertTrue(shader!!.contains("half4 main(half4 c)"))
        }

        val prefs = TrapPreferences(context)
        assertEquals(SurfaceFilterMode.None, prefs.surfaceFilterMode)
        prefs.surfaceFilterMode = SurfaceFilterMode.EInkGrayscale
        assertEquals(SurfaceFilterMode.EInkGrayscale, TrapPreferences(context).surfaceFilterMode)
    }

    @Test
    fun diagnostic_profiling_registers_api36_and_api37_triggers_and_retains_local_only_files() {
        assertTrue(DiagnosticProfilingController.triggersForSdk(35).isEmpty())

        val api36Triggers = DiagnosticProfilingController.triggersForSdk(36).map { it.triggerType }
        assertEquals(
            listOf(
                DiagnosticProfilingController.TRIGGER_OOM,
                DiagnosticProfilingController.TRIGGER_ANOMALY,
                DiagnosticProfilingController.TRIGGER_KILL_EXCESSIVE_CPU,
            ),
            api36Triggers,
        )

        val api37Triggers = DiagnosticProfilingController.triggersForSdk(37).map { it.triggerType }
        assertEquals(4, api37Triggers.size)
        assertTrue(api37Triggers.contains(DiagnosticProfilingController.TRIGGER_COLD_START))

        val diagDir = File(context.filesDir, "diagnostics-test").apply {
            deleteRecursively()
            mkdirs()
        }
        repeat(11) { idx ->
            val file = DiagnosticProfilingController.recordLocalDiagnosticMetadata(
                diagnosticsDir = diagDir,
                triggerType = DiagnosticProfilingController.TRIGGER_ANOMALY,
                errorCode = 0,
                resultFilePath = if (idx == 10) "https://telemetry.invalid/leak.pb" else "/data/user/0/dev.onionvault/files/trace_$idx.pb",
                errorMessage = null,
                nowEpochMillis = 1_700_000_000_000L + idx * 1_000L,
            )
            file.setLastModified(1_700_000_000_000L + idx * 1_000L)
        }
        DiagnosticProfilingController.pruneOldDiagnostics(diagDir)

        val retained = diagDir.listFiles()?.filter { it.isFile }.orEmpty()
        assertEquals(DiagnosticProfilingController.MAX_RETAINED_DIAGNOSTIC_FILES, retained.size)

        val newestJson = JSONObject(retained.maxBy { it.lastModified() }.readText())
        assertTrue(newestJson.getBoolean("local_only"))
        assertEquals(
            "Remote URLs must never be recorded as diagnostic file paths",
            "",
            newestJson.getString("result_file_path"),
        )
    }

    @Test
    fun lan_access_controller_isolates_public_origins_and_prompts_only_on_explicit_main_frame_lan() {
        assertTrue(LanAccessController.isLocalNetworkUrlOrHost("http://192.168.1.1/admin"))
        assertTrue(LanAccessController.isLocalNetworkUrlOrHost("10.0.0.42:8080"))
        assertTrue(LanAccessController.isLocalNetworkUrlOrHost("http://172.20.5.9/"))
        assertTrue(LanAccessController.isLocalNetworkUrlOrHost("http://localhost:3000"))
        assertTrue(LanAccessController.isLocalNetworkUrlOrHost("http://printer.local/status"))
        assertTrue(LanAccessController.isLocalNetworkUrlOrHost("http://[fd12:3456::1]:8443/"))
        assertFalse(LanAccessController.isLocalNetworkUrlOrHost("https://example.com/"))
        assertFalse(LanAccessController.isLocalNetworkUrlOrHost("http://172.32.0.1/"))
        assertFalse(
            LanAccessController.isLocalNetworkUrlOrHost(
                "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/",
            ),
        )

        val lan = LanAccessController(sdkIntProvider = { 37 })

        // Public web origin silently probing LAN subresource is blocked without prompting OS permission.
        assertEquals(
            LanAccessController.LanDecision.BLOCKED_CROSS_ORIGIN_LAN_PROBE,
            lan.evaluateRequest(
                pageUrl = "https://public.example.com/app",
                targetUrl = "http://192.168.1.1/api/config",
                isMainFrame = false,
                hiddenServiceOnly = false,
                hasOsPermission = true,
            ),
        )

        // Explicitly permitting that origin allows subresource only when OS permission is also granted.
        lan.allowOriginForLanSubresources("https://public.example.com")
        assertEquals(
            LanAccessController.LanDecision.ALLOW_LAN,
            lan.evaluateRequest(
                pageUrl = "https://public.example.com/app",
                targetUrl = "http://192.168.1.1/api/config",
                isMainFrame = false,
                hiddenServiceOnly = false,
                hasOsPermission = true,
            ),
        )

        // Explicit main-frame user navigation prompts OS permission when not yet granted on API 37.
        assertEquals(
            LanAccessController.LanDecision.PROMPT_OS_PERMISSION,
            lan.evaluateRequest(
                pageUrl = "about:blank",
                targetUrl = "http://192.168.1.1/",
                isMainFrame = true,
                hiddenServiceOnly = false,
                hasOsPermission = false,
            ),
        )

        // Once granted, explicit main-frame navigation succeeds unless Hidden Service Only is active.
        assertEquals(
            LanAccessController.LanDecision.ALLOW_LAN,
            lan.evaluateRequest(
                pageUrl = "about:blank",
                targetUrl = "http://192.168.1.1/",
                isMainFrame = true,
                hiddenServiceOnly = false,
                hasOsPermission = true,
            ),
        )
        assertEquals(
            LanAccessController.LanDecision.BLOCKED_HIDDEN_SERVICE_ONLY,
            lan.evaluateRequest(
                pageUrl = "about:blank",
                targetUrl = "http://192.168.1.1/",
                isMainFrame = true,
                hiddenServiceOnly = true,
                hasOsPermission = true,
            ),
        )
    }

    @Test
    fun android17_pickers_touchpad_pointer_bridge_and_handoff_respect_privacy_invariants() {
        // 1. EyeDropper & Scoped Contact Picker intents.
        val eyeIntent = WebPickersBridge.buildEyeDropperIntent()
        assertEquals(Intent.ACTION_OPEN_EYE_DROPPER, eyeIntent.action)
        val eyeResult = Intent().putExtra(Intent.EXTRA_COLOR, 0xFF112233.toInt())
        assertEquals(
            0xFF112233.toInt(),
            WebPickersBridge.extractEyeDropperColor(Activity.RESULT_OK, eyeResult),
        )
        assertNull(WebPickersBridge.extractEyeDropperColor(Activity.RESULT_CANCELED, eyeResult))

        val contactsIntent = WebPickersBridge.buildScopedContactPickerIntent(
            requestedMimeDataFields = listOf("vnd.android.cursor.item/email_v2"),
            selectionLimit = 2,
            matchAllFields = true,
        )
        assertEquals(ContactsPickerSessionContract.ACTION_PICK_CONTACTS, contactsIntent.action)
        assertEquals(
            arrayListOf("vnd.android.cursor.item/email_v2"),
            contactsIntent.getStringArrayListExtra(
                ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS,
            ),
        )
        assertEquals(
            2,
            contactsIntent.getIntExtra(
                ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_SELECTION_LIMIT,
                0,
            ),
        )

        // 2. Relative touchpad pointer accumulation.
        val pointerState = TouchpadPointerBridge.VirtualPointerState()
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = 12f
                y = -18f
                setAxisValue(MotionEvent.AXIS_RELATIVE_X, 25f)
                setAxisValue(MotionEvent.AXIS_RELATIVE_Y, -30f)
            },
        )
        val relEvent = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_MOVE,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHPAD,
            0,
        )
        val moved = TouchpadPointerBridge.accumulateRelativeMotion(
            event = relEvent,
            state = pointerState,
            viewportWidth = 400,
            viewportHeight = 800,
        )
        relEvent.recycle()
        // Center (200, 400) + (+25, -30) -> (225, 370)
        assertEquals(225 to 370, moved)

        // 3. Cross-Device Handoff opt-in & private/blank tab exclusions.
        val prefs = TrapPreferences(context)
        assertFalse("Handoff must default to opt-in false", prefs.handoffEnabled)

        assertNull(
            CrossDeviceHandoffController.buildPayloadIfEligible(
                handoffEnabled = false,
                activeUrl = "https://example.org/docs",
                activeTitle = "Docs",
                isPrivateTab = false,
                scrollY = 320,
            ),
        )
        assertNull(
            CrossDeviceHandoffController.buildPayloadIfEligible(
                handoffEnabled = true,
                activeUrl = "about:blank",
                activeTitle = "New Tab",
                isPrivateTab = false,
                scrollY = 0,
            ),
        )
        assertNull(
            CrossDeviceHandoffController.buildPayloadIfEligible(
                handoffEnabled = true,
                activeUrl = "https://example.org/secret",
                activeTitle = "Secret",
                isPrivateTab = true,
                scrollY = 120,
            ),
        )

        val eligible = CrossDeviceHandoffController.buildPayloadIfEligible(
            handoffEnabled = true,
            activeUrl = "https://example.org/docs",
            activeTitle = "Docs",
            isPrivateTab = false,
            scrollY = 480,
        )
        assertNotNull(eligible)
        assertEquals("https://example.org/docs", eligible!!.url)
        assertEquals(480, eligible.scrollY)

        val handoffIntent = Intent()
            .putExtra(CrossDeviceHandoffController.EXTRA_HANDOFF_URL, eligible.url)
            .putExtra(CrossDeviceHandoffController.EXTRA_HANDOFF_TITLE, eligible.title)
            .putExtra(CrossDeviceHandoffController.EXTRA_HANDOFF_SCROLL_Y, eligible.scrollY)
        assertEquals(eligible, CrossDeviceHandoffController.extractIncomingHandoff(handoffIntent))

        // Verify SettingsSheet exposes both Surface Filter and Handoff controls.
        compose.setContent {
            TrapTheme {
                SettingsSheet(
                    state = BrowserUiState(
                        surfaceFilterMode = SurfaceFilterMode.ReaderWarm,
                        handoffEnabled = false,
                    ),
                    onSelectThemeFamily = {},
                    onSelectThemeMode = {},
                    onToggleHiddenServiceOnly = {},
                    onToggleRequireBiometric = {},
                    onSelectDefaultZoom = {},
                    onTogglePipOnMinimize = {},
                    onTogglePipExcludePrivateTabs = {},
                    onSelectSurfaceFilterMode = {},
                    onToggleHandoff = {},
                    onClearSession = {},
                    onOpenLegalNotices = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("settings_surface_filter").assertExists()
        compose.onNodeWithTag("settings_handoff_switch").assertExists()
    }
}
