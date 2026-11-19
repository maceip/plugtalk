package dev.onionvault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import dev.onionvault.ui.previews.CrtOnionDirectoryPreview
import dev.onionvault.ui.previews.MoneroWalletHomescreenPreview
import dev.onionvault.ui.previews.NeonGraffitiKeyringPreview
import dev.onionvault.ui.previews.TorBootstrapPreview
import java.io.File
import java.util.Locale
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
 * Robolectric's native Skia renderer (`@GraphicsMode(GraphicsMode.Mode.NATIVE)`) and
 * extracts exact component bounding boxes directly from the live Compose Semantics tree
 * (`getUnclippedBoundsInRoot()`) so the GitHub Pages interactive callout diagram always
 * stays 1:1 geometry-synced with the Android Kotlin source code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomescreenPagesSyncTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private data class ComponentTarget(
        val id: String,
        val indexLabel: String,
        val side: String,
        val testTag: String,
        val composableName: String,
        val sourcePath: String,
        val statePreviewId: String,
        val customAnchorNormX: Float? = null,
        val customAnchorNormY: Float? = null,
        val highlightBoundsOverrideNorm: FloatArray? = null,
    )

    @Test
    fun sync_compose_preview_homescreen_and_semantics_bounds_for_github_pages() {
        val outputRoot = resolveOutputDirectory()
        val assetsDir = File(outputRoot, "assets").apply { mkdirs() }

        compose.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        }

        var activePreview = androidx.compose.runtime.mutableStateOf<@Composable () -> Unit>({
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

        val targets = listOf(
            ComponentTarget(
                id = "start-zine-viewport",
                indexLabel = "01",
                side = "left",
                testTag = "webpage_viewport_host",
                composableName = "StartPage / BrowserScreen",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/StartPage.kt",
                statePreviewId = "resting",
                customAnchorNormX = 0.48f,
                customAnchorNormY = 0.19f,
                highlightBoundsOverrideNorm = floatArrayOf(0.03f, 0.015f, 0.94f, 0.46f),
            ),
            ComponentTarget(
                id = "perimeter-steel-rails",
                indexLabel = "02",
                side = "left",
                testTag = "perimeter_steel_rails",
                composableName = "PerimeterSteelRails",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/pods/PerimeterSteelRails.kt",
                statePreviewId = "tor-circuit",
                customAnchorNormX = (6f / rootWidthDp).coerceIn(0.01f, 0.05f),
                customAnchorNormY = 0.43f,
                highlightBoundsOverrideNorm = floatArrayOf(0.0f, 0.0f, 12f / rootWidthDp, 1.0f),
            ),
            ComponentTarget(
                id = "kpt-back-pod",
                indexLabel = "03",
                side = "left",
                testTag = "kpt_back_pod",
                composableName = "KptBackButtonPod",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/pods/KptBackButtonPod.kt",
                statePreviewId = "resting",
            ),
            ComponentTarget(
                id = "tor-orb-pod",
                indexLabel = "04",
                side = "left",
                testTag = "tor_orb_pod",
                composableName = "TorKnurledOrbPod",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/pods/TorKnurledOrbPod.kt",
                statePreviewId = "tor-circuit",
            ),
            ComponentTarget(
                id = "crt-address-pod",
                indexLabel = "05",
                side = "right",
                testTag = "crt_address_pod",
                composableName = "RecessedCrtAddressPod",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/pods/RecessedCrtAddressPod.kt",
                statePreviewId = "crt-directory",
            ),
            ComponentTarget(
                id = "pgp-rocker-pod",
                indexLabel = "06",
                side = "right",
                testTag = "pgp_rocker_pod",
                composableName = "OpenPgpRockerPod",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/pods/OpenPgpRockerPod.kt",
                statePreviewId = "pgp-keyring",
            ),
            ComponentTarget(
                id = "side-remote-pod",
                indexLabel = "07",
                side = "right",
                testTag = "side_remote_pod",
                composableName = "PhotoSoapSideRemote",
                sourcePath = "android/app/src/main/kotlin/dev/onionvault/ui/pods/PhotoSoapSideRemote.kt",
                statePreviewId = "side-remote",
            ),
        )

        val componentsJson = JSONArray()
        for (target in targets) {
            val rect: DpRect = compose.onNodeWithTag(target.testTag).getUnclippedBoundsInRoot()
            val wDp = rect.width.value
            val hDp = rect.height.value
            assertTrue("Component ${target.testTag} must have positive width", wDp > 0f)
            assertTrue("Component ${target.testTag} must have positive height", hDp > 0f)

            val normLeft = (rect.left.value / rootWidthDp).coerceIn(0f, 1f)
            val normTop = (rect.top.value / rootHeightDp).coerceIn(0f, 1f)
            val normWidth = (wDp / rootWidthDp).coerceIn(0f, 1f - normLeft)
            val normHeight = (hDp / rootHeightDp).coerceIn(0f, 1f - normTop)

            val anchorX = target.customAnchorNormX
                ?: (normLeft + normWidth * 0.5f).coerceIn(0.035f, 0.89f)
            val anchorY = target.customAnchorNormY
                ?: (normTop + normHeight * 0.5f).coerceIn(0.035f, 0.96f)

            val highlight = target.highlightBoundsOverrideNorm
            val hlLeft = highlight?.get(0) ?: normLeft
            val hlTop = highlight?.get(1) ?: normTop
            val hlWidth = highlight?.get(2) ?: normWidth
            val hlHeight = highlight?.get(3) ?: normHeight

            componentsJson.put(
                JSONObject().apply {
                    put("id", target.id)
                    put("index", target.indexLabel)
                    put("side", target.side)
                    put("testTag", target.testTag)
                    put("composable", target.composableName)
                    put("sourcePath", target.sourcePath)
                    put("statePreviewId", target.statePreviewId)
                    put(
                        "boundsDp",
                        JSONObject().apply {
                            put("left", round2(rect.left.value))
                            put("top", round2(rect.top.value))
                            put("right", round2(rect.right.value))
                            put("bottom", round2(rect.bottom.value))
                            put("width", round2(wDp))
                            put("height", round2(hDp))
                        },
                    )
                    put(
                        "boundsNorm",
                        JSONObject().apply {
                            put("left", round4(hlLeft))
                            put("top", round4(hlTop))
                            put("width", round4(hlWidth))
                            put("height", round4(hlHeight))
                        },
                    )
                    put(
                        "anchorNorm",
                        JSONObject().apply {
                            put("x", round4(anchorX))
                            put("y", round4(anchorY))
                        },
                    )
                },
            )
        }

        // 2. Render synced interactive state `@Preview` composables from `OnionVaultPreviews.kt`
        data class StatePreviewSpec(
            val id: String,
            val previewFn: String,
            val fileName: String,
            val content: @Composable () -> Unit,
        )

        val stateSpecs = listOf(
            StatePreviewSpec("crt-directory", "CrtOnionDirectoryPreview", "state-crt-directory.webp") {
                CrtOnionDirectoryPreview()
            },
            StatePreviewSpec("side-remote", "BrowserMenuPreview", "state-side-remote.webp") {
                BrowserMenuPreview()
            },
            StatePreviewSpec("tor-circuit", "TorBootstrapPreview", "state-tor-circuit.webp") {
                TorBootstrapPreview()
            },
            StatePreviewSpec("pgp-keyring", "NeonGraffitiKeyringPreview", "state-pgp-keyring.webp") {
                NeonGraffitiKeyringPreview()
            },
            StatePreviewSpec("monero-wallet", "MoneroWalletHomescreenPreview", "state-monero-wallet.webp") {
                MoneroWalletHomescreenPreview()
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

        for (spec in stateSpecs) {
            compose.runOnUiThread {
                activePreview.value = spec.content
            }
            val outFile = File(assetsDir, spec.fileName)
            captureCurrentPreviewToWebp(outFile)
            assertTrue("State preview ${spec.fileName} must be non-empty", outFile.length() > 4_096L)
            statesJson.put(
                JSONObject().apply {
                    put("id", spec.id)
                    put("previewFn", spec.previewFn)
                    put("image", "generated/assets/${spec.fileName}")
                },
            )
        }

        val density = compose.activity.resources.displayMetrics.density
        val manifest = JSONObject().apply {
            put("schemaVersion", 1)
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
            ?: "0.1.43"
    }

    private fun resolveGitCommit(): String {
        return System.getenv("TRAP_SYNC_COMMIT_SHA")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(12)
            ?: "synced"
    }

    private fun round2(v: Float): Double =
        String.format(Locale.US, "%.2f", v).toDouble()

    private fun round4(v: Float): Double =
        String.format(Locale.US, "%.4f", v).toDouble()
}
