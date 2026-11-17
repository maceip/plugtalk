package dev.onionvault

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import dev.onionvault.ui.BrowserScreen
import dev.onionvault.ui.BrowserUiState
import dev.onionvault.ui.NormalizedBounds
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.PgpOverlayState
import dev.onionvault.ui.TrapPreferences
import dev.onionvault.ui.TrapTheme
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
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
class PipControllerTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context

    private class FakePipEnvironment(
        override var sdkInt: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        override val packageName: String = "dev.onionvault",
        var pipFeature: Boolean = true,
        var expandedPipFeature: Boolean = false,
        var smallestWidth600Dp: Boolean = false,
        var pipAppOpAllowed: Boolean = true,
    ) : PipController.Environment {
        override fun hasPipFeature(): Boolean = pipFeature
        override fun hasExpandedPipFeature(): Boolean = expandedPipFeature
        override fun isSmallestWidthAtLeast600Dp(): Boolean = smallestWidth600Dp
        override fun isPipAppOpAllowed(): Boolean = pipAppOpAllowed
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(TrapPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun manifest_declares_pip_support_single_task_and_required_config_changes() {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("android/app/src/main/AndroidManifest.xml"),
        )
        val manifestFile = candidates.firstOrNull { it.isFile }
        assertNotNull("Expected AndroidManifest.xml in source tree", manifestFile)

        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = dbf.newDocumentBuilder().parse(manifestFile!!)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val activities = doc.getElementsByTagName("activity")
        var mainActivity: Element? = null
        for (i in 0 until activities.length) {
            val el = activities.item(i) as Element
            if (el.getAttributeNS(androidNs, "name") == ".MainActivity") {
                mainActivity = el
                break
            }
        }
        assertNotNull("Expected .MainActivity in AndroidManifest.xml", mainActivity)
        assertEquals("true", mainActivity!!.getAttributeNS(androidNs, "supportsPictureInPicture"))
        assertEquals("singleTask", mainActivity.getAttributeNS(androidNs, "launchMode"))

        val configTokens = mainActivity.getAttributeNS(androidNs, "configChanges")
            .split("|")
            .map { it.trim() }
            .toSet()
        assertTrue(configTokens.contains("screenSize"))
        assertTrue(configTokens.contains("smallestScreenSize"))
        assertTrue(configTokens.contains("screenLayout"))
        assertTrue(configTokens.contains("orientation"))
    }

    @Test
    fun preferences_persist_pip_on_minimize_and_private_tab_exclusion_defaults() {
        val prefs = TrapPreferences(context)
        assertTrue(prefs.pipOnMinimizeEnabled)
        assertTrue(prefs.pipExcludePrivateTabs)

        prefs.pipOnMinimizeEnabled = false
        prefs.pipExcludePrivateTabs = false

        val reloaded = TrapPreferences(context)
        assertFalse(reloaded.pipOnMinimizeEnabled)
        assertFalse(reloaded.pipExcludePrivateTabs)
    }

    @Test
    fun eligibility_requires_feature_appop_preference_non_blank_url_and_respects_private_exclusion() {
        val env = FakePipEnvironment()
        val controller = PipController(env)

        val baseSnapshot = PipController.PipSnapshot(
            pipOnMinimizeEnabled = true,
            pipExcludePrivateTabs = true,
            activeUrl = "https://tor.taxi/",
            activeTitle = "tor.taxi",
        )
        assertTrue(controller.evaluateEligibility(baseSnapshot).eligible)

        // Blank / start page must not float into PiP.
        assertFalse(
            controller.evaluateEligibility(baseSnapshot.copy(activeUrl = "about:blank")).eligible,
        )

        // Crash screen must block PiP.
        assertFalse(
            controller.evaluateEligibility(baseSnapshot.copy(crashScreenVisible = true)).eligible,
        )

        // User toggle disabled must block PiP.
        assertFalse(
            controller.evaluateEligibility(baseSnapshot.copy(pipOnMinimizeEnabled = false)).eligible,
        )

        // Private tab excluded by default, allowed when user unchecks exclusion.
        val privateTab = baseSnapshot.copy(isActiveTabPrivate = true)
        val privateEval = controller.evaluateEligibility(privateTab)
        assertFalse(privateEval.eligible)
        assertTrue(privateEval.excludedPrivateTab)
        assertTrue(
            controller.evaluateEligibility(
                privateTab.copy(pipExcludePrivateTabs = false),
            ).eligible,
        )

        // AppOp denied must gate PiP and build package-scoped settings deep-link.
        env.pipAppOpAllowed = false
        val appOpDenied = controller.evaluateEligibility(baseSnapshot)
        assertFalse(appOpDenied.eligible)
        assertFalse(appOpDenied.isAppOpAllowed)

        val settingsIntent = controller.buildPipSettingsIntent()
        assertEquals(PipController.ACTION_PICTURE_IN_PICTURE_SETTINGS, settingsIntent.action)
        assertEquals("package:dev.onionvault", settingsIntent.dataString)

        // Missing hardware feature blocks PiP completely.
        env.pipFeature = false
        env.pipAppOpAllowed = true
        assertFalse(controller.evaluateEligibility(baseSnapshot).eligible)
    }

    @Test
    fun api31_uses_state_driven_params_and_never_calls_legacy_user_leave_hint_enter_pip() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val env = FakePipEnvironment(sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val controller = PipController(env)
        val snapshot = PipController.PipSnapshot(
            activeUrl = "https://tor.taxi/",
            sourceRectHint = Rect(0, 48, 1080, 2100),
        )

        var pushedParams: PictureInPictureParams? = null
        val synced = controller.syncPictureInPictureParams(activity, snapshot) {
            pushedParams = it
        }
        assertTrue(synced)
        assertNotNull(pushedParams)

        var enterPipCalled = false
        val legacyTriggered = controller.handleUserLeaveHint(activity, snapshot) {
            enterPipCalled = true
            true
        }
        assertFalse(legacyTriggered)
        assertFalse(enterPipCalled)
    }

    @Test
    fun api30_falls_back_to_enter_pip_in_user_leave_hint_only_when_eligible() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val env = FakePipEnvironment(sdkInt = Build.VERSION_CODES.R)
        val controller = PipController(env)
        val snapshot = PipController.PipSnapshot(activeUrl = "https://tor.taxi/")

        // API 30 does not push setAutoEnterEnabled params proactively.
        assertFalse(controller.syncPictureInPictureParams(activity, snapshot) {})

        var enterPipCalls = 0
        assertTrue(
            controller.handleUserLeaveHint(activity, snapshot) {
                enterPipCalls += 1
                true
            },
        )
        assertEquals(1, enterPipCalls)

        // Blank page does not trigger legacy PiP on API 30.
        assertFalse(
            controller.handleUserLeaveHint(
                activity,
                snapshot.copy(activeUrl = "about:blank"),
            ) {
                enterPipCalls += 1
                true
            },
        )
        assertEquals(1, enterPipCalls)
    }

    @Test
    fun aspect_ratio_clamps_extreme_videos_and_populates_expanded_ratio_on_tablets() {
        val env = FakePipEnvironment(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            expandedPipFeature = false,
            smallestWidth600Dp = false,
        )
        val controller = PipController(env)

        // Standard web document -> compact readable 3:4 card.
        assertEquals(
            PipController.DEFAULT_DOCUMENT_ASPECT_RATIO,
            controller.computeAspectRatio(hasActiveVideo = false, videoWidth = 0, videoHeight = 0),
        )

        // Active video without intrinsic size -> 16:9.
        assertEquals(
            PipController.DEFAULT_VIDEO_ASPECT_RATIO,
            controller.computeAspectRatio(hasActiveVideo = true, videoWidth = 0, videoHeight = 0),
        )

        // Ultra-wide 32:9 video clamps to 2.39:1.
        assertEquals(
            PipController.MAX_ASPECT_RATIO,
            controller.computeAspectRatio(hasActiveVideo = true, videoWidth = 3200, videoHeight = 900),
        )

        // Ultra-tall 1:4 video clamps to 1:2.39.
        assertEquals(
            PipController.MIN_ASPECT_RATIO,
            controller.computeAspectRatio(hasActiveVideo = true, videoWidth = 400, videoHeight = 1600),
        )

        // Standard 16:9 video stays ~1.778.
        val ratio1080p: Rational = controller.computeAspectRatio(
            hasActiveVideo = true,
            videoWidth = 1920,
            videoHeight = 1080,
        )
        assertEquals(1920f / 1080f, ratio1080p.toFloat(), 0.005f)

        // Phone without FEATURE_EXPANDED_PICTURE_IN_PICTURE returns null expanded ratio.
        assertNull(controller.computeExpandedAspectRatio(hasActiveVideo = true))

        // Tablet (sw600dp) or expanded-PiP device returns expanded ratios.
        env.smallestWidth600Dp = true
        assertEquals(
            PipController.EXPANDED_VIDEO_ASPECT_RATIO,
            controller.computeExpandedAspectRatio(hasActiveVideo = true),
        )
        assertEquals(
            PipController.EXPANDED_DOCUMENT_ASPECT_RATIO,
            controller.computeExpandedAspectRatio(hasActiveVideo = false),
        )
    }

    @Test
    fun zero_flicker_chrome_stripping_hides_dock_banners_and_overlays_immediately() {
        assertTrue(
            PipController.resolveChromeHidden(
                isInPictureInPictureMode = false,
                isTransitioningToPip = true,
            ),
        )
        assertTrue(
            PipController.resolveChromeHidden(
                isInPictureInPictureMode = true,
                isTransitioningToPip = false,
            ),
        )
        assertFalse(
            PipController.resolveChromeHidden(
                isInPictureInPictureMode = false,
                isTransitioningToPip = false,
            ),
        )

        var chromeHidden by mutableStateOf(false)
        compose.setContent {
            TrapTheme {
                BrowserScreen(
                    state = BrowserUiState(
                        url = "https://tor.taxi/",
                        isLoading = true,
                        status = "Connected",
                        isPipChromeHidden = chromeHidden,
                        pgp = PgpOverlayState(
                            fields = listOf(
                                PgpFieldState(
                                    nodeId = "f1",
                                    kind = "compose",
                                    label = "Message",
                                    bounds = NormalizedBounds(0.1f, 0.2f, 0.8f, 0.4f),
                                    cdpNodeId = 1,
                                    backendNodeId = 1,
                                ),
                            ),
                        ),
                    ),
                )
            }
        }

        // Before PiP transition: floating MetaTools pods, steel rails, status banner, and PGP overlay exist.
        compose.onAllNodesWithTag("bottom_control_dock").assertCountEquals(0)
        compose.onNodeWithTag("metatools_browser_shell").assertExists()
        compose.onNodeWithTag("perimeter_steel_rails").assertExists()
        compose.onNodeWithTag("tor_orb_pod").assertExists()
        compose.onNodeWithTag("crt_address_pod").assertExists()
        compose.onNodeWithTag("pgp_rocker_pod").assertExists()
        compose.onNodeWithTag("side_remote_pod").assertExists()
        compose.onNodeWithTag("status_notice_banner").assertExists()
        compose.onNodeWithTag("pgp_field_overlay").assertExists()

        // First frame of transition (`isTransitioningToPip == true`): all floating pods & rails stripped.
        chromeHidden = true
        compose.waitForIdle()
        compose.onAllNodesWithTag("perimeter_steel_rails").assertCountEquals(0)
        compose.onAllNodesWithTag("tor_orb_pod").assertCountEquals(0)
        compose.onAllNodesWithTag("crt_address_pod").assertCountEquals(0)
        compose.onAllNodesWithTag("pgp_rocker_pod").assertCountEquals(0)
        compose.onAllNodesWithTag("side_remote_pod").assertCountEquals(0)
        compose.onAllNodesWithTag("status_notice_banner").assertCountEquals(0)
        compose.onAllNodesWithTag("pgp_field_overlay").assertCountEquals(0)

        // Exiting PiP restores surrounding floating pods & rails cleanly.
        chromeHidden = false
        compose.waitForIdle()
        compose.onNodeWithTag("perimeter_steel_rails").assertExists()
        compose.onNodeWithTag("tor_orb_pod").assertExists()
        compose.onNodeWithTag("crt_address_pod").assertExists()
        compose.onNodeWithTag("pgp_rocker_pod").assertExists()
        compose.onNodeWithTag("side_remote_pod").assertExists()
        compose.onNodeWithTag("status_notice_banner").assertExists()
        compose.onNodeWithTag("pgp_field_overlay").assertExists()
    }

    @Test
    fun etld_plus_one_origin_extracts_onion_apex_public_suffixes_and_hosts_cleanly() {
        assertEquals("", PipController.extractEtldPlusOneOrigin("about:blank"))
        assertEquals(
            "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
            PipController.extractEtldPlusOneOrigin(
                "http://lite.duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/?q=trap",
            ),
        )
        assertEquals(
            "tor.taxi",
            PipController.extractEtldPlusOneOrigin("https://mirror.tor.taxi/directory"),
        )
        assertEquals(
            "bbc.co.uk",
            PipController.extractEtldPlusOneOrigin("https://www.news.bbc.co.uk/world"),
        )
        assertEquals(
            "192.168.1.42",
            PipController.extractEtldPlusOneOrigin("http://192.168.1.42:8080/status"),
        )
        assertEquals(
            "localhost",
            PipController.extractEtldPlusOneOrigin("http://localhost:9050/"),
        )
    }

    @Test
    fun media_and_webrtc_overlay_actions_prioritize_mic_mute_and_track_play_pause_state() {
        val controller = PipController(FakePipEnvironment(), context)

        // Active HTML5 video playback -> [Pause, Seek +10s, Switch Tab].
        val playingModel = controller.resolveOverlayModel(
            PipController.PipSnapshot(
                activeUrl = "https://media.tor.taxi/watch",
                activeTitle = "Tor Relay Lecture",
                isPlayingAudio = true,
                hasActiveVideo = true,
                tabCount = 2,
            ),
        )
        assertEquals(PipController.OverlayMode.MEDIA_WEBRTC, playingModel.mode)
        assertEquals("Tor Relay Lecture", playingModel.title)
        assertEquals("tor.taxi", playingModel.subtitle)
        assertEquals(3, playingModel.actions.size)
        assertEquals(PipActionReceiver.Command.PLAY_PAUSE, playingModel.actions[0].command)
        assertEquals("Pause", playingModel.actions[0].title)
        assertEquals(PipActionReceiver.Command.SEEK_FORWARD, playingModel.actions[1].command)
        assertEquals(PipActionReceiver.Command.SWITCH_TAB, playingModel.actions[2].command)
        assertTrue(playingModel.actions[2].enabled)
        assertEquals(PipActionReceiver.Command.CLOSE_PIP, playingModel.closeAction.command)

        // Paused in session -> icon/title swap to Play while remaining in Media mode.
        val pausedModel = controller.resolveOverlayModel(
            PipController.PipSnapshot(
                activeUrl = "https://media.tor.taxi/watch",
                activeTitle = "Tor Relay Lecture",
                isMediaPausedByUser = true,
                tabCount = 1,
            ),
        )
        assertEquals(PipController.OverlayMode.MEDIA_WEBRTC, pausedModel.mode)
        assertEquals("Play", pausedModel.actions[0].title)
        assertFalse(pausedModel.actions[2].enabled)

        // WebRTC mic active -> Slot 1 prioritizes Mute / Unmute Mic over Play/Pause.
        val webrtcUnmuted = controller.resolveOverlayModel(
            PipController.PipSnapshot(
                activeUrl = "https://calls.tor.taxi/room",
                hasWebRtcAudio = true,
                isAudioMuted = false,
            ),
        )
        assertEquals(PipActionReceiver.Command.TOGGLE_MUTE, webrtcUnmuted.actions[0].command)
        assertEquals("Mute Mic", webrtcUnmuted.actions[0].title)

        val webrtcMuted = controller.resolveOverlayModel(
            PipController.PipSnapshot(
                activeUrl = "https://calls.tor.taxi/room",
                hasWebRtcAudio = true,
                isAudioMuted = true,
            ),
        )
        assertEquals(PipActionReceiver.Command.TOGGLE_MUTE, webrtcMuted.actions[0].command)
        assertEquals("Unmute Mic", webrtcMuted.actions[0].title)
    }

    @Test
    fun document_monitor_overlay_adapts_scroll_reload_stop_and_tab_or_back_actions() {
        val controller = PipController(FakePipEnvironment(), context)

        // Single tab, top of document, idle, cannot go back.
        val docTop = controller.resolveOverlayModel(
            PipController.PipSnapshot(
                activeUrl = "https://ci.tor.taxi/pipelines",
                activeTitle = "CI Build #412",
                atDocumentBottom = false,
                isLoading = false,
                canGoBack = false,
                tabCount = 1,
            ),
        )
        assertEquals(PipController.OverlayMode.DOCUMENT_MONITOR, docTop.mode)
        assertEquals("CI Build #412", docTop.title)
        assertEquals("tor.taxi", docTop.subtitle)
        assertEquals("Scroll Down", docTop.actions[0].title)
        assertEquals("Reload", docTop.actions[1].title)
        assertEquals(PipActionReceiver.Command.HISTORY_BACK, docTop.actions[2].command)
        assertFalse(docTop.actions[2].enabled)

        val remoteActionsDisabledBack = controller.buildRemoteActions(context, docTop.actions)
        assertEquals(3, remoteActionsDisabledBack.size)
        assertFalse(remoteActionsDisabledBack[2].isEnabled)

        // Document at bottom + loading + multi-tab -> [Scroll Up, Stop, Switch Tab (enabled)].
        val docBottomLoadingMulti = controller.resolveOverlayModel(
            PipController.PipSnapshot(
                activeUrl = "https://ci.tor.taxi/pipelines",
                atDocumentBottom = true,
                isLoading = true,
                canGoBack = true,
                tabCount = 3,
            ),
        )
        assertEquals("Scroll Up", docBottomLoadingMulti.actions[0].title)
        assertEquals("Stop", docBottomLoadingMulti.actions[1].title)
        assertEquals(PipActionReceiver.Command.SWITCH_TAB, docBottomLoadingMulti.actions[2].command)
        assertTrue(docBottomLoadingMulti.actions[2].enabled)
    }

    @Test
    fun pip_action_receiver_dispatches_all_overlay_commands_and_session_cycles_tabs() {
        val received = mutableListOf<PipActionReceiver.Command>()
        val receiver = PipActionReceiver { cmd -> received.add(cmd) }

        PipActionReceiver.Command.entries.forEach { cmd ->
            receiver.onReceive(context, Intent(cmd.action))
        }
        assertEquals(PipActionReceiver.Command.entries, received)

        // Unrelated action is ignored safely.
        receiver.onReceive(context, Intent("android.intent.action.VIEW"))
        assertEquals(PipActionReceiver.Command.entries.size, received.size)

        // Verify TabSessionManager tab cycling and PiP close media teardown on JVM.
        val sessionManager = TabSessionManager(
            context = context,
            cdpController = CdpDomPgpController(),
            backStackManager = OnionBackStackManager(),
        )
        val tab1 = sessionManager.createTab("about:blank", select = true)
        val tab2 = sessionManager.createTab("about:blank", select = false)
        val tab3 = sessionManager.createTab("about:blank", select = false)
        assertEquals(tab1, sessionManager.activeTabId.value)

        assertEquals(tab2, sessionManager.cycleTabFromPip(preferAudible = false))
        assertEquals(tab3, sessionManager.cycleTabFromPip(preferAudible = false))
        assertEquals(tab1, sessionManager.cycleTabFromPip(preferAudible = false))

        sessionManager.toggleActiveMediaPlayPause(tab1)
        assertTrue(sessionManager.activeTab()?.isMediaPausedByUser == true)
        sessionManager.pauseAndStopActiveMediaForPipClose()
        assertFalse(sessionManager.activeTab()?.isMediaPausedByUser == true)
        assertFalse(sessionManager.activeTab()?.isPlayingAudio == true)
    }
}
