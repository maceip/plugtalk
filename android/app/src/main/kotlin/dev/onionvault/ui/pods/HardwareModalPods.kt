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
import dev.onionvault.ui.PgpPlaintextState
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
        contentAlignment = Alignment.BottomCenter,
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
                subtitle = "Block non-.onion traffic in Cefrium & SOCKS5",
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
                    text = "Trap bundles Cefrium 0.19.0 (LGPL-3.0-or-later / Chromium BSD-3-Clause), Sequoia OpenPGP (LGPL-2.0-or-later), and Arti (MIT OR Apache-2.0).",
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
