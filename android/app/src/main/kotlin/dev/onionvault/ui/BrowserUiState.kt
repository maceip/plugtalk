package dev.onionvault.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import dev.onionvault.OvNative
import dev.onionvault.platform.SurfaceFilterMode

/**
 * Pure immutable UI state of Trap's browser screen, sheets, and overlays.
 * Contains zero fabricated metrics, zero fake circuit hops, and zero JNI pointers.
 */
@Immutable
data class BrowserUiState(
    val url: String = "about:blank",
    val title: String = "",
    val isLoading: Boolean = false,
    val loadProgress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val viewingSnapshot: Boolean = false,
    val backPreview: BackPreviewState? = null,
    val tor: TorBootstrapState = TorBootstrapState(),
    val pgp: PgpOverlayState = PgpOverlayState(),
    val status: String = "",
    val blocked: Boolean = false,
    val tabs: List<TabItemState> = listOf(
        TabItemState(
            id = "tab-1",
            title = "Trap",
            url = "about:blank",
            isActive = true,
        ),
    ),
    val activeTabId: String = "tab-1",
    val keys: List<KeyInfo> = emptyList(),
    val defaultKeyFingerprint: String = "",
    val legalDocuments: List<LegalDocument> = emptyList(),
    val hiddenServiceOnly: Boolean = false,
    val requireBiometric: Boolean = false,
    val zoomPercent: Int = 100,
    val defaultZoomPercent: Int = 100,
    val themeFamily: TrapThemeFamily = TrapThemeFamily.SecureDrop,
    val themeMode: TrapThemeMode = TrapThemeMode.Dark,
    val clearnetPrompt: ClearnetPromptState? = null,
    val clearnetRules: List<OvNative.ClearnetRuleItem> = emptyList(),
    val contextMenu: ContextMenuUiState? = null,
    val selectionPgp: SelectionPgpUiState? = null,
    val pipOnMinimizeEnabled: Boolean = true,
    val pipExcludePrivateTabs: Boolean = true,
    val isInPipMode: Boolean = false,
    val isPipChromeHidden: Boolean = false,
    val surfaceFilterMode: SurfaceFilterMode = SurfaceFilterMode.None,
    val handoffEnabled: Boolean = false,
    val isScreenRecordingActive: Boolean = false,
    val podsRetracted: Boolean = false,
    val findInPage: FindInPageState = FindInPageState(),
    val pipFallbackBitmap: ImageBitmap? = null,
) {
    val tabCount: Int get() = tabs.size.coerceAtLeast(1)

    val isStartPage: Boolean
        get() = isBlankOrStartUrl(url)
}

@Immutable
data class FindInPageState(
    val visible: Boolean = false,
    val query: String = "",
    val matchCount: Int = 0,
    val activeIndex: Int = 0,
    val activeBounds: NormalizedBounds? = null,
)

@Immutable
data class ClearnetPromptState(
    val host: String,
    val pendingUrl: String?,
    val suggestedPattern: String,
)

fun isBlankOrStartUrl(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.isEmpty() || trimmed.equals("about:blank", ignoreCase = true)
}

@Immutable
data class TabItemState(
    val id: String,
    val title: String,
    val url: String,
    val isActive: Boolean = false,
    val isLoading: Boolean = false,
    val isOnion: Boolean = false,
    val isPrivate: Boolean = false,
)

@Immutable
data class LegalDocument(
    val name: String,
    val title: String,
    val sizeBytes: Long,
)

@Immutable
data class KeyInfo(
    val fingerprint: String,
    val userId: String,
    val hasSecret: Boolean,
    val createdEpochSecs: Long = 0L,
    val canSign: Boolean = hasSecret,
    val canEncrypt: Boolean = true,
    val isOwnKey: Boolean = hasSecret,
)

/**
 * Real Arti Tor transport state surfaced from `OvNative.nativeTorStatusJson`.
 */
@Immutable
data class TorBootstrapState(
    val percent: Int = 0,
    val stage: String = "",
    val ready: Boolean = false,
    val socksAddr: String = "",
    val isolationToken: String? = null,
    val isolatedGroupCount: Int = 0,
)

@Immutable
data class PgpOverlayState(
    val fields: List<PgpFieldState> = emptyList(),
    val plaintext: PgpPlaintextState? = null,
    val encryptRequest: EncryptRequestState? = null,
    val decryptPassphraseRequest: DecryptPassphraseRequestState? = null,
    val error: String? = null,
) {
    val hasArmoredPgpBlock: Boolean
        get() = fields.any { it.isArmoredPgpBlock }

    val primaryArmoredBlock: PgpFieldState?
        get() = fields.firstOrNull { it.kind == "pgp_message" }
            ?: fields.firstOrNull { it.isArmoredPgpBlock }

    val editablePgpFields: List<PgpFieldState>
        get() = fields.filter { it.isEditablePgpField }

    val hasPgpContent: Boolean
        get() = hasArmoredPgpBlock || editablePgpFields.isNotEmpty()
}

@Immutable
data class PgpFieldState(
    val nodeId: String,
    val kind: String,
    val label: String,
    val bounds: NormalizedBounds,
    val preview: String = "",
    val armored: String? = null,
    val cdpNodeId: Int = 0,
    val backendNodeId: Int = 0,
    val fingerprintHint: String = "",
    val isSearchLike: Boolean = false,
) {
    val isArmoredPgpBlock: Boolean
        get() = kind == "pgp_message" ||
            (kind == "pgp_public_key" && !label.equals("Insert My PubKey", ignoreCase = true))

    val isEditablePgpField: Boolean
        get() = !isSearchLike && (
            kind == "pgp_textarea" ||
                kind == "textarea" ||
                kind == "text_input" ||
                kind == "editable" ||
                (kind == "pgp_public_key" && label.equals("Insert My PubKey", ignoreCase = true))
            )
}

@Immutable
data class NormalizedBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

@Immutable
data class PgpPlaintextState(
    val nodeId: String,
    val title: String,
    val body: String,
    val signatureVerified: Boolean,
    val signatureStatus: String = if (signatureVerified) "good" else "unsigned",
    val signerFingerprint: String? = null,
    val signerUserId: String? = null,
    val cdpNodeId: Int = 0,
    val backendNodeId: Int = 0,
    val targetEditable: Boolean = false,
)

@Immutable
data class EncryptRequestState(
    val nodeId: String,
    val cdpNodeId: Int = 0,
    val backendNodeId: Int = 0,
    val initialText: String = "",
    val writeBackToField: Boolean = true,
)

@Immutable
data class DecryptPassphraseRequestState(
    val nodeId: String,
    val cdpNodeId: Int = 0,
    val backendNodeId: Int = 0,
    val armoredCiphertext: String,
    val targetEditable: Boolean = false,
    val error: String? = null,
)

@Immutable
data class BackPreviewState(
    val url: String,
    val title: String,
    val scrollOffset: IntOffset = IntOffset.Zero,
    val fields: List<PgpFieldState> = emptyList(),
    val frameSnapshot: ImageBitmap? = null,
)

@Immutable
data class ContextMenuUiState(
    val tabId: String,
    val linkUrl: String? = null,
    val mediaUrl: String? = null,
    val selectedText: String? = null,
    val classificationKind: String = "plain",
    val armoredPayload: String = "",
)

@Immutable
data class SelectionPgpUiState(
    val tabId: String,
    val selectedText: String,
    val kind: String,
    val actionLabel: String = "",
    val armored: String = "",
    val fingerprintHint: String = "",
)
