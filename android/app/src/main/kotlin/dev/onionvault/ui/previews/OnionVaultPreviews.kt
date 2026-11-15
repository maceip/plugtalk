package dev.onionvault.ui.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.onionvault.ui.BackPreviewState
import dev.onionvault.ui.BrowserScreen
import dev.onionvault.ui.BrowserUiState
import dev.onionvault.ui.EncryptRequestState
import dev.onionvault.ui.KeyInfo
import dev.onionvault.ui.LegalDocument
import dev.onionvault.ui.NormalizedBounds
import dev.onionvault.ui.OvCrashScreen
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.PgpOverlayState
import dev.onionvault.ui.PgpPlaintextState
import dev.onionvault.ui.TabItemState
import dev.onionvault.ui.TorBootstrapState
import dev.onionvault.ui.TrapTheme
import dev.onionvault.ui.TrapThemeFamily
import dev.onionvault.ui.TrapThemeMode

@Composable
private fun PreviewHiddenServiceSurface() {
    val colors = TrapTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface2)
            .padding(18.dp),
    ) {
        Text(
            text = "SecureDrop",
            color = colors.textPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Send an encrypted message to the newsroom:",
            color = colors.textBody,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .border(1.5.dp, colors.teal, RoundedCornerShape(12.dp))
                .background(colors.surface)
                .padding(12.dp),
        ) {
            Text(
                text = "This submission will be encrypted natively before entering Blink.",
                color = colors.textMuted,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(28.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(86.dp)
                .border(1.5.dp, colors.amber, RoundedCornerShape(12.dp))
                .background(colors.surface)
                .padding(12.dp),
        ) {
            Text(
                text = "-----BEGIN PGP MESSAGE-----\nhQEMA8x3...\n-----END PGP MESSAGE-----",
                color = colors.textBody,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(74.dp)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                .background(colors.surface)
                .padding(12.dp),
        ) {
            Text(
                text = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nmDMEZ...\n-----END PGP PUBLIC KEY BLOCK-----",
                color = colors.textMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun PreviewDuckDuckGoOnionSurface() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF18181B))
            .padding(18.dp),
    ) {
        Text(
            text = "DuckDuckGo Onion Service (HTML / Zero-JS)",
            color = Color(0xFFF4F4F5),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Connected over Arti v3 rendezvous circuit · JavaScript disabled.",
            color = Color(0xFFA1A1AA),
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

fun chromeState(
    family: TrapThemeFamily = TrapThemeFamily.SecureDrop,
    mode: TrapThemeMode = TrapThemeMode.Dark,
): BrowserUiState = BrowserUiState(
    url = "about:blank",
    title = "Trap Start",
    tor = TorBootstrapState(
        percent = 100,
        stage = "ready",
        ready = true,
        socksAddr = "127.0.0.1:41952",
        isolationToken = null,
        isolatedGroupCount = 0,
    ),
    themeFamily = family,
    themeMode = mode,
)

fun torTaxiState(): BrowserUiState = BrowserUiState(
    url = "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/",
    title = "DuckDuckGo (.onion)",
    canGoBack = true,
    tor = TorBootstrapState(
        percent = 100,
        stage = "ready",
        ready = true,
        socksAddr = "127.0.0.1:41952",
        isolationToken = "iso-duckduckgogg42xj",
        isolatedGroupCount = 1,
    ),
)

@Preview(name = "1. Tor Bootstrap & Security Sheet", heightDp = 760)
@Composable
fun TorBootstrapPreview() {
    BrowserScreen(
        state = BrowserUiState(
            url = "http://sdshqo3t6ilcq6cyudujqi3l5n6dqsmx6x3rv3moymqm3vhch6ad.onion",
            title = "SecureDrop",
            isLoading = true,
            loadProgress = 65,
            tor = TorBootstrapState(
                percent = 65,
                stage = "connecting to guard",
                ready = false,
                socksAddr = "127.0.0.1:41952",
                isolationToken = "iso-sdshqo3t6ilcq6cy",
                isolatedGroupCount = 1,
            ),
        ),
        showSecurity = true,
        surface = { PreviewHiddenServiceSurface() },
    )
}

@Preview(name = "2. Zero-JS Form with PGP Action Chips", heightDp = 760)
@Composable
fun PgpActionChipsPreview() {
    BrowserScreen(
        state = BrowserUiState(
            url = "http://sdshqo3t6ilcq6cyudujqi3l5n6dqsmx6x3rv3moymqm3vhch6ad.onion",
            title = "SecureDrop",
            canGoBack = true,
            tor = TorBootstrapState(
                percent = 100,
                stage = "ready",
                ready = true,
                socksAddr = "127.0.0.1:41952",
                isolationToken = "iso-sdshqo3t6ilcq6cy",
                isolatedGroupCount = 1,
            ),
            pgp = PgpOverlayState(
                fields = listOf(
                    PgpFieldState(
                        nodeId = "cdp-12",
                        kind = "pgp_textarea",
                        label = "Encrypt Field",
                        bounds = NormalizedBounds(0.05f, 0.14f, 0.95f, 0.30f),
                        preview = "This submission will be encrypted…",
                        cdpNodeId = 12,
                    ),
                    PgpFieldState(
                        nodeId = "cdp-19",
                        kind = "pgp_message",
                        label = "Decrypt Block",
                        bounds = NormalizedBounds(0.05f, 0.35f, 0.95f, 0.48f),
                        armored = "-----BEGIN PGP MESSAGE-----\nhQEMA8x3...\n-----END PGP MESSAGE-----",
                        cdpNodeId = 19,
                    ),
                    PgpFieldState(
                        nodeId = "cdp-25",
                        kind = "pgp_public_key",
                        label = "Import Key",
                        bounds = NormalizedBounds(0.05f, 0.52f, 0.95f, 0.64f),
                        armored = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nmDMEZ...\n-----END PGP PUBLIC KEY BLOCK-----",
                        cdpNodeId = 25,
                    ),
                ),
            ),
        ),
        surface = { PreviewHiddenServiceSurface() },
    )
}

@Preview(name = "3. Predictive Back Mid-Swipe & Snapshot Reload", heightDp = 760)
@Composable
fun PredictiveBackPreview() {
    BrowserScreen(
        state = BrowserUiState(
            url = "http://sdshqo3t6ilcq6cyudujqi3l5n6dqsmx6x3rv3moymqm3vhch6ad.onion",
            title = "SecureDrop",
            canGoBack = true,
            viewingSnapshot = true,
            backPreview = BackPreviewState(
                url = "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/",
                title = "DuckDuckGo (.onion)",
            ),
            tor = TorBootstrapState(
                percent = 100,
                stage = "ready",
                ready = true,
                isolationToken = "iso-sdshqo3t6ilcq6cy",
            ),
        ),
        previewProgress = 0.65f,
        surface = { PreviewHiddenServiceSurface() },
    )
}

@Preview(name = "4. Plaintext Decryption Sheet & Verified Signer", heightDp = 760)
@Composable
fun PgpDecryptionModalPreview() {
    BrowserScreen(
        state = BrowserUiState(
            url = "http://sdshqo3t6ilcq6cyudujqi3l5n6dqsmx6x3rv3moymqm3vhch6ad.onion",
            title = "SecureDrop",
            tor = TorBootstrapState(
                percent = 100,
                stage = "ready",
                ready = true,
                isolationToken = "iso-sdshqo3t6ilcq6cy",
            ),
            pgp = PgpOverlayState(
                plaintext = PgpPlaintextState(
                    nodeId = "cdp-19",
                    title = "Decrypted Message",
                    body = "We received your submission. Check back in 48 hours using your codename.\n\n-- Newsroom Desk",
                    signatureVerified = true,
                    signatureStatus = "good",
                    signerUserId = "Newsroom Desk <desk@securedrop.onion>",
                    signerFingerprint = "6F1A2C939B4E77D10E585A623F8B91C47D0A11E2",
                    targetEditable = false,
                ),
            ),
            keys = listOf(
                KeyInfo(
                    fingerprint = "6F1A2C939B4E77D10E585A623F8B91C47D0A11E2",
                    userId = "Newsroom Desk <desk@securedrop.onion>",
                    hasSecret = false,
                    isOwnKey = false,
                ),
                KeyInfo(
                    fingerprint = "A3D9071F5C2E8B40D16F7394C8E5A2B6F04D71C9",
                    userId = "Source <source@onion>",
                    hasSecret = true,
                    isOwnKey = true,
                ),
            ),
        ),
        surface = { PreviewHiddenServiceSurface() },
    )
}

@Preview(name = "5. Legal Notices of Bundled Components", heightDp = 760)
@Composable
fun LegalNoticesPreview() {
    BrowserScreen(
        state = BrowserUiState(
            url = "about:blank",
            title = "Trap Start",
            tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
            legalDocuments = listOf(
                LegalDocument("LGPL-2.1.txt", "GNU Lesser General Public License 2.1", 26_001),
                LegalDocument("GPL-3.0.txt", "GNU General Public License 3.0", 34_674),
                LegalDocument("LGPL-3.0.txt", "GNU Lesser General Public License 3.0", 42_098),
                LegalDocument("CEF-LICENSE.txt", "Cefrium / Chromium Engine Notices", 2_273_727),
            ),
        ),
        showLegalNotices = true,
    )
}

@Preview(name = "6. Trap Start Page (SecureDrop Dark)", heightDp = 760)
@Composable
fun BrowserChromePreview() {
    BrowserScreen(
        state = chromeState(TrapThemeFamily.SecureDrop, TrapThemeMode.Dark),
    )
}

@Preview(name = "7. DuckDuckGo .onion Loaded over Tor", heightDp = 760)
@Composable
fun TorTaxiPreview() {
    BrowserScreen(
        state = torTaxiState(),
        surface = { PreviewDuckDuckGoOnionSurface() },
    )
}

@Preview(name = "8. Browser Menu Sheet (Neon Graffiti Dark)", heightDp = 760)
@Composable
fun BrowserMenuPreview() {
    BrowserScreen(
        state = chromeState(TrapThemeFamily.NeonGraffiti, TrapThemeMode.Dark),
        showMenu = true,
    )
}

@Preview(name = "9. Crash Recovery Screen", heightDp = 760)
@Composable
fun CrashScreenPreview() {
    TrapTheme(
        family = TrapThemeFamily.NeonGraffiti,
        mode = TrapThemeMode.Dark,
    ) {
        OvCrashScreen(
            report = """
                Trap stopped unexpectedly.

                Timestamp: 2026-11-14T18:42:07Z
                Process:   dev.onionvault
                Reason:    java.lang.IllegalStateException: Engine startup check

                java.lang.IllegalStateException: Engine startup check
                    at dev.onionvault.MainActivity.startEngine(MainActivity.kt:142)
                    at dev.onionvault.MainActivity.onCreate(MainActivity.kt:88)
            """.trimIndent(),
            onCopy = {},
            onRetry = {},
        )
    }
}

@Preview(name = "10. Neon Graffiti Dark Chrome + PGP Keyring", heightDp = 760)
@Composable
fun NeonGraffitiKeyringPreview() {
    BrowserScreen(
        state = chromeState(TrapThemeFamily.NeonGraffiti, TrapThemeMode.Dark).copy(
            keys = listOf(
                KeyInfo(
                    fingerprint = "A3D9071F5C2E8B40D16F7394C8E5A2B6F04D71C9",
                    userId = "Source <source@onion>",
                    hasSecret = true,
                    isOwnKey = true,
                ),
                KeyInfo(
                    fingerprint = "6F1A2C939B4E77D10E585A623F8B91C47D0A11E2",
                    userId = "Newsroom Desk <desk@securedrop.onion>",
                    hasSecret = false,
                    isOwnKey = false,
                ),
            ),
        ),
        showKeyManager = true,
    )
}

@Preview(name = "11. SecureDrop Light Settings Sheet", heightDp = 760)
@Composable
fun SecureDropLightSettingsPreview() {
    BrowserScreen(
        state = chromeState(TrapThemeFamily.SecureDrop, TrapThemeMode.Light),
        showSettings = true,
    )
}

@Preview(name = "12. Neon Graffiti Light Multi-Tab Sheet", heightDp = 760)
@Composable
fun NeonGraffitiLightTabsPreview() {
    BrowserScreen(
        state = chromeState(TrapThemeFamily.NeonGraffiti, TrapThemeMode.Light).copy(
            tabs = listOf(
                TabItemState(
                    id = "tab-1",
                    title = "DuckDuckGo (.onion)",
                    url = "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/",
                    isActive = true,
                    isOnion = true,
                ),
                TabItemState(
                    id = "tab-2",
                    title = "Trap Start",
                    url = "about:blank",
                    isActive = false,
                ),
            ),
        ),
        showTabs = true,
    )
}

@Preview(name = "13. Recipient & Signer Picker Sheet", heightDp = 760)
@Composable
fun RecipientPickerPreview() {
    BrowserScreen(
        state = BrowserUiState(
            url = "http://sdshqo3t6ilcq6cyudujqi3l5n6dqsmx6x3rv3moymqm3vhch6ad.onion",
            title = "SecureDrop",
            tor = TorBootstrapState(percent = 100, stage = "ready", ready = true),
            keys = listOf(
                KeyInfo(
                    fingerprint = "6F1A2C939B4E77D10E585A623F8B91C47D0A11E2",
                    userId = "Newsroom Desk <desk@securedrop.onion>",
                    hasSecret = false,
                    isOwnKey = false,
                ),
                KeyInfo(
                    fingerprint = "A3D9071F5C2E8B40D16F7394C8E5A2B6F04D71C9",
                    userId = "Source <source@onion>",
                    hasSecret = true,
                    isOwnKey = true,
                ),
            ),
            pgp = PgpOverlayState(
                encryptRequest = EncryptRequestState(
                    nodeId = "cdp-12",
                    cdpNodeId = 12,
                    initialText = "Document archive hash: sha256:9c8f41...",
                    writeBackToField = true,
                ),
            ),
        ),
        surface = { PreviewHiddenServiceSurface() },
    )
}
