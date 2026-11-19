package dev.onionvault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.core.view.WindowCompat
import dev.onionvault.ui.previews.BrowserChromePreview
import dev.onionvault.ui.previews.BrowserMenuPreview
import dev.onionvault.ui.previews.BtcXmrSwapHomescreenPreview
import dev.onionvault.ui.previews.CrtOnionDirectoryPreview
import dev.onionvault.ui.previews.MoneroWalletHomescreenPreview
import dev.onionvault.ui.previews.NeonGraffitiKeyringPreview
import dev.onionvault.ui.previews.PgpActionChipsPreview
import dev.onionvault.ui.previews.PredictiveBackPreview
import dev.onionvault.ui.previews.SteelRailsLoadPulsePreview
import dev.onionvault.ui.previews.TorBootstrapPreview
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Live Jetpack Compose `@Preview` -> GitHub Pages synchronization suite.
 *
 * Renders the canonical `@Preview` composables in [dev.onionvault.ui.previews] using
 * Robolectric's native Skia renderer (`@GraphicsMode(GraphicsMode.Mode.NATIVE)`), recovers
 * exact rotated component contours (`contourDp`) and expanded sheet contours (`expandedContourDp`)
 * directly from live Compose Semantics (`getUnclippedBoundsInRoot()`), and writes
 * `site/generated/homescreen-sync.json` + `site/generated/assets/<state>.webp`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomescreenPagesSyncTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private data class ContourGeometry(
        val shape: String,
        val cx: Float,
        val cy: Float,
        val width: Float,
        val height: Float,
        val rx: Float,
        val rotationDeg: Float = 0f,
        val focusOffsetLocalY: Float? = null,
        val focusRadiusDp: Float? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("shape", shape)
            put("cx", round2(cx))
            put("cy", round2(cy))
            put("width", round2(width))
            put("height", round2(height))
            put("rx", round2(rx))
            put("rotationDeg", round2(rotationDeg))
            if (focusOffsetLocalY != null && focusRadiusDp != null) {
                val rad = Math.toRadians(rotationDeg.toDouble())
                val fx = cx - focusOffsetLocalY * sin(rad).toFloat()
                val fy = cy + focusOffsetLocalY * cos(rad).toFloat()
                put(
                    "subFocus",
                    JSONObject().apply {
                        put("cx", round2(fx))
                        put("cy", round2(fy))
                        put("r", round2(focusRadiusDp))
                    },
                )
            }
        }
    }

    private data class StatePreviewSpec(
        val id: String,
        val previewFn: String,
        val fileName: String,
        val expandedPrimaryTag: String,
        val expandedSecondaryTag: String? = null,
        val expandedShape: String = "roundrect",
        val expandedRxDp: Float = 22f,
        val content: @Composable () -> Unit,
    )

    private data class ComponentTarget(
        val id: String,
        val indexLabel: String,
        val side: String,
        val testTag: String,
        val composableName: String,
        val sourcePath: String,
        val statePreviewId: String,
        val shape: String,
        val rxDp: Float,
        val rotationDeg: Float = 0f,
        val anchorLocalOffsetY: Float = 0f,
        val focusOffsetLocalY: Float? = null,
        val focusRadiusDp: Float? = null,
        val customRestingContour: ContourGeometry? = null,
        val customRestingAnchorDp: Pair<Float, Float>? = null,
    )

    @Test
    fun sync_compose_preview_homescreen_and_semantics_bounds_for_github_pages() {
        val outputRoot = resolveOutputDirectory()
        val assetsDir = File(outputRoot, "assets").apply { mkdirs() }

        compose.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        }

        val activePreview = mutableStateOf<@Composable () -> Unit>({
            BrowserChromePreview()
        })
        compose.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                activePreview.value()
            }
        }

        // 1. Render the canonical Android Homescreen `@Preview`: `BrowserChromePreview()`
        val homescreenFile = File(assetsDir, "homescreen-preview.webp")
        val (widthPx, heightPx) = captureCurrentPreviewToWebp(homescreenFile)
        assertTrue("Homescreen WebP must be non-empty", homescreenFile.length() > 8_192L)

        val rootBounds = compose.onNodeWithTag("metatools_browser_shell").getUnclippedBoundsInRoot()
        val rootWidthDp = rootBounds.width.value
        val rootHeightDp = rootBounds.height.value
        assertTrue("Expected portrait width >= 400dp, got $rootWidthDp", rootWidthDp >= 400f)
        assertTrue("Expected portrait height >= 800dp, got $rootHeightDp", rootHeightDp >= 800f)

        val viewportBounds = compose.onNodeWithTag("webpage_viewport_host").getUnclippedBoundsInRoot()
        val railsBounds = compose.onNodeWithTag("perimeter_steel_rails").getUnclippedBoundsInRoot()
        assertEquals(0.dp, viewportBounds.top)
        assertEquals(0.dp, railsBounds.top)

        // Snapshot all resting tag bounds while `BrowserChromePreview()` is composed
        val restingTags = listOf(
            "webpage_viewport_host",
            "perimeter_steel_rails",
            "kpt_back_pod",
            "tor_orb_pod",
            "crt_address_pod",
            "pgp_rocker_pod",
            "side_remote_pod",
        )
        val restingRects = restingTags.associateWith { tag ->
            compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        }

        // 2. Render every interactive expanded `@Preview` and measure its live expanded bounds
        val stateSpecs = listOf(
            StatePreviewSpec(
                id = "zerojs-page",
                previewFn = "PgpActionChipsPreview",
                fileName = "state-zerojs-page.webp",
                expandedPrimaryTag = "webpage_viewport_host",
                expandedRxDp = 16f,
            ) {
                PgpActionChipsPreview()
            },
            StatePreviewSpec(
                id = "steel-rails-load",
                previewFn = "SteelRailsLoadPulsePreview",
                fileName = "state-steel-rails-load.webp",
                expandedPrimaryTag = "active_swap_hud",
                expandedShape = "dual-rails",
                expandedRxDp = 14f,
            ) {
                SteelRailsLoadPulsePreview()
            },
            StatePreviewSpec(
                id = "predictive-back",
                previewFn = "PredictiveBackPreview",
                fileName = "state-predictive-back.webp",
                expandedPrimaryTag = "webpage_viewport_host",
                expandedRxDp = 16f,
            ) {
                PredictiveBackPreview()
            },
            StatePreviewSpec(
                id = "tor-circuit",
                previewFn = "TorBootstrapPreview",
                fileName = "state-tor-circuit.webp",
                expandedPrimaryTag = "side_remote_pod",
                expandedRxDp = 24f,
            ) {
                TorBootstrapPreview()
            },
            StatePreviewSpec(
                id = "pgp-keyring",
                previewFn = "NeonGraffitiKeyringPreview",
                fileName = "state-pgp-keyring.webp",
                expandedPrimaryTag = "keyring_sheet",
                expandedRxDp = 22f,
            ) {
                NeonGraffitiKeyringPreview()
            },
            StatePreviewSpec(
                id = "crt-directory",
                previewFn = "CrtOnionDirectoryPreview",
                fileName = "state-crt-directory.webp",
                expandedPrimaryTag = "trusted_onion_directory",
                expandedSecondaryTag = "crt_address_pod",
                expandedRxDp = 18f,
            ) {
                CrtOnionDirectoryPreview()
            },
            StatePreviewSpec(
                id = "side-remote",
                previewFn = "BrowserMenuPreview",
                fileName = "state-side-remote.webp",
                expandedPrimaryTag = "side_remote_pod",
                expandedRxDp = 24f,
            ) {
                BrowserMenuPreview()
            },
            StatePreviewSpec(
                id = "monero-wallet",
                previewFn = "MoneroWalletHomescreenPreview",
                fileName = "state-monero-wallet.webp",
                expandedPrimaryTag = "xmr_wallet_sheet",
                expandedRxDp = 22f,
            ) {
                MoneroWalletHomescreenPreview()
            },
            StatePreviewSpec(
                id = "btc-xmr-swap",
                previewFn = "BtcXmrSwapHomescreenPreview",
                fileName = "state-btc-xmr-swap.webp",
                expandedPrimaryTag = "btc_xmr_swap_sheet",
                expandedRxDp = 22f,
            ) {
                BtcXmrSwapHomescreenPreview()
            },
        )

        val statesJson = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("id", "resting")
                    put("previewFn", "BrowserChromePreview")
                    put("image", "generated/assets/homescreen-preview.webp")
                },
            )
        }

        val expandedContoursByState = mutableMapOf<String, ContourGeometry>()
        for (spec in stateSpecs) {
            compose.runOnUiThread {
                activePreview.value = spec.content
            }
            val outFile = File(assetsDir, spec.fileName)
            captureCurrentPreviewToWebp(outFile)
            assertTrue("State preview ${spec.fileName} must be non-empty", outFile.length() > 4_096L)

            val primaryRect = compose.onNodeWithTag(spec.expandedPrimaryTag).getUnclippedBoundsInRoot()
            val mergedRect = if (spec.expandedSecondaryTag != null) {
                val secRect = compose.onNodeWithTag(spec.expandedSecondaryTag).getUnclippedBoundsInRoot()
                DpRect(
                    left = min(primaryRect.left.value, secRect.left.value).dp,
                    top = min(primaryRect.top.value, secRect.top.value).dp,
                    right = max(primaryRect.right.value, secRect.right.value).dp,
                    bottom = max(primaryRect.bottom.value, secRect.bottom.value).dp,
                )
            } else {
                primaryRect
            }

            val expContour = when (spec.id) {
                "zerojs-page", "predictive-back" -> ContourGeometry(
                    shape = "roundrect",
                    cx = rootWidthDp * 0.5f,
                    cy = 320f,
                    width = rootWidthDp - 28f,
                    height = 612f,
                    rx = 16f,
                )
                "steel-rails-load" -> ContourGeometry(
                    shape = "dual-rails",
                    cx = rootWidthDp * 0.5f,
                    cy = rootHeightDp * 0.5f,
                    width = rootWidthDp,
                    height = rootHeightDp,
                    rx = 4f,
                    focusOffsetLocalY = (mergedRect.top.value + mergedRect.height.value * 0.5f) - rootHeightDp * 0.5f,
                    focusRadiusDp = 34f,
                )
                else -> ContourGeometry(
                    shape = spec.expandedShape,
                    cx = (mergedRect.left.value + mergedRect.width.value * 0.5f).coerceIn(20f, rootWidthDp - 20f),
                    cy = (mergedRect.top.value + mergedRect.height.value * 0.5f).coerceIn(20f, rootHeightDp - 20f),
                    width = mergedRect.width.value.coerceAtMost(rootWidthDp - 12f),
                    height = mergedRect.height.value.coerceAtMost(rootHeightDp - 12f),
                    rx = spec.expandedRxDp,
                )
            }
            expandedContoursByState[spec.id] = expContour

            statesJson.put(
                JSONObject().apply {
                    put("id", spec.id)
                    put("previewFn", spec.previewFn)
                    put("image", "generated/assets/${spec.fileName}")
                },
            )
        }

        // 3. Build geometry-accurate component descriptors for all 9 homescreen & XMR/Swap callouts
        val targets = listOf(
            ComponentTarget(
                id = "start-zine-viewport",
                indexLabel = "01",
                side = "left",
                testTag = "webpage_viewport_host",
                composableName = "StartPage / BrowserScreen",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/StartPage.kt",
                statePreviewId = "zerojs-page",
                shape = "roundrect",
                rxDp = 14f,
                customRestingContour = ContourGeometry(
                    shape = "roundrect",
                    cx = rootWidthDp * 0.5f,
                    cy = 272f,
                    width = rootWidthDp - 28f,
                    height = 520f,
                    rx = 14f,
                ),
                customRestingAnchorDp = 14f to 195f,
            ),
            ComponentTarget(
                id = "perimeter-steel-rails",
                indexLabel = "02",
                side = "left",
                testTag = "perimeter_steel_rails",
                composableName = "PerimeterSteelRails",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/pods/PerimeterSteelRails.kt",
                statePreviewId = "steel-rails-load",
                shape = "dual-rails",
                rxDp = 4f,
                customRestingContour = ContourGeometry(
                    shape = "dual-rails",
                    cx = rootWidthDp * 0.5f,
                    cy = rootHeightDp * 0.5f,
                    width = rootWidthDp,
                    height = rootHeightDp,
                    rx = 4f,
                ),
                customRestingAnchorDp = 12f to 395f,
            ),
            ComponentTarget(
                id = "kpt-back-pod",
                indexLabel = "03",
                side = "left",
                testTag = "kpt_back_pod",
                composableName = "KptBackButtonPod",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/pods/KptBackButtonPod.kt",
                statePreviewId = "predictive-back",
                shape = "roundrect",
                rxDp = 8f,
            ),
            ComponentTarget(
                id = "tor-orb-pod",
                indexLabel = "04",
                side = "left",
                testTag = "tor_orb_pod",
                composableName = "TorKnurledOrbPod",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/pods/TorKnurledOrbPod.kt",
                statePreviewId = "tor-circuit",
                shape = "circle",
                rxDp = 52f,
            ),
            ComponentTarget(
                id = "pgp-rocker-pod",
                indexLabel = "05",
                side = "right",
                testTag = "pgp_rocker_pod",
                composableName = "OpenPgpRockerPod",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/pods/OpenPgpRockerPod.kt",
                statePreviewId = "pgp-keyring",
                shape = "roundrect",
                rxDp = 15f,
                rotationDeg = 20f,
            ),
            ComponentTarget(
                id = "crt-address-pod",
                indexLabel = "06",
                side = "right",
                testTag = "crt_address_pod",
                composableName = "RecessedCrtAddressPod",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/pods/RecessedCrtAddressPod.kt",
                statePreviewId = "crt-directory",
                shape = "roundrect",
                rxDp = 26f,
            ),
            ComponentTarget(
                id = "side-remote-pod",
                indexLabel = "07",
                side = "right",
                testTag = "side_remote_pod",
                composableName = "PhotoSoapSideRemote",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/pods/PhotoSoapSideRemote.kt",
                statePreviewId = "side-remote",
                shape = "pill",
                rxDp = 30f,
                rotationDeg = 20f,
                anchorLocalOffsetY = -44f,
            ),
            ComponentTarget(
                id = "monero-wallet",
                indexLabel = "08",
                side = "right",
                testTag = "side_remote_pod",
                composableName = "MoneroWalletHardwarePod",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/pods/HardwareModalPods.kt",
                statePreviewId = "monero-wallet",
                shape = "pill",
                rxDp = 30f,
                rotationDeg = 20f,
                anchorLocalOffsetY = 0f,
                focusOffsetLocalY = -36f,
                focusRadiusDp = 19f,
            ),
            ComponentTarget(
                id = "btc-xmr-swap",
                indexLabel = "09",
                side = "right",
                testTag = "side_remote_pod",
                composableName = "BtcToXmrSwapHardwarePod",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/pods/HardwareModalPods.kt",
                statePreviewId = "btc-xmr-swap",
                shape = "pill",
                rxDp = 30f,
                rotationDeg = 20f,
                anchorLocalOffsetY = 46f,
                focusOffsetLocalY = 44f,
                focusRadiusDp = 21f,
            ),
        )

        val componentsJson = JSONArray()
        for (target in targets) {
            val rect: DpRect = restingRects.getValue(target.testTag)
            val wDp = rect.width.value
            val hDp = rect.height.value
            assertTrue("Component ${target.testTag} must have positive width", wDp > 0f)
            assertTrue("Component ${target.testTag} must have positive height", hDp > 0f)

            val rad = Math.toRadians(target.rotationDeg.toDouble())
            val cosR = cos(rad).toFloat()
            val sinR = sin(rad).toFloat()

            // Recover exact unrotated pivot center from Compose's rotated top-left origin
            val recoveredCx = rect.left.value + (wDp * 0.5f) * cosR - (hDp * 0.5f) * sinR
            val recoveredCy = rect.top.value + (wDp * 0.5f) * sinR + (hDp * 0.5f) * cosR

            val restingContour = target.customRestingContour ?: ContourGeometry(
                shape = target.shape,
                cx = recoveredCx,
                cy = recoveredCy,
                width = wDp,
                height = hDp,
                rx = target.rxDp,
                rotationDeg = target.rotationDeg,
                focusOffsetLocalY = target.focusOffsetLocalY,
                focusRadiusDp = target.focusRadiusDp,
            )

            // Compute outer-rim perimeter anchor point so leader lines touch the encircling contour cleanly
            val (anchorDpX, anchorDpY) = target.customRestingAnchorDp ?: run {
                val localX = if (target.side == "left") -restingContour.width * 0.5f else restingContour.width * 0.5f
                val localY = target.anchorLocalOffsetY
                val ax = (restingContour.cx + localX * cosR - localY * sinR).coerceIn(6f, rootWidthDp - 6f)
                val ay = (restingContour.cy + localX * sinR + localY * cosR).coerceIn(12f, rootHeightDp - 12f)
                ax to ay
            }

            val expandedContour = expandedContoursByState[target.statePreviewId] ?: restingContour
            val expAnchorDpX = if (target.side == "left") {
                (expandedContour.cx - expandedContour.width * 0.5f).coerceIn(8f, rootWidthDp - 8f)
            } else {
                (expandedContour.cx + expandedContour.width * 0.5f).coerceIn(8f, rootWidthDp - 8f)
            }
            val expAnchorDpY = (expandedContour.cy + target.anchorLocalOffsetY * 0.65f).coerceIn(36f, rootHeightDp - 36f)

            componentsJson.put(
                JSONObject().apply {
                    put("id", target.id)
                    put("index", target.indexLabel)
                    put("side", target.side)
                    put("testTag", target.testTag)
                    put("composable", target.composableName)
                    put("sourcePath", target.sourcePath)
                    put("statePreviewId", target.statePreviewId)
                    put("contourDp", restingContour.toJson())
                    put("expandedContourDp", expandedContour.toJson())
                    put(
                        "anchorNorm",
                        JSONObject().apply {
                            put("x", round4(anchorDpX / rootWidthDp))
                            put("y", round4(anchorDpY / rootHeightDp))
                        },
                    )
                    put(
                        "expandedAnchorNorm",
                        JSONObject().apply {
                            put("x", round4(expAnchorDpX / rootWidthDp))
                            put("y", round4(expAnchorDpY / rootHeightDp))
                        },
                    )
                },
            )
        }

        val density = compose.activity.resources.displayMetrics.density
        val manifest = JSONObject().apply {
            put("schemaVersion", 2)
            put("appVersion", resolveRepoVersion())
            put("gitCommit", resolveGitCommit())
            put("previewEntrypoint", "dev.onionvault.ui.previews.BrowserChromePreview")
            put(
                "viewport",
                JSONObject().apply {
                    put("qualifiers", "w412dp-h915dp-xhdpi")
                    put("density", round2(density))
                    put("widthDp", round2(rootWidthDp))
                    put("heightDp", round2(rootHeightDp))
                    put("widthPx", widthPx)
                    put("heightPx", heightPx)
                },
            )
            put("homescreenImage", "generated/assets/homescreen-preview.webp")
            put("states", statesJson)
            put("components", componentsJson)
        }

        val jsonFile = File(outputRoot, "homescreen-sync.json")
        jsonFile.writeText(manifest.toString(2) + "\n", Charsets.UTF_8)
        assertTrue("Manifest JSON must exist and be non-empty", jsonFile.length() > 512L)
    }

    @Suppress("DEPRECATION")
    private fun captureCurrentPreviewToWebp(
        destination: File,
    ): Pair<Int, Int> {
        compose.waitForIdle()

        val contentRoot: View = compose.activity.findViewById(android.R.id.content)
            ?: compose.activity.window.decorView
        val w = contentRoot.width.coerceAtLeast(1)
        val h = contentRoot.height.coerceAtLeast(1)
        contentRoot.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        contentRoot.layout(0, 0, w, h)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        contentRoot.draw(canvas)

        val dialog = ShadowDialog.getLatestDialog()
        if (dialog != null && dialog.isShowing) {
            val decor = dialog.window?.decorView
            if (decor != null) {
                if (decor.width <= 0 || decor.height <= 0) {
                    decor.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST),
                    )
                    decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                }
                if (decor.width > 0 && decor.height > 0) {
                    val overlay = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    decor.draw(Canvas(overlay))
                    val attributes = dialog.window?.attributes
                    canvas.drawBitmap(
                        overlay,
                        (attributes?.x ?: 0).toFloat(),
                        (attributes?.y ?: 0).toFloat(),
                        null,
                    )
                }
            }
        }

        val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        destination.parentFile?.mkdirs()
        destination.outputStream().use { stream ->
            bitmap.compress(format, 92, stream)
        }
        return w to h
    }

    private fun resolveOutputDirectory(): File {
        val configured = System.getenv("TRAP_SYNC_PAGES_DIR")?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getProperty("trap.syncPagesDir")?.trim()?.takeIf { it.isNotEmpty() }
        if (configured != null) {
            return File(configured).apply { mkdirs() }
        }
        return File("build/pages-sync").apply { mkdirs() }
    }

    private fun resolveRepoVersion(): String {
        val envVer = System.getenv("TRAP_SYNC_VERSION")?.trim()?.takeIf { it.isNotEmpty() }
        if (envVer != null) return envVer
        val candidates = listOf(
            File("../../VERSION"),
            File("../VERSION"),
            File("VERSION"),
        )
        return candidates.firstOrNull { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "0.1.46"
    }

    private fun resolveGitCommit(): String {
        return System.getenv("TRAP_SYNC_COMMIT_SHA")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(12)
            ?: "synced"
    }
}

private fun round2(v: Float): Double =
    String.format(Locale.US, "%.2f", v).toDouble()

private fun round4(v: Float): Double =
    String.format(Locale.US, "%.4f", v).toDouble()
