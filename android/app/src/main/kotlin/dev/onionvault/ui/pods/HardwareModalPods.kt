package dev.onionvault.ui.pods

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.onionvault.OvNative
import dev.onionvault.platform.SensitiveContentEffect
import dev.onionvault.platform.SurfaceFilterMode
import dev.onionvault.ui.BrowserUiState
import dev.onionvault.ui.ClearnetPromptState
import dev.onionvault.ui.DecryptPassphraseRequestState
import dev.onionvault.ui.EncryptRequestState
import dev.onionvault.ui.KeyInfo
import dev.onionvault.ui.LegalDocument
import dev.onionvault.ui.MoneroUiState
import dev.onionvault.ui.MoneroWalletTab
import dev.onionvault.ui.PgpFieldState
import dev.onionvault.ui.PgpPlaintextState
import dev.onionvault.ui.SelectionPgpUiState
import dev.onionvault.ui.TabItemState
import dev.onionvault.ui.TrapGlyph
import dev.onionvault.ui.TrapIcon
import dev.onionvault.ui.TrapThemeFamily
import dev.onionvault.ui.TrapThemeMode
import dev.onionvault.ui.formatClearnetRuleTtl
import dev.onionvault.ui.formatGroupedFingerprint
import dev.onionvault.ui.formatShortFingerprint
import dev.onionvault.ui.isBlankOrStartUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared Sculptural Dark-Anodized Hardware Modal Pod Chassis.
 *
 * Enforces non-negotiable hardware modal invariants:
 * 1. Mandatory [BackHandler] dismissing cleanly on system Back.
 * 2. Mandatory `.statusBarsPadding()`, `.navigationBarsPadding()`, and `.imePadding()`.
 * 3. Zero backdrop blur — crisp non-blurred dark scrim + floating cast-shadow chassis.
 * 4. Flexible `weight(1f, fill = false)` body support so footer actions NEVER clip!
 */
@Composable
fun HardwareModalFrame(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    ledColor: Color = MetaToolsTokens.LedGreen,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val grainBrush = rememberPewterGrainBrush()
    val podRadius = 20.dp
    val podShape = RoundedCornerShape(podRadius)

    BackHandler(enabled = true) {
        onDismiss()
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Non-blurred dark scrim (tap outside chassis dismisses)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xA6000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .testTag("hardware_modal_scrim"),
        )

        Column(
            modifier = Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth()
                .metaToolsDropShadow(cornerRadius = podRadius)
                .clip(podShape)
                .drawBehind {
                    val rPx = podRadius.toPx()
                    val corner = CornerRadius(rPx, rPx)
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            0.0f to MetaToolsTokens.AnodizedSurfaceTop,
                            0.35f to MetaToolsTokens.AnodizedSurface,
                            1.0f to MetaToolsTokens.AnodizedSurfaceDeep,
                        ),
                        cornerRadius = corner,
                    )
                    drawRoundRect(
                        brush = grainBrush,
                        cornerRadius = corner,
                    )
                    drawMetallicSquircleBevel(
                        cornerRadiusPx = rPx,
                        strokeWidthPx = 1.5.dp.toPx(),
                        highlightColor = MetaToolsTokens.AnodizedBevelLight,
                        shadowColor = MetaToolsTokens.AnodizedBevelDark,
                    )
                    // 4 machined corner hex-rivet pin details
                    val rivetInset = 9.dp.toPx()
                    val rivets = listOf(
                        Offset(rivetInset, rivetInset),
                        Offset(size.width - rivetInset, rivetInset),
                        Offset(rivetInset, size.height - rivetInset),
                        Offset(size.width - rivetInset, size.height - rivetInset),
                    )
                    rivets.forEach { pt ->
                        drawCircle(color = Color(0xFF0A0C0F), radius = 2.dp.toPx(), center = pt)
                        drawCircle(
                            color = Color(0xFF565F6B),
                            radius = 1.dp.toPx(),
                            center = Offset(pt.x - 0.3.dp.toPx(), pt.y - 0.3.dp.toPx()),
                        )
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            event.changes.forEach { change ->
                                if (!change.isConsumed) {
                                    change.consume()
                                }
                            }
                        }
                    }
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // Header Bezel Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(modifier = Modifier.size(10.dp)) {
                    drawRecessedGlassLedBead(
                        center = center,
                        beadDiameterPx = 7.dp.toPx(),
                        litColor = ledColor,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    text = title,
                    color = Color(0xFFF0F4F8),
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (headerAction != null) {
                    headerAction()
                    Spacer(Modifier.width(8.dp))
                }
                HardwareBezelButton(
                    text = "✕",
                    onClick = onDismiss,
                    compact = true,
                    modifier = Modifier.testTag("sheet_close_button"),
                )
            }

            EngravedGrooveDivider(modifier = Modifier.padding(bottom = 12.dp))

            content()
        }
    }
}

// ============================================================================
// 1. Spatial Tab Switcher Hardware Pod
// ============================================================================

@Composable
fun SpatialTabSwitcherPod(
    tabs: List<TabItemState>,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onNewPrivateTab: () -> Unit = {},
) {
    HardwareModalFrame(
        title = "TABS (${tabs.size})",
        onDismiss = onDismiss,
        modifier = modifier.testTag("tabs_sheet"),
        headerAction = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HardwareBezelButton(
                    text = "+ Private",
                    compact = true,
                    onClick = {
                        onNewPrivateTab()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("tabs_new_private_tab"),
                )
                HardwareBezelButton(
                    text = "+ New Tab",
                    primary = true,
                    compact = true,
                    onClick = {
                        onNewTab()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("tabs_new_tab"),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .testTag("spatial_tab_switcher_pod"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { tab ->
                val displayTitle = tab.title.ifBlank {
                    if (isBlankOrStartUrl(tab.url)) "Trap" else tab.url
                }
                val displayUrl = if (isBlankOrStartUrl(tab.url)) {
                    "about:blank"
                } else if (isOnionHostUrl(tab.url)) {
                    truncateOnionPreservingTail(
                        tab.url.removePrefix("https://").removePrefix("http://").substringBefore('/'),
                    )
                } else {
                    tab.url
                }

                // Single combined status token so row stays strictly <= 2:1 label density
                val singleBadge = when {
                    tab.isActive && tab.isPrivate -> "ACTIVE·PRIV"
                    tab.isActive -> "ACTIVE"
                    tab.isPrivate -> "PRIVATE"
                    tab.isOnion || isOnionHostUrl(tab.url) -> ".ONION"
                    else -> null
                }
                val slotShape = RoundedCornerShape(12.dp)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(slotShape)
                        .background(
                            if (tab.isActive) MetaToolsTokens.CrtBlackGlass else MetaToolsTokens.AnodizedRecessedWell,
                        )
                        .border(
                            width = 1.dp,
                            color = if (tab.isActive) {
                                MetaToolsTokens.PhosphorOnionGreen.copy(alpha = 0.65f)
                            } else {
                                MetaToolsTokens.GrooveHighlight
                            },
                            shape = slotShape,
                        )
                        .clickable(
                            role = Role.Button,
                            onClick = {
                                onSelectTab(tab.id)
                                onDismiss()
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("tab_row_${tab.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(modifier = Modifier.size(9.dp)) {
                        drawRecessedGlassLedBead(
                            center = center,
                            beadDiameterPx = 6.dp.toPx(),
                            litColor = when {
                                tab.isActive -> MetaToolsTokens.LedGreen
                                tab.isPrivate -> MetaToolsTokens.LedAmber
                                else -> null
                            },
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayTitle,
                            color = Color(0xFFF0F4F8),
                            fontSize = 13.5.sp,
                            fontWeight = if (tab.isActive) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = displayUrl,
                            color = if (tab.isActive) {
                                MetaToolsTokens.PhosphorClearnetGreen
                            } else {
                                MetaToolsTokens.StencilInk.copy(alpha = 0.72f)
                            },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (singleBadge != null) {
                        Spacer(Modifier.width(8.dp))
                        HardwareStatusChip(
                            text = singleBadge,
                            accent = if (tab.isActive) {
                                MetaToolsTokens.PhosphorOnionGreen
                            } else {
                                MetaToolsTokens.PhosphorLoadingAmber
                            },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    HardwareBezelButton(
                        text = "✕",
                        compact = true,
                        onClick = { onCloseTab(tab.id) },
                        modifier = Modifier.testTag("tab_close_${tab.id}"),
                    )
                }
            }
        }
    }
}

// ============================================================================
// 2. Clearnet 3-Day Consent Hardware Pod
// ============================================================================

@Composable
fun ClearnetConsentHardwarePod(
    prompt: ClearnetPromptState,
    onAllow: (prompt: ClearnetPromptState, pattern: String, ttlSeconds: Long) -> Unit,
    onBlock: (prompt: ClearnetPromptState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current

    val exactHost = remember(prompt.host) {
        prompt.host.trim().trimEnd('.').lowercase()
    }
    val domainGlob = remember(prompt.host, prompt.suggestedPattern) {
        prompt.suggestedPattern.trim().ifBlank {
            OvNative.suggestGlob(exactHost)
        }.ifBlank { exactHost }
    }
    var pattern by remember(prompt.host, prompt.pendingUrl, prompt.suggestedPattern) {
        mutableStateOf(domainGlob)
    }

    val cleanPattern = pattern.trim()
    val canAllow = cleanPattern.isNotEmpty()
    val isDomainSelected = cleanPattern.equals(domainGlob, ignoreCase = true) &&
        (domainGlob != exactHost || !cleanPattern.equals(exactHost, ignoreCase = true))
    val isExactSelected = cleanPattern.equals(exactHost, ignoreCase = true) && !isDomainSelected

    HardwareModalFrame(
        title = "Allow clearnet site over Tor?",
        ledColor = MetaToolsTokens.LedAmber,
        onDismiss = { onBlock(prompt) },
        modifier = modifier.testTag("clearnet_consent_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Host Readout inside Recessed CRT Strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MetaToolsTokens.CrtBlackGlass)
                    .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = exactHost.ifEmpty { prompt.host },
                    color = MetaToolsTokens.PhosphorLoadingAmber,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("clearnet_prompt_host"),
                )
            }

            // Strictly 2 Scope Toggle Keys (<= 2:1 label density)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HardwareChoicePill(
                    label = "*.domain",
                    selected = isDomainSelected,
                    onClick = { pattern = domainGlob },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("clearnet_scope_domain"),
                )
                HardwareChoicePill(
                    label = "Exact host",
                    selected = isExactSelected,
                    onClick = { pattern = exactHost },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("clearnet_scope_exact"),
                )
            }

            HardwareCrtTextField(
                value = pattern,
                onValueChange = { pattern = it },
                placeholder = "*.example.com",
                singleLine = true,
                keyboardType = KeyboardType.Uri,
                testTag = "clearnet_pattern_input",
            )
        }

        Spacer(Modifier.height(10.dp))

        // Pinned Hardware Action Footer (always visible above IME soft keyboard)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Primary 44dp CTA: Allow for 3 days (259,200s)
            HardwareBezelButton(
                text = "Allow for 3 days",
                primary = true,
                enabled = canAllow,
                onClick = {
                    focusManager.clearFocus()
                    onAllow(prompt, cleanPattern, OvNative.DEFAULT_CLEARNET_TTL_SECONDS)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("clearnet_allow_3d_button"),
            )

            // Secondary 2-button row: Allow once (0s) & Block
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HardwareBezelButton(
                    text = "Allow once",
                    enabled = canAllow,
                    onClick = {
                        focusManager.clearFocus()
                        onAllow(prompt, cleanPattern, 0L)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .testTag("clearnet_allow_once_button"),
                )
                HardwareBezelButton(
                    text = "Block",
                    danger = true,
                    onClick = {
                        focusManager.clearFocus()
                        onBlock(prompt)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .testTag("clearnet_block_button"),
                )
            }
        }
    }
}

// ============================================================================
// 3. Encrypt Recipient Picker Hardware Pod (Safe Recipient Default + Inline Import!)
// ============================================================================

@Composable
fun EncryptRecipientHardwarePod(
    request: EncryptRequestState,
    keys: List<KeyInfo>,
    defaultKeyFingerprint: String,
    errorMessage: String?,
    onImportKey: (armored: String, passphrase: String) -> Unit,
    onOpenKeyring: () -> Unit,
    onConfirmEncrypt: (
        plaintext: String,
        recipientFingerprint: String,
        signerFingerprintOrEmpty: String,
        passphrase: String,
    ) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val encryptCapable = remember(keys) { keys.filter { it.canEncrypt } }
    val signCapable = remember(keys) { keys.filter { it.hasSecret && it.canSign } }

    // SECURITY RULE: NEVER auto-select [Own] key when external recipient keys are missing!
    val externalRecipientKeys = remember(encryptCapable) {
        encryptCapable.filter { !it.isOwnKey }
    }
    val initialRecipient = remember(externalRecipientKeys) {
        externalRecipientKeys.firstOrNull()?.fingerprint ?: ""
    }
    val initialSigner = remember(signCapable, defaultKeyFingerprint) {
        signCapable.firstOrNull { it.fingerprint == defaultKeyFingerprint }?.fingerprint ?: ""
    }

    var messageText by remember(request.nodeId, request.initialText) {
        mutableStateOf(request.initialText)
    }
    var selectedRecipient by remember(request.nodeId) { mutableStateOf(initialRecipient) }
    var selectedSigner by remember(request.nodeId) { mutableStateOf(initialSigner) }
    var passphrase by remember(request.nodeId) { mutableStateOf("") }

    // Inline "+ Import Key" drawer state so user never loses draft plaintext
    var showInlineImport by remember { mutableStateOf(externalRecipientKeys.isEmpty()) }
    var inlineArmoredInput by remember { mutableStateOf("") }

    // Auto-select newly imported external recipient key when none was selected yet
    LaunchedEffect(externalRecipientKeys) {
        if (selectedRecipient.isEmpty() && externalRecipientKeys.isNotEmpty()) {
            selectedRecipient = externalRecipientKeys.last().fingerprint
            showInlineImport = false
        }
    }

    HardwareModalFrame(
        title = if (request.writeBackToField) "ENCRYPT FIELD" else "ENCRYPT PGP",
        ledColor = MetaToolsTokens.LedAmber,
        onDismiss = onDismiss,
        modifier = modifier.testTag("encrypt_picker_sheet"),
    ) {
        SensitiveContentEffect()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HardwareSectionHeader(title = "Message Plaintext")
            HardwareCrtTextField(
                value = messageText,
                onValueChange = { messageText = it },
                placeholder = "Type message to encrypt…",
                singleLine = false,
                minHeight = 78.dp,
                testTag = "encrypt_plaintext_input",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Recipient Public Key",
                    color = MetaToolsTokens.StencilInk,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                HardwareBezelButton(
                    text = if (showInlineImport) "Hide Import" else "+ Import Key",
                    compact = true,
                    onClick = { showInlineImport = !showInlineImport },
                    modifier = Modifier.testTag("encrypt_inline_import_toggle"),
                )
            }

            if (externalRecipientKeys.isEmpty() && selectedRecipient.isEmpty()) {
                Text(
                    text = "No external recipient key in keyring. Import recipient public key below or explicitly select a key.",
                    color = MetaToolsTokens.PhosphorLoadingAmber,
                    fontSize = 11.5.sp,
                )
            }

            if (showInlineImport) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MetaToolsTokens.AnodizedRecessedWell)
                        .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HardwareCrtTextField(
                        value = inlineArmoredInput,
                        onValueChange = { inlineArmoredInput = it },
                        placeholder = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
                        singleLine = false,
                        minHeight = 68.dp,
                        testTag = "encrypt_inline_import_input",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        HardwareBezelButton(
                            text = "Paste",
                            compact = true,
                            onClick = {
                                clipboard.getText()?.text?.let { inlineArmoredInput = it }
                            },
                            modifier = Modifier.testTag("encrypt_inline_paste_button"),
                        )
                        HardwareBezelButton(
                            text = "Import Public Key",
                            primary = true,
                            compact = true,
                            enabled = inlineArmoredInput.isNotBlank(),
                            onClick = {
                                val raw = inlineArmoredInput.trim()
                                inlineArmoredInput = ""
                                onImportKey(raw, "")
                            },
                            modifier = Modifier.testTag("encrypt_inline_import_submit"),
                        )
                    }
                }
            }

            if (encryptCapable.isEmpty()) {
                HardwareBezelButton(
                    text = "Open PGP Keyring",
                    onClick = {
                        onDismiss()
                        onOpenKeyring()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    encryptCapable.forEach { key ->
                        HardwareKeyRow(
                            title = key.userId.ifBlank { "PGP Key" },
                            subtitle = formatShortFingerprint(key.fingerprint),
                            badge = if (key.isOwnKey) "Own" else "Recipient",
                            selected = selectedRecipient == key.fingerprint,
                            onClick = { selectedRecipient = key.fingerprint },
                            testTag = "encrypt_recipient_${key.fingerprint}",
                        )
                    }
                }
            }

            if (signCapable.isNotEmpty()) {
                HardwareSectionHeader(title = "Sign Message (Optional)")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HardwareKeyRow(
                        title = "Unsigned",
                        subtitle = "No cryptographic signature",
                        badge = null,
                        selected = selectedSigner.isEmpty(),
                        onClick = { selectedSigner = "" },
                        testTag = "encrypt_signer_none",
                    )
                    signCapable.forEach { key ->
                        HardwareKeyRow(
                            title = key.userId.ifBlank { "Own Signing Key" },
                            subtitle = formatShortFingerprint(key.fingerprint),
                            badge = "Secret",
                            selected = selectedSigner == key.fingerprint,
                            onClick = { selectedSigner = key.fingerprint },
                            testTag = "encrypt_signer_${key.fingerprint}",
                        )
                    }
                }

                if (selectedSigner.isNotEmpty()) {
                    HardwareCrtTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        placeholder = "Signing key passphrase (optional)",
                        isPassword = true,
                        singleLine = true,
                        testTag = "encrypt_passphrase_input",
                    )
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    color = MetaToolsTokens.StreetMagenta,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            HardwareBezelButton(
                text = "Cancel",
                onClick = onDismiss,
            )
            HardwareBezelButton(
                text = if (request.writeBackToField) "Encrypt & Fill Field" else "Encrypt & Copy",
                primary = true,
                enabled = messageText.isNotBlank() && selectedRecipient.isNotEmpty(),
                onClick = {
                    onConfirmEncrypt(
                        messageText,
                        selectedRecipient,
                        selectedSigner,
                        passphrase,
                    )
                },
                modifier = Modifier.testTag("encrypt_confirm_button"),
            )
        }
    }
}

// ============================================================================
// 4. Keyring Hardware Pod
// ============================================================================

private enum class HardwareKeyringSection {
    Keys,
    Generate,
    Import,
}

@Composable
fun KeyringHardwarePod(
    keys: List<KeyInfo>,
    defaultKeyFingerprint: String,
    errorMessage: String?,
    onGenerateKey: (userId: String, passphrase: String) -> Unit,
    onImportKey: (armored: String, passphrase: String) -> Unit,
    onExportKey: (fingerprint: String) -> Unit,
    onSetDefaultKey: (fingerprint: String) -> Unit,
    onDeleteKey: (fingerprint: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    pgpFields: List<PgpFieldState> = emptyList(),
    selectionPgp: SelectionPgpUiState? = null,
    onFieldAction: (PgpFieldState) -> Unit = {},
    onInsertMyPubKey: (PgpFieldState) -> Unit = {},
    onDecryptArmoredText: (String) -> Unit = {},
    onEncryptSelectedText: (String) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    var section by remember { mutableStateOf(HardwareKeyringSection.Keys) }
    var genUserId by remember { mutableStateOf("") }
    var genPassphrase by remember { mutableStateOf("") }
    var importArmored by remember { mutableStateOf("") }
    var importPassphrase by remember { mutableStateOf("") }
    var armedDeleteFp by remember { mutableStateOf<String?>(null) }

    HardwareModalFrame(
        title = "PGP KEYRING (${keys.size})",
        ledColor = if (keys.isNotEmpty()) MetaToolsTokens.LedGreen else MetaToolsTokens.LedAmber,
        onDismiss = onDismiss,
        modifier = modifier.testTag("keyring_sheet"),
    ) {
        SensitiveContentEffect()

        // 2-row or 1+2 action bar strictly obeying <= 2:1 sibling label density
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (section != HardwareKeyringSection.Keys) {
                HardwareChoicePill(
                    label = "Keys (${keys.size})",
                    selected = false,
                    onClick = { section = HardwareKeyringSection.Keys },
                    modifier = Modifier.weight(1f),
                )
            }
            HardwareChoicePill(
                label = "Generate",
                selected = section == HardwareKeyringSection.Generate,
                onClick = {
                    section = if (section == HardwareKeyringSection.Generate) {
                        HardwareKeyringSection.Keys
                    } else {
                        HardwareKeyringSection.Generate
                    }
                },
                modifier = Modifier.weight(1f),
            )
            HardwareChoicePill(
                label = "Import",
                selected = section == HardwareKeyringSection.Import,
                onClick = {
                    section = if (section == HardwareKeyringSection.Import) {
                        HardwareKeyringSection.Keys
                    } else {
                        HardwareKeyringSection.Import
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                color = MetaToolsTokens.StreetMagenta,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            when (section) {
                HardwareKeyringSection.Keys -> {
                    val primaryMessageBlock = pgpFields.firstOrNull { it.kind == "pgp_message" }
                    val primaryPubKeyBlock = pgpFields.firstOrNull {
                        it.kind == "pgp_public_key" && !it.label.equals("Insert My PubKey", ignoreCase = true)
                    }
                    val primaryEditableField = pgpFields.firstOrNull { it.isEditablePgpField }
                    val hasPagePgpActions = primaryMessageBlock != null ||
                        primaryPubKeyBlock != null ||
                        primaryEditableField != null ||
                        selectionPgp != null

                    if (hasPagePgpActions) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MetaToolsTokens.CrtBlackGlass)
                                .border(1.dp, MetaToolsTokens.PhosphorOnionGreen.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                                .padding(10.dp)
                                .testTag("keyring_page_pgp_actions"),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HardwareSectionHeader("PAGE OPENPGP ACTIONS")

                            if (primaryMessageBlock != null) {
                                HardwareBezelButton(
                                    text = "Decrypt Page PGP Block",
                                    primary = true,
                                    onClick = {
                                        onDismiss()
                                        onFieldAction(primaryMessageBlock)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("keyring_decrypt_page_block"),
                                )
                            } else if (selectionPgp != null && selectionPgp.kind == "pgp_message") {
                                HardwareBezelButton(
                                    text = "Decrypt Page PGP Block",
                                    primary = true,
                                    onClick = {
                                        onDismiss()
                                        onDecryptArmoredText(selectionPgp.armored.ifBlank { selectionPgp.selectedText })
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("keyring_decrypt_page_block"),
                                )
                            }

                            if (primaryPubKeyBlock != null) {
                                HardwareBezelButton(
                                    text = "Import Page PubKey",
                                    onClick = {
                                        onDismiss()
                                        onFieldAction(primaryPubKeyBlock)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("keyring_import_page_pubkey"),
                                )
                            }

                            if (primaryEditableField != null) {
                                val isPubKeyOnly = primaryEditableField.kind == "pgp_public_key" &&
                                    primaryEditableField.label.equals("Insert My PubKey", ignoreCase = true)
                                if (isPubKeyOnly) {
                                    HardwareBezelButton(
                                        text = "Insert My PubKey",
                                        primary = primaryMessageBlock == null,
                                        onClick = {
                                            onDismiss()
                                            onInsertMyPubKey(primaryEditableField)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("keyring_insert_my_pubkey"),
                                    )
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        HardwareBezelButton(
                                            text = "Encrypt Field",
                                            primary = primaryMessageBlock == null,
                                            onClick = {
                                                onDismiss()
                                                onFieldAction(primaryEditableField)
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("keyring_encrypt_page_field"),
                                        )
                                        HardwareBezelButton(
                                            text = "Insert My PubKey",
                                            onClick = {
                                                onDismiss()
                                                onInsertMyPubKey(primaryEditableField)
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("keyring_insert_my_pubkey"),
                                        )
                                    }
                                }
                            } else if (selectionPgp != null && selectionPgp.selectedText.isNotBlank()) {
                                HardwareBezelButton(
                                    text = "Encrypt Selection",
                                    onClick = {
                                        onDismiss()
                                        onEncryptSelectedText(selectionPgp.selectedText)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("keyring_encrypt_selection"),
                                )
                            }
                        }
                    }

                    if (keys.isEmpty()) {
                        Text(
                            text = "Keyring is empty. Generate your own keypair or import a hidden service public key.",
                            color = MetaToolsTokens.StencilInk,
                            fontSize = 12.5.sp,
                        )
                    } else {
                        keys.forEach { key ->
                            val isDefault = key.fingerprint == defaultKeyFingerprint ||
                                (defaultKeyFingerprint.isEmpty() && key == keys.firstOrNull { it.hasSecret })
                            val badge = when {
                                isDefault && key.hasSecret -> "DEFAULT·SEC"
                                isDefault -> "DEFAULT"
                                key.hasSecret -> "SECRET"
                                else -> "PUBLIC"
                            }
                            val deleteArmed = armedDeleteFp == key.fingerprint
                            val cardShape = RoundedCornerShape(12.dp)

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(cardShape)
                                    .background(MetaToolsTokens.AnodizedRecessedWell)
                                    .border(
                                        width = 1.dp,
                                        color = if (isDefault) {
                                            MetaToolsTokens.PhosphorOnionGreen.copy(alpha = 0.6f)
                                        } else {
                                            MetaToolsTokens.GrooveHighlight
                                        },
                                        shape = cardShape,
                                    )
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = key.userId.ifBlank { "PGP Key" },
                                            color = Color(0xFFF0F4F8),
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = formatGroupedFingerprint(key.fingerprint),
                                            color = MetaToolsTokens.StencilInk.copy(alpha = 0.78f),
                                            fontSize = 10.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    HardwareStatusChip(
                                        text = badge,
                                        accent = if (key.hasSecret) {
                                            MetaToolsTokens.PhosphorOnionGreen
                                        } else {
                                            MetaToolsTokens.PhosphorLoadingAmber
                                        },
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                ) {
                                    HardwareBezelButton(
                                        text = "Copy PubKey",
                                        compact = true,
                                        onClick = { onExportKey(key.fingerprint) },
                                    )
                                    if (key.hasSecret && !isDefault) {
                                        HardwareBezelButton(
                                            text = "Set Default",
                                            compact = true,
                                            onClick = { onSetDefaultKey(key.fingerprint) },
                                            modifier = Modifier.testTag("key_set_default_${key.fingerprint}"),
                                        )
                                    }
                                    HardwareBezelButton(
                                        text = if (deleteArmed) "Confirm ✕" else "Delete",
                                        danger = true,
                                        compact = true,
                                        onClick = {
                                            if (!deleteArmed) {
                                                armedDeleteFp = key.fingerprint
                                            } else {
                                                armedDeleteFp = null
                                                onDeleteKey(key.fingerprint)
                                            }
                                        },
                                        modifier = Modifier.testTag("key_delete_${key.fingerprint}"),
                                    )
                                }
                            }
                        }
                    }
                }

                HardwareKeyringSection.Generate -> {
                    HardwareSectionHeader("User ID (handle or pseudonym)")
                    HardwareCrtTextField(
                        value = genUserId,
                        onValueChange = { genUserId = it },
                        placeholder = "Whistleblower <drop@onion>",
                        singleLine = true,
                        testTag = "keygen_userid_input",
                    )
                    HardwareSectionHeader("Passphrase (optional)")
                    HardwareCrtTextField(
                        value = genPassphrase,
                        onValueChange = { genPassphrase = it },
                        placeholder = "Optional secret passphrase",
                        isPassword = true,
                        singleLine = true,
                        testTag = "keygen_passphrase_input",
                    )
                }

                HardwareKeyringSection.Import -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "ASCII-Armored Key Block",
                            color = MetaToolsTokens.StencilInk,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        HardwareBezelButton(
                            text = "Paste",
                            compact = true,
                            onClick = {
                                clipboard.getText()?.text?.let { importArmored = it }
                            },
                        )
                    }
                    HardwareCrtTextField(
                        value = importArmored,
                        onValueChange = { importArmored = it },
                        placeholder = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
                        singleLine = false,
                        minHeight = 96.dp,
                        testTag = "keyimport_armored_input",
                    )
                    HardwareCrtTextField(
                        value = importPassphrase,
                        onValueChange = { importPassphrase = it },
                        placeholder = "Passphrase (only for encrypted secret keys)",
                        isPassword = true,
                        singleLine = true,
                        testTag = "keyimport_passphrase_input",
                    )
                }
            }
        }

        if (section == HardwareKeyringSection.Generate) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                HardwareBezelButton(
                    text = "Generate Keypair",
                    primary = true,
                    enabled = genUserId.isNotBlank(),
                    onClick = {
                        val uid = genUserId.trim()
                        val pass = genPassphrase
                        genUserId = ""
                        genPassphrase = ""
                        onGenerateKey(uid, pass)
                        section = HardwareKeyringSection.Keys
                    },
                    modifier = Modifier.testTag("keygen_submit_button"),
                )
            }
        } else if (section == HardwareKeyringSection.Import) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                HardwareBezelButton(
                    text = "Import Key",
                    primary = true,
                    enabled = importArmored.isNotBlank(),
                    onClick = {
                        val raw = importArmored.trim()
                        val pass = importPassphrase
                        importArmored = ""
                        importPassphrase = ""
                        onImportKey(raw, pass)
                        section = HardwareKeyringSection.Keys
                    },
                    modifier = Modifier.testTag("keyimport_submit_button"),
                )
            }
        }
    }
}

// ============================================================================
// 5. Passphrase Unlock & Decrypted Plaintext Hardware Pods
// ============================================================================

@Composable
fun PassphraseUnlockHardwarePod(
    request: DecryptPassphraseRequestState,
    onSubmitPassphrase: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var passphrase by remember(request.armoredCiphertext) { mutableStateOf("") }

    HardwareModalFrame(
        title = "UNLOCK SECRET KEY",
        ledColor = MetaToolsTokens.LedAmber,
        onDismiss = onDismiss,
        modifier = modifier.testTag("decrypt_passphrase_sheet"),
    ) {
        SensitiveContentEffect()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Enter secret key passphrase to decrypt this OpenPGP block.",
                color = MetaToolsTokens.StencilInk,
                fontSize = 12.5.sp,
            )

            if (!request.error.isNullOrBlank()) {
                Text(
                    text = request.error,
                    color = MetaToolsTokens.StreetMagenta,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            HardwareCrtTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                placeholder = "Secret key passphrase",
                isPassword = true,
                singleLine = true,
                testTag = "decrypt_passphrase_input",
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            HardwareBezelButton(text = "Cancel", onClick = onDismiss)
            HardwareBezelButton(
                text = "Decrypt",
                primary = true,
                enabled = passphrase.isNotEmpty(),
                onClick = { onSubmitPassphrase(passphrase) },
                modifier = Modifier.testTag("decrypt_passphrase_submit"),
            )
        }
    }
}

@Composable
fun DecryptedPlaintextHardwarePod(
    plaintext: PgpPlaintextState,
    onCopy: (String) -> Unit,
    onInsert: (PgpPlaintextState) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = plaintext.signatureStatus.lowercase()
    val signerName = plaintext.signerUserId?.takeIf { it.isNotBlank() }
    val fpSuffix = plaintext.signerFingerprint
        ?.replace(" ", "")
        ?.takeIf { it.isNotEmpty() }
        ?.takeLast(16)

    val (sigHeadline, sigColor) = when (status) {
        "good" -> (if (signerName != null) "GOOD SIG · $signerName" else "GOOD SIGNATURE") to MetaToolsTokens.PhosphorOnionGreen
        "bad" -> "INVALID SIGNATURE — FORGED OR MODIFIED" to MetaToolsTokens.StreetMagenta
        "unknown_key" -> "SIGNED BY UNKNOWN KEY" to MetaToolsTokens.PhosphorLoadingAmber
        else -> "UNSIGNED PGP MESSAGE" to MetaToolsTokens.StencilInk
    }

    HardwareModalFrame(
        title = plaintext.title.ifBlank { "DECRYPTED MESSAGE" },
        ledColor = if (status == "bad") MetaToolsTokens.StreetMagenta else MetaToolsTokens.LedGreen,
        onDismiss = onClose,
        modifier = modifier.testTag("plaintext_sheet"),
    ) {
        SensitiveContentEffect()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MetaToolsTokens.AnodizedRecessedWell)
                    .border(1.dp, sigColor.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = sigHeadline,
                    color = sigColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (fpSuffix != null) {
                    Text(
                        text = "KEY · $fpSuffix",
                        color = MetaToolsTokens.StencilInk.copy(alpha = 0.78f),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MetaToolsTokens.CrtBlackGlass)
                    .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = plaintext.body,
                    color = MetaToolsTokens.PhosphorClearnetGreen,
                    fontSize = 13.sp,
                    lineHeight = 18.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HardwareBezelButton(
                text = "Copy",
                onClick = { onCopy(plaintext.body) },
                modifier = Modifier.testTag("plaintext_copy"),
            )
            if (plaintext.targetEditable && plaintext.nodeId.isNotEmpty()) {
                HardwareBezelButton(
                    text = "Insert",
                    onClick = { onInsert(plaintext) },
                    modifier = Modifier.testTag("plaintext_insert"),
                )
            }
            HardwareBezelButton(
                text = "Close",
                primary = true,
                onClick = onClose,
                modifier = Modifier.testTag("plaintext_close"),
            )
        }
    }
}

// ============================================================================
// 6. Settings & Licences Hardware Pods
// ============================================================================

@Composable
fun SettingsHardwarePod(
    state: BrowserUiState,
    onSelectThemeFamily: (TrapThemeFamily) -> Unit,
    onSelectThemeMode: (TrapThemeMode) -> Unit,
    onToggleHiddenServiceOnly: (Boolean) -> Unit,
    onToggleRequireBiometric: (Boolean) -> Unit,
    onSelectDefaultZoom: (Int) -> Unit,
    onRevokeClearnetRule: (String) -> Unit = {},
    onClearClearnetRules: () -> Unit = {},
    onTogglePipOnMinimize: (Boolean) -> Unit = {},
    onTogglePipExcludePrivateTabs: (Boolean) -> Unit = {},
    onSelectSurfaceFilterMode: (SurfaceFilterMode) -> Unit = {},
    onToggleHandoff: (Boolean) -> Unit = {},
    onClearSession: () -> Unit,
    onOpenLegalNotices: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClearSession by remember { mutableStateOf(false) }
    val nowMs = System.currentTimeMillis()

    HardwareModalFrame(
        title = "HARDWARE SETTINGS",
        onDismiss = onDismiss,
        modifier = modifier.testTag("settings_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HardwareSectionHeader("Theme Family")
            HardwareTwoColumnGrid(
                options = listOf(
                    TrapThemeFamily.SecureDrop to "SecureDrop",
                    TrapThemeFamily.NeonGraffiti to "Neon Graffiti",
                ),
                selected = state.themeFamily,
                onSelect = onSelectThemeFamily,
                modifier = Modifier.testTag("settings_theme_family"),
            )

            HardwareSectionHeader("Appearance Mode")
            HardwareTwoColumnGrid(
                options = listOf(
                    TrapThemeMode.Dark to "Dark",
                    TrapThemeMode.Light to "Light",
                ),
                selected = state.themeMode,
                onSelect = onSelectThemeMode,
                modifier = Modifier.testTag("settings_theme_mode"),
            )

            HardwareSectionHeader("Page Surface Filter")
            HardwareTwoColumnGrid(
                options = SurfaceFilterMode.entries.map { it to it.label },
                selected = state.surfaceFilterMode,
                onSelect = onSelectSurfaceFilterMode,
                modifier = Modifier.testTag("settings_surface_filter"),
            )

            HardwareToggleRow(
                title = "Hidden Service Only",
                subtitle = "Block non-.onion traffic in Cefpack & SOCKS5",
                checked = state.hiddenServiceOnly,
                onCheckedChange = onToggleHiddenServiceOnly,
                testTag = "settings_hidden_service_switch",
            )

            if (state.clearnetRules.isNotEmpty()) {
                HardwareSectionHeader("Clearnet Rules (${state.clearnetRules.size})")
                state.clearnetRules.forEach { rule ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MetaToolsTokens.AnodizedRecessedWell)
                            .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rule.pattern,
                                color = Color.White,
                                fontSize = 12.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatClearnetRuleTtl(rule, nowMs),
                                color = MetaToolsTokens.PhosphorClearnetGreen,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        HardwareBezelButton(
                            text = "Revoke",
                            danger = true,
                            compact = true,
                            onClick = { onRevokeClearnetRule(rule.pattern) },
                            modifier = Modifier.testTag("clearnet_revoke_${rule.pattern}"),
                        )
                    }
                }
                HardwareBezelButton(
                    text = "Clear all clearnet rules",
                    danger = true,
                    onClick = onClearClearnetRules,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("clearnet_clear_all_button"),
                )
            }

            HardwareToggleRow(
                title = "Require Biometric Unlock",
                subtitle = "Verify identity before decrypting or signing",
                checked = state.requireBiometric,
                onCheckedChange = onToggleRequireBiometric,
                testTag = "settings_biometric_switch",
            )

            HardwareToggleRow(
                title = "Picture-in-Picture on Minimize",
                subtitle = "Float active page when leaving Trap",
                checked = state.pipOnMinimizeEnabled,
                onCheckedChange = onTogglePipOnMinimize,
                testTag = "settings_pip_on_minimize_switch",
            )

            if (state.pipOnMinimizeEnabled) {
                HardwareToggleRow(
                    title = "Exclude Private Tabs from PiP",
                    subtitle = "Never float private tabs",
                    checked = state.pipExcludePrivateTabs,
                    onCheckedChange = onTogglePipExcludePrivateTabs,
                    testTag = "settings_pip_exclude_private_switch",
                )
            }

            HardwareToggleRow(
                title = "Cross-Device Tab Handoff",
                subtitle = "Resume non-private tabs across devices",
                checked = state.handoffEnabled,
                onCheckedChange = onToggleHandoff,
                testTag = "settings_handoff_switch",
            )

            HardwareSectionHeader("Default Page Zoom (${state.defaultZoomPercent}%)")
            HardwareTwoColumnGrid(
                options = listOf(
                    75 to "75%",
                    90 to "90%",
                    100 to "100%",
                    125 to "125%",
                    150 to "150%",
                ),
                selected = state.defaultZoomPercent,
                onSelect = onSelectDefaultZoom,
                modifier = Modifier.testTag("settings_default_zoom"),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HardwareBezelButton(
                    text = "Licences",
                    onClick = {
                        onDismiss()
                        onOpenLegalNotices()
                    },
                    modifier = Modifier.weight(1f),
                )
                HardwareBezelButton(
                    text = if (confirmClearSession) "Confirm Wipe?" else "Clear Session",
                    danger = true,
                    onClick = {
                        if (!confirmClearSession) {
                            confirmClearSession = true
                        } else {
                            confirmClearSession = false
                            onClearSession()
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_clear_session"),
                )
            }
        }
    }
}

@Composable
fun LegalNoticesHardwarePod(
    documents: List<LegalDocument>,
    onLoadLegalLines: suspend (String) -> List<String>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeDoc by remember { mutableStateOf<LegalDocument?>(null) }
    var docLines by remember(activeDoc?.name) { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(activeDoc?.name) {
        val doc = activeDoc ?: return@LaunchedEffect
        docLines = withContext(Dispatchers.IO) { onLoadLegalLines(doc.name) }
    }

    HardwareModalFrame(
        title = activeDoc?.title ?: "OPEN-SOURCE LICENCES",
        onDismiss = {
            if (activeDoc != null) {
                activeDoc = null
            } else {
                onDismiss()
            }
        },
        modifier = modifier.testTag("legal_notices_pod"),
    ) {
        val currentDoc = activeDoc
        if (currentDoc == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Trap bundles Cefpack (MIT OR Apache-2.0 / Chromium BSD-3-Clause), Sequoia OpenPGP (LGPL-2.0-or-later), and Arti (MIT OR Apache-2.0).",
                    color = MetaToolsTokens.StencilInk,
                    fontSize = 12.sp,
                )
                documents.forEach { doc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MetaToolsTokens.AnodizedRecessedWell)
                            .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button, onClick = { activeDoc = doc })
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = doc.title,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "READ",
                            color = MetaToolsTokens.PhosphorClearnetGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        } else {
            val loaded = docLines
            if (loaded == null) {
                Text(
                    text = "Loading ${currentDoc.title}…",
                    color = MetaToolsTokens.PhosphorClearnetGreen,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MetaToolsTokens.CrtBlackGlass)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(loaded) { line ->
                        Text(
                            text = line,
                            color = MetaToolsTokens.StencilInk,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// Shared Hardware Modal Sub-Controls (<= 2:1 Label Density Enforced)
// ============================================================================

@Composable
internal fun HardwareSectionHeader(title: String) {
    Text(
        text = title,
        color = MetaToolsTokens.StencilInk,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
internal fun HardwareStatusChip(
    text: String,
    accent: Color,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(MetaToolsTokens.CrtBlackGlass)
            .border(1.dp, accent.copy(alpha = 0.55f), shape)
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@Composable
internal fun HardwareBezelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
    compact: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val shift = if (isPressed && enabled) 1.dp else 0.dp
    val shape = RoundedCornerShape(if (compact) 8.dp else 10.dp)

    val container = when {
        !enabled -> MetaToolsTokens.AnodizedRecessedWell
        primary -> Color(0xFFD8FF00)
        danger -> Color(0xFF3A121C)
        else -> MetaToolsTokens.AnodizedSurfaceTop
    }
    val ink = when {
        !enabled -> MetaToolsTokens.PhosphorPathDim
        primary -> Color(0xFF0A0D00)
        danger -> MetaToolsTokens.StreetMagenta
        else -> Color(0xFFF0F4F8)
    }
    val borderColor = when {
        !enabled -> MetaToolsTokens.GrooveShadow
        primary -> Color(0xFFE9FF66)
        danger -> MetaToolsTokens.StreetMagenta.copy(alpha = 0.7f)
        isPressed -> MetaToolsTokens.AnodizedBevelDark
        else -> MetaToolsTokens.AnodizedBevelLight
    }

    Box(
        modifier = modifier
            .heightIn(min = if (compact) 30.dp else 40.dp)
            .clip(shape)
            .background(container)
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 5.dp else 9.dp,
            )
            .offset(x = shift, y = shift),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = ink,
            fontSize = if (compact) 11.5.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@Composable
internal fun HardwareChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (selected) MetaToolsTokens.CrtBlackGlass else MetaToolsTokens.AnodizedRecessedWell)
            .border(
                width = 1.dp,
                color = if (selected) MetaToolsTokens.PhosphorOnionGreen else MetaToolsTokens.GrooveHighlight,
                shape = shape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Canvas(modifier = Modifier.size(7.dp)) {
            drawRecessedGlassLedBead(
                center = center,
                beadDiameterPx = 5.5.dp.toPx(),
                litColor = if (selected) MetaToolsTokens.LedGreen else null,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = if (selected) Color.White else MetaToolsTokens.StencilInk,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Arranges multi-option selectors in 2-column hardware pairs so no row EVER stacks
 * 3+ adjacent inline chips (strict `<= 2:1` UI label density).
 */
@Composable
private fun <T> HardwareTwoColumnGrid(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEach { (value, label) ->
                    HardwareChoicePill(
                        label = label,
                        selected = value == selected,
                        onClick = { onSelect(value) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HardwareKeyRow(
    title: String,
    subtitle: String,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MetaToolsTokens.CrtBlackGlass else MetaToolsTokens.AnodizedRecessedWell)
            .border(
                width = 1.dp,
                color = if (selected) MetaToolsTokens.PhosphorOnionGreen else MetaToolsTokens.GrooveHighlight,
                shape = shape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawRecessedGlassLedBead(
                center = center,
                beadDiameterPx = 6.dp.toPx(),
                litColor = if (selected) MetaToolsTokens.LedGreen else null,
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = MetaToolsTokens.StencilInk.copy(alpha = 0.78f),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            HardwareStatusChip(
                text = badge,
                accent = if (selected) MetaToolsTokens.PhosphorOnionGreen else MetaToolsTokens.StencilInk,
            )
        }
    }
}

@Composable
private fun HardwareToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MetaToolsTokens.AnodizedRecessedWell)
            .border(1.dp, MetaToolsTokens.GrooveHighlight, shape)
            .clickable(role = Role.Switch, onClick = { onCheckedChange(!checked) })
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = MetaToolsTokens.StencilInk.copy(alpha = 0.75f),
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF041A0B),
                checkedTrackColor = MetaToolsTokens.LedGreen,
                uncheckedThumbColor = MetaToolsTokens.StencilInk,
                uncheckedTrackColor = MetaToolsTokens.AnodizedSurfaceTop,
                uncheckedBorderColor = MetaToolsTokens.GrooveHighlight,
            ),
        )
    }
}

@Composable
internal fun HardwareCrtTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
    minHeight: Dp = 44.dp,
    testTag: String? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(shape)
            .background(MetaToolsTokens.CrtBlackGlass)
            .border(1.dp, MetaToolsTokens.GrooveHighlight, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            visualTransformation = if (isPassword) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = keyboardType,
            ),
            textStyle = TextStyle(
                color = MetaToolsTokens.PhosphorOnionGreen,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(MetaToolsTokens.PhosphorOnionGreen),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = MetaToolsTokens.PhosphorMutedHint,
                            fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

// ============================================================================
// 8. Monero Light Wallet Hardware Pod (`xmr_wallet_sheet`)
// ============================================================================

private enum class XmrOnboardingMode {
    Create,
    Restore,
}

@Composable
fun MoneroWalletHardwarePod(
    xmr: MoneroUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onCreateWallet: (label: String) -> Unit = {},
    onRestoreWallet: (mnemonic: String, restoreHeight: Long) -> Unit = { _, _ -> },
    onSyncWallet: () -> Unit = {},
    onNewSubaddress: (label: String) -> Unit = {},
    onSendXmr: (destinationAddress: String, amountXmr: String, sweepAll: Boolean) -> Unit = { _, _, _ -> },
    onSetDaemonEndpoint: (endpointUrl: String, mode: String) -> Unit = { _, _ -> },
    onRevealSeed: () -> Unit = {},
    onHideSeed: () -> Unit = {},
    onDismissNewSeedBackup: () -> Unit = {},
    onDeleteWallet: () -> Unit = {},
    onOpenSwap: () -> Unit = {},
    onCopyText: (String) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val status = xmr.status

    fun copyString(text: String) {
        if (text.isBlank()) return
        runCatching { clipboard.setText(AnnotatedString(text)) }
        onCopyText(text)
    }

    var onboardingMode by remember { mutableStateOf(XmrOnboardingMode.Create) }
    var createLabel by remember { mutableStateOf("Trap Light Wallet") }
    var restoreMnemonic by remember { mutableStateOf("") }
    var restoreHeightInput by remember { mutableStateOf("") }

    var activeTab by remember(xmr.walletInitialTab, xmr.prefillSendAddress) {
        mutableStateOf(
            if (xmr.prefillSendAddress.isNotBlank()) {
                MoneroWalletTab.Send
            } else {
                xmr.walletInitialTab
            },
        )
    }
    var newSubaddressLabel by remember { mutableStateOf("") }

    var destAddress by remember(xmr.prefillSendAddress) { mutableStateOf(xmr.prefillSendAddress) }
    var amountInput by remember(xmr.prefillSendAmount) { mutableStateOf(xmr.prefillSendAmount) }
    var sweepAll by remember { mutableStateOf(false) }

    var syncMode by remember(status.syncMode) {
        mutableStateOf(status.syncMode.ifBlank { "monerod_onion" })
    }
    var daemonEndpointInput by remember(status.syncEndpoint) {
        mutableStateOf(status.syncEndpoint)
    }
    var confirmDeleteWallet by remember { mutableStateOf(false) }

    HardwareModalFrame(
        title = "MONERO LIGHT WALLET",
        ledColor = if (status.hasWallet) MetaToolsTokens.LedGreen else MetaToolsTokens.LedAmber,
        onDismiss = onDismiss,
        modifier = modifier.testTag("xmr_wallet_sheet"),
        headerAction = if (status.hasWallet) {
            {
                HardwareBezelButton(
                    text = if (xmr.syncing) "Syncing…" else "Sync Tor",
                    compact = true,
                    primary = true,
                    enabled = !xmr.syncing,
                    onClick = onSyncWallet,
                    modifier = Modifier.testTag("xmr_sync_button"),
                )
            }
        } else {
            null
        },
    ) {
        SensitiveContentEffect()

        if (!xmr.error.isNullOrBlank()) {
            Text(
                text = xmr.error,
                color = MetaToolsTokens.StreetMagenta,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .testTag("xmr_wallet_error"),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!status.hasWallet) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MetaToolsTokens.CrtBlackGlass)
                        .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "No Wallet Created",
                        color = MetaToolsTokens.PhosphorLoadingAmber,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("xmr_empty_wallet_title"),
                    )
                    Text(
                        text = "25-word Monero light wallet wrapped in Android Keystore and synced fail-closed over Arti Tor.",
                        color = MetaToolsTokens.StencilInk,
                        fontSize = 11.5.sp,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HardwareChoicePill(
                        label = "Create New",
                        selected = onboardingMode == XmrOnboardingMode.Create,
                        onClick = { onboardingMode = XmrOnboardingMode.Create },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("xmr_mode_create"),
                    )
                    HardwareChoicePill(
                        label = "Restore Seed",
                        selected = onboardingMode == XmrOnboardingMode.Restore,
                        onClick = { onboardingMode = XmrOnboardingMode.Restore },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("xmr_mode_restore"),
                    )
                }

                when (onboardingMode) {
                    XmrOnboardingMode.Create -> {
                        HardwareSectionHeader("Wallet Label")
                        HardwareCrtTextField(
                            value = createLabel,
                            onValueChange = { createLabel = it },
                            placeholder = "Trap Light Wallet",
                            singleLine = true,
                            testTag = "xmr_create_label_input",
                        )
                        HardwareBezelButton(
                            text = if (xmr.creatingOrRestoring) {
                                "Generating Seed…"
                            } else {
                                "Create 25-Word Wallet"
                            },
                            primary = true,
                            enabled = !xmr.creatingOrRestoring,
                            onClick = {
                                focusManager.clearFocus()
                                onCreateWallet(createLabel.trim().ifEmpty { "Trap Light Wallet" })
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("xmr_create_wallet_button"),
                        )
                    }

                    XmrOnboardingMode.Restore -> {
                        HardwareSectionHeader("25-Word Monero Electrum Seed")
                        HardwareCrtTextField(
                            value = restoreMnemonic,
                            onValueChange = { restoreMnemonic = it },
                            placeholder = "Enter 25 space-separated seed words…",
                            singleLine = false,
                            minHeight = 76.dp,
                            testTag = "xmr_restore_mnemonic_input",
                        )
                        HardwareSectionHeader("Restore Height (Optional)")
                        HardwareCrtTextField(
                            value = restoreHeightInput,
                            onValueChange = { next ->
                                restoreHeightInput = next.filter { it.isDigit() }
                            },
                            placeholder = "0 (or wallet creation block height)",
                            singleLine = true,
                            keyboardType = KeyboardType.Number,
                            testTag = "xmr_restore_height_input",
                        )
                        val wordCount = restoreMnemonic.trim()
                            .split(Regex("\\s+"))
                            .count { it.isNotEmpty() }
                        HardwareBezelButton(
                            text = if (xmr.creatingOrRestoring) {
                                "Restoring Wallet…"
                            } else {
                                "Restore Light Wallet ($wordCount/25)"
                            },
                            primary = true,
                            enabled = !xmr.creatingOrRestoring && wordCount == 25,
                            onClick = {
                                focusManager.clearFocus()
                                val height = restoreHeightInput.trim().toLongOrNull() ?: 0L
                                onRestoreWallet(restoreMnemonic.trim(), height)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("xmr_restore_wallet_button"),
                        )
                    }
                }
            } else {
                // Backup seed banner immediately after fresh wallet creation
                if (!xmr.newlyCreatedMnemonic.isNullOrBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MetaToolsTokens.CrtBlackGlass)
                            .border(1.dp, MetaToolsTokens.PhosphorLoadingAmber, RoundedCornerShape(12.dp))
                            .padding(10.dp)
                            .testTag("xmr_new_seed_backup_box"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HardwareSectionHeader("BACKUP 25-WORD RECOVERY SEED")
                        Text(
                            text = xmr.newlyCreatedMnemonic,
                            color = MetaToolsTokens.PhosphorOnionGreen,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("xmr_seed_words_text"),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HardwareBezelButton(
                                text = "Copy Seed",
                                primary = true,
                                compact = true,
                                onClick = { copyString(xmr.newlyCreatedMnemonic) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("xmr_copy_seed_button"),
                            )
                            HardwareBezelButton(
                                text = "Saved",
                                compact = true,
                                onClick = onDismissNewSeedBackup,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("xmr_dismiss_new_seed_button"),
                            )
                        }
                    }
                }

                // Balance & .onion Daemon Height CRT Readout (<= 2:1 label density)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MetaToolsTokens.CrtBlackGlass)
                        .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${status.unlockedBalanceXmr} XMR",
                            color = MetaToolsTokens.PhosphorOnionGreen,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("xmr_unlocked_balance_text"),
                        )
                        Text(
                            text = "Total ${status.balanceXmr} XMR",
                            color = MetaToolsTokens.StencilInk,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("xmr_total_balance_text"),
                        )
                    }
                    val heightReadout = when {
                        status.daemonHeight > 0L ->
                            "Synced ${status.syncedHeight} / ${status.daemonHeight} (${if (status.syncMode == "lws") "LWS" else ".onion"})"
                        status.birthdayPending ->
                            "Height pending Tor sync"
                        else ->
                            "Synced #${status.syncedHeight}"
                    }
                    Text(
                        text = heightReadout,
                        color = MetaToolsTokens.PhosphorClearnetGreen,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("xmr_sync_height_text"),
                    )
                }

                // 2x2 Hardware Section Grid (strictly <= 2 pills per row!)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HardwareChoicePill(
                            label = "Receive",
                            selected = activeTab == MoneroWalletTab.Receive,
                            onClick = { activeTab = MoneroWalletTab.Receive },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("xmr_tab_receive"),
                        )
                        HardwareChoicePill(
                            label = "Send",
                            selected = activeTab == MoneroWalletTab.Send,
                            onClick = { activeTab = MoneroWalletTab.Send },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("xmr_tab_send"),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HardwareChoicePill(
                            label = "Node / Seed",
                            selected = activeTab == MoneroWalletTab.NodeSeed,
                            onClick = { activeTab = MoneroWalletTab.NodeSeed },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("xmr_tab_node_seed"),
                        )
                        HardwareBezelButton(
                            text = "Swap BTC → XMR",
                            compact = true,
                            onClick = onOpenSwap,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("xmr_open_swap_button"),
                        )
                    }
                }

                when (activeTab) {
                    MoneroWalletTab.Receive -> {
                        val activeAddr = status.activeSubaddress.ifBlank { status.primaryAddress }
                        HardwareSectionHeader("Active 8… Subaddress")
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MetaToolsTokens.CrtBlackGlass)
                                .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                        ) {
                            Text(
                                text = activeAddr,
                                color = MetaToolsTokens.PhosphorOnionGreen,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.testTag("xmr_active_subaddress_text"),
                            )
                        }

                        HardwareCrtTextField(
                            value = newSubaddressLabel,
                            onValueChange = { newSubaddressLabel = it },
                            placeholder = "Optional subaddress label (e.g. Source Drop)",
                            singleLine = true,
                            testTag = "xmr_subaddress_label_input",
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HardwareBezelButton(
                                text = "Copy 8…",
                                primary = true,
                                enabled = activeAddr.isNotBlank(),
                                onClick = { copyString(activeAddr) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("xmr_copy_subaddress_button"),
                            )
                            HardwareBezelButton(
                                text = "+ New Subaddress",
                                onClick = {
                                    val label = newSubaddressLabel.trim()
                                    newSubaddressLabel = ""
                                    onNewSubaddress(label)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("xmr_new_subaddress_button"),
                            )
                        }

                        if (status.subaddresses.isNotEmpty()) {
                            HardwareSectionHeader("Subaddresses (${status.subaddresses.size})")
                            status.subaddresses.forEach { sub ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MetaToolsTokens.AnodizedRecessedWell)
                                        .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(10.dp))
                                        .clickable(
                                            role = Role.Button,
                                            onClick = { copyString(sub.address) },
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .testTag("xmr_subaddr_row_${sub.index}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "#${sub.index} ${sub.label.ifBlank { "Subaddress" }}",
                                            color = Color.White,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${truncateCryptoAddress(sub.address)} · ${sub.receivedXmr} XMR",
                                            color = MetaToolsTokens.StencilInk.copy(alpha = 0.8f),
                                            fontSize = 10.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    HardwareStatusChip(
                                        text = "COPY",
                                        accent = MetaToolsTokens.PhosphorClearnetGreen,
                                    )
                                }
                            }
                        }

                        HardwareSectionHeader("Recent Transactions (${status.recentTxs.size})")
                        if (status.recentTxs.isEmpty()) {
                            Text(
                                text = "No transactions scanned yet.",
                                color = MetaToolsTokens.StencilInk,
                                fontSize = 12.sp,
                                modifier = Modifier.testTag("xmr_empty_txs_text"),
                            )
                        } else {
                            status.recentTxs.forEach { tx ->
                                val isIncoming = tx.direction.equals("in", ignoreCase = true)
                                val signPrefix = if (isIncoming) "+" else "-"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MetaToolsTokens.AnodizedRecessedWell)
                                        .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(10.dp))
                                        .clickable(
                                            role = Role.Button,
                                            onClick = { copyString(tx.txid) },
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .testTag("xmr_tx_row_${tx.txid.take(12)}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "$signPrefix${tx.amountXmr} XMR",
                                            color = if (isIncoming) {
                                                MetaToolsTokens.PhosphorOnionGreen
                                            } else {
                                                MetaToolsTokens.PhosphorLoadingAmber
                                            },
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                        Text(
                                            text = "${truncateCryptoAddress(tx.txid)} · #${tx.height}",
                                            color = MetaToolsTokens.StencilInk.copy(alpha = 0.78f),
                                            fontSize = 10.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    HardwareStatusChip(
                                        text = if (tx.unlocked) "UNLOCKED" else "LOCKED",
                                        accent = if (tx.unlocked) {
                                            MetaToolsTokens.PhosphorOnionGreen
                                        } else {
                                            MetaToolsTokens.PhosphorLoadingAmber
                                        },
                                    )
                                }
                            }
                        }
                    }

                    MoneroWalletTab.Send -> {
                        HardwareSectionHeader("Destination 4… / 8… Address or monero: URI")
                        HardwareCrtTextField(
                            value = destAddress,
                            onValueChange = { input ->
                                val parsedUri = OvNative.parseMoneroUri(input)
                                if (parsedUri != null) {
                                    destAddress = parsedUri.address
                                    if (parsedUri.amountXmr.isNotBlank()) {
                                        amountInput = parsedUri.amountXmr
                                    }
                                } else {
                                    destAddress = input.trim()
                                }
                            },
                            placeholder = "8… or 4… Monero mainnet address",
                            singleLine = true,
                            testTag = "xmr_send_address_input",
                        )

                        HardwareSectionHeader("Amount (XMR)")
                        HardwareCrtTextField(
                            value = if (sweepAll) "" else amountInput,
                            onValueChange = { next ->
                                if (!sweepAll) {
                                    amountInput = next.trim()
                                }
                            },
                            placeholder = if (sweepAll) {
                                "Sweeping full unlocked balance (${status.unlockedBalanceXmr} XMR)"
                            } else {
                                "0.25"
                            },
                            singleLine = true,
                            keyboardType = KeyboardType.Decimal,
                            testTag = "xmr_send_amount_input",
                        )

                        HardwareToggleRow(
                            title = "Sweep All Unlocked XMR",
                            subtitle = "Spend entire unlocked balance minus RingCT network fee",
                            checked = sweepAll,
                            onCheckedChange = { sweepAll = it },
                            testTag = "xmr_sweep_all_toggle",
                        )

                        val destValid = OvNative.isValidMoneroAddress(destAddress)
                        val amountValid = sweepAll || (
                            amountInput.isNotBlank() &&
                                amountInput.toDoubleOrNull()?.let { it > 0.0 } == true
                            )
                        HardwareBezelButton(
                            text = if (xmr.sending) {
                                "Broadcasting Ring-16 TX…"
                            } else {
                                "Sign & Broadcast XMR"
                            },
                            primary = true,
                            enabled = !xmr.sending && destValid && amountValid,
                            onClick = {
                                focusManager.clearFocus()
                                onSendXmr(destAddress.trim(), amountInput.trim(), sweepAll)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("xmr_send_submit_button"),
                        )

                        if (!xmr.lastTxid.isNullOrBlank()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MetaToolsTokens.CrtBlackGlass)
                                    .border(1.dp, MetaToolsTokens.PhosphorOnionGreen, RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                                    .testTag("xmr_send_receipt_box"),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "Broadcast TX: ${xmr.lastTxid}",
                                    color = MetaToolsTokens.PhosphorOnionGreen,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.testTag("xmr_last_txid_text"),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "Fee ${xmr.lastFeeXmr.orEmpty().ifBlank { "0" }} XMR",
                                        color = MetaToolsTokens.StencilInk,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    HardwareBezelButton(
                                        text = "Copy TXID",
                                        compact = true,
                                        onClick = { copyString(xmr.lastTxid) },
                                        modifier = Modifier.testTag("xmr_copy_txid_button"),
                                    )
                                }
                            }
                        }
                    }

                    MoneroWalletTab.NodeSeed -> {
                        HardwareSectionHeader("Tor Sync Mode")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HardwareChoicePill(
                                label = ".onion monerod",
                                selected = syncMode != "lws",
                                onClick = { syncMode = "monerod_onion" },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("xmr_mode_monerod"),
                            )
                            HardwareChoicePill(
                                label = "Self-Hosted LWS",
                                selected = syncMode == "lws",
                                onClick = { syncMode = "lws" },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("xmr_mode_lws"),
                            )
                        }

                        HardwareSectionHeader("Custom Endpoint URL (Blank = 4-Node .onion Pool)")
                        HardwareCrtTextField(
                            value = daemonEndpointInput,
                            onValueChange = { daemonEndpointInput = it },
                            placeholder = "http://plowsoff…6tyd.onion:18089",
                            singleLine = true,
                            keyboardType = KeyboardType.Uri,
                            testTag = "xmr_daemon_endpoint_input",
                        )

                        HardwareBezelButton(
                            text = "Save Sync Endpoint",
                            onClick = {
                                focusManager.clearFocus()
                                onSetDaemonEndpoint(daemonEndpointInput.trim(), syncMode)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("xmr_save_daemon_button"),
                        )

                        EngravedGrooveDivider()

                        HardwareSectionHeader("25-Word Recovery Seed")
                        if (xmr.revealedMnemonic.isNullOrBlank()) {
                            HardwareBezelButton(
                                text = "Reveal 25-Word Seed",
                                onClick = onRevealSeed,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("xmr_reveal_seed_button"),
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MetaToolsTokens.CrtBlackGlass)
                                    .border(1.dp, MetaToolsTokens.PhosphorLoadingAmber, RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = xmr.revealedMnemonic,
                                    color = MetaToolsTokens.PhosphorOnionGreen,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.testTag("xmr_revealed_seed_text"),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    HardwareBezelButton(
                                        text = "Copy Seed",
                                        primary = true,
                                        compact = true,
                                        onClick = { copyString(xmr.revealedMnemonic) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("xmr_copy_revealed_seed_button"),
                                    )
                                    HardwareBezelButton(
                                        text = "Hide Seed",
                                        compact = true,
                                        onClick = onHideSeed,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("xmr_hide_seed_button"),
                                    )
                                }
                            }
                        }

                        HardwareBezelButton(
                            text = if (confirmDeleteWallet) {
                                "Confirm Delete Wallet?"
                            } else {
                                "Delete Wallet"
                            },
                            danger = true,
                            onClick = {
                                if (!confirmDeleteWallet) {
                                    confirmDeleteWallet = true
                                } else {
                                    confirmDeleteWallet = false
                                    onDeleteWallet()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("xmr_delete_wallet_button"),
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// 9. BTC -> XMR Zero-JS Tor Swap Hardware Pod (`btc_xmr_swap_sheet`)
// ============================================================================

@Composable
fun BtcToXmrSwapHardwarePod(
    xmr: MoneroUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onFetchQuotes: (btcAmount: String) -> Unit = {},
    onLaunchQuoteInBrowser: (quote: OvNative.BtcSwapQuoteItem, btcAmount: String) -> Unit = { _, _ -> },
    onOpenTrackedSwapPage: (OvNative.TrackedBtcXmrSwap) -> Unit = {},
    onDismissSwap: (swapId: String) -> Unit = {},
    onSyncWallet: () -> Unit = {},
    onOpenWallet: () -> Unit = {},
    onCopyText: (String) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    var btcAmount by remember(xmr.quotesBtcAmount) {
        mutableStateOf(xmr.quotesBtcAmount.ifBlank { "0.01" })
    }

    fun copyString(text: String) {
        if (text.isBlank()) return
        runCatching { clipboard.setText(AnnotatedString(text)) }
        onCopyText(text)
    }

    HardwareModalFrame(
        title = "SWAP BTC → XMR (TOR)",
        ledColor = if (xmr.quotes.isNotEmpty()) MetaToolsTokens.LedGreen else MetaToolsTokens.LedAmber,
        onDismiss = onDismiss,
        modifier = modifier.testTag("btc_xmr_swap_sheet"),
        headerAction = {
            HardwareBezelButton(
                text = "Wallet",
                compact = true,
                onClick = onOpenWallet,
                modifier = Modifier.testTag("swap_open_wallet_button"),
            )
        },
    ) {
        SensitiveContentEffect()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Live zero-JS aggregator quotes via OrangeFren .onion over Arti Tor. Each swap mints a fresh unlinkable 8… subaddress.",
                color = MetaToolsTokens.StencilInk,
                fontSize = 11.5.sp,
            )

            HardwareSectionHeader("Send BTC Amount")
            HardwareCrtTextField(
                value = btcAmount,
                onValueChange = { btcAmount = it.trim() },
                placeholder = "0.01",
                singleLine = true,
                keyboardType = KeyboardType.Decimal,
                testTag = "swap_btc_amount_input",
            )

            val amountPositive = btcAmount.toDoubleOrNull()?.let { it > 0.0 } == true
            HardwareBezelButton(
                text = if (xmr.quotesLoading) {
                    "Fetching Live .onion Quotes…"
                } else {
                    "Fetch Live Tor Quotes"
                },
                primary = true,
                enabled = !xmr.quotesLoading && amountPositive,
                onClick = {
                    focusManager.clearFocus()
                    onFetchQuotes(btcAmount.trim())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("swap_fetch_quotes_button"),
            )

            if (!xmr.error.isNullOrBlank()) {
                Text(
                    text = xmr.error,
                    color = MetaToolsTokens.StreetMagenta,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag("swap_error_text"),
                )
            }

            if (xmr.quotes.isEmpty() && !xmr.quotesLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MetaToolsTokens.CrtBlackGlass)
                        .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        text = if (!xmr.quotesHasFetched) {
                            "Enter BTC amount & tap Fetch Tor Quotes"
                        } else {
                            "No zero-JS quotes returned for ${xmr.quotesBtcAmount} BTC."
                        },
                        color = MetaToolsTokens.PhosphorMutedHint,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("swap_quotes_empty_text"),
                    )
                }
            } else if (xmr.quotes.isNotEmpty()) {
                HardwareSectionHeader("Live Zero-JS Offers (${xmr.quotes.size})")
                xmr.quotes.forEach { quote ->
                    // Single combined badge strictly enforcing <= 2:1 UI label density
                    val singleChip = when {
                        quote.isOnionUrl && quote.neverKyc -> ".ONION·NO-KYC"
                        quote.isOnionUrl -> ".ONION"
                        quote.neverKyc -> "NO-KYC"
                        else -> "CLEARNET"
                    }
                    val chipAccent = if (quote.isOnionUrl) {
                        MetaToolsTokens.PhosphorOnionGreen
                    } else {
                        MetaToolsTokens.PhosphorLoadingAmber
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MetaToolsTokens.AnodizedRecessedWell)
                            .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .testTag("swap_quote_card_${quote.id}"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = quote.provider,
                                    color = Color(0xFFF0F4F8),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val rateSuffix = if (quote.rateXmrPerBtc.isNotBlank()) {
                                    " · 1 BTC ≈ ${quote.rateXmrPerBtc} XMR"
                                } else {
                                    ""
                                }
                                Text(
                                    text = "${quote.estimatedXmr} XMR$rateSuffix",
                                    color = MetaToolsTokens.PhosphorOnionGreen,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            HardwareStatusChip(
                                text = singleChip,
                                accent = chipAccent,
                            )
                        }

                        if (!quote.neverKyc && quote.kycWarning.isNotBlank()) {
                            Text(
                                text = quote.kycWarning,
                                color = MetaToolsTokens.PhosphorLoadingAmber,
                                fontSize = 10.5.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        HardwareBezelButton(
                            text = "Swap in Browser",
                            primary = true,
                            compact = true,
                            onClick = { onLaunchQuoteInBrowser(quote, btcAmount.trim()) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("swap_launch_${quote.id}"),
                        )
                    }
                }
            }

            if (xmr.activeTrackedSwaps.isNotEmpty()) {
                EngravedGrooveDivider()
                HardwareSectionHeader("Tracked Swaps (${xmr.activeTrackedSwaps.size})")
                xmr.activeTrackedSwaps.forEach { swap ->
                    val statusBadge = when (swap.status) {
                        "completed" -> "COMPLETED"
                        "awaiting_btc_deposit" -> "DEPOSIT BTC"
                        else -> "AWAITING FORM"
                    }
                    val statusAccent = when (swap.status) {
                        "completed" -> MetaToolsTokens.PhosphorOnionGreen
                        else -> MetaToolsTokens.PhosphorLoadingAmber
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MetaToolsTokens.AnodizedRecessedWell)
                            .border(1.dp, MetaToolsTokens.GrooveHighlight, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .testTag("tracked_swap_${swap.swapId}"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${swap.provider} · ${swap.btcAmount} BTC",
                                    color = Color(0xFFF0F4F8),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subLine = if (swap.status == "completed" && swap.receivedXmr != "0") {
                                    "Received +${swap.receivedXmr} XMR"
                                } else if (swap.btcDepositAddress.isNotBlank()) {
                                    "BTC In: ${truncateCryptoAddress(swap.btcDepositAddress)}"
                                } else {
                                    "Payout: ${truncateCryptoAddress(swap.subaddress)}"
                                }
                                Text(
                                    text = subLine,
                                    color = MetaToolsTokens.StencilInk,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            HardwareStatusChip(
                                text = statusBadge,
                                accent = statusAccent,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HardwareBezelButton(
                                text = "Open Order Page",
                                compact = true,
                                onClick = { onOpenTrackedSwapPage(swap) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("tracked_swap_open_${swap.swapId}"),
                            )
                            if (swap.btcDepositAddress.isNotBlank()) {
                                HardwareBezelButton(
                                    text = "Copy BTC Deposit",
                                    compact = true,
                                    onClick = { copyString(swap.btcDepositAddress) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("tracked_swap_copy_btc_${swap.swapId}"),
                                )
                            } else {
                                HardwareBezelButton(
                                    text = "Copy 8…",
                                    compact = true,
                                    onClick = { copyString(swap.subaddress) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("tracked_swap_copy_xmr_${swap.swapId}"),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HardwareBezelButton(
                                text = "Sync Payout",
                                compact = true,
                                onClick = onSyncWallet,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("tracked_swap_sync_${swap.swapId}"),
                            )
                            HardwareBezelButton(
                                text = "Dismiss",
                                danger = true,
                                compact = true,
                                onClick = { onDismissSwap(swap.swapId) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("tracked_swap_dismiss_${swap.swapId}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 10. Compact Bottom-Docked Active Swap Browser HUD (`active_swap_hud`)
// ============================================================================

@Composable
fun ActiveSwapBrowserHud(
    swap: OvNative.TrackedBtcXmrSwap,
    syncing: Boolean,
    onCopyText: (String) -> Unit,
    onSyncPayout: () -> Unit,
    onOpenWallet: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(14.dp)

    fun copyString(text: String) {
        if (text.isBlank()) return
        runCatching { clipboard.setText(AnnotatedString(text)) }
        onCopyText(text)
    }

    val statusChip = when (swap.status) {
        "completed" -> "PAID +${swap.receivedXmr} XMR"
        "awaiting_btc_deposit" -> "DEPOSIT BTC"
        else -> "FILL 8…"
    }
    val accent = if (swap.status == "completed") {
        MetaToolsTokens.PhosphorOnionGreen
    } else {
        MetaToolsTokens.PhosphorLoadingAmber
    }

    SensitiveContentEffect()

    Column(
        modifier = modifier
            .fillMaxWidth(0.92f)
            .metaToolsDropShadow(cornerRadius = 14.dp)
            .clip(shape)
            .background(MetaToolsTokens.AnodizedSurface)
            .border(1.2.dp, MetaToolsTokens.AnodizedBevelLight, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("active_swap_hud"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(modifier = Modifier.size(8.dp)) {
                drawRecessedGlassLedBead(
                    center = center,
                    beadDiameterPx = 6.dp.toPx(),
                    litColor = if (swap.status == "completed") {
                        MetaToolsTokens.LedGreen
                    } else {
                        MetaToolsTokens.LedAmber
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${swap.provider} · ${swap.btcDepositAmount.ifBlank { swap.btcAmount }} BTC",
                color = Color(0xFFF0F4F8),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            HardwareStatusChip(text = statusChip, accent = accent)
            Spacer(Modifier.width(6.dp))
            HardwareBezelButton(
                text = "✕",
                compact = true,
                onClick = onDismiss,
                modifier = Modifier.testTag("active_swap_hud_dismiss"),
            )
        }

        when (swap.status) {
            "awaiting_btc_deposit" -> {
                if (swap.btcDepositAddress.isNotBlank()) {
                    Text(
                        text = truncateCryptoAddress(swap.btcDepositAddress),
                        color = MetaToolsTokens.PhosphorLoadingAmber,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("active_swap_hud_btc_addr_text"),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HardwareBezelButton(
                        text = "Copy BTC Addr",
                        primary = true,
                        compact = true,
                        enabled = swap.btcDepositAddress.isNotBlank(),
                        onClick = { copyString(swap.btcDepositAddress) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("active_swap_copy_btc_addr"),
                    )
                    HardwareBezelButton(
                        text = "Copy BTC Amt",
                        compact = true,
                        onClick = { copyString(swap.btcDepositAmount.ifBlank { swap.btcAmount }) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("active_swap_copy_btc_amt"),
                    )
                }
                HardwareBezelButton(
                    text = if (syncing) "Syncing XMR…" else "Sync XMR Payout",
                    compact = true,
                    enabled = !syncing,
                    onClick = onSyncPayout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("active_swap_sync_button"),
                )
            }

            "completed" -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HardwareBezelButton(
                        text = "Open Wallet",
                        primary = true,
                        compact = true,
                        onClick = onOpenWallet,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("active_swap_open_wallet"),
                    )
                    HardwareBezelButton(
                        text = if (syncing) "Syncing…" else "Sync XMR",
                        compact = true,
                        enabled = !syncing,
                        onClick = onSyncPayout,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("active_swap_sync_button"),
                    )
                }
            }

            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HardwareBezelButton(
                        text = "Copy 8… Subaddress",
                        primary = true,
                        compact = true,
                        enabled = swap.subaddress.isNotBlank(),
                        onClick = { copyString(swap.subaddress) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("active_swap_copy_subaddress"),
                    )
                    HardwareBezelButton(
                        text = if (syncing) "Syncing…" else "Sync XMR",
                        compact = true,
                        enabled = !syncing,
                        onClick = onSyncPayout,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("active_swap_sync_button"),
                    )
                }
            }
        }
    }
}

internal fun truncateCryptoAddress(address: String): String {
    val clean = address.trim()
    if (clean.length <= 18) return clean
    return "${clean.take(10)}…${clean.takeLast(8)}"
}
