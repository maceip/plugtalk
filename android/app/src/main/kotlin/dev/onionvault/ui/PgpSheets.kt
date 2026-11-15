package dev.onionvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
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

/**
 * Native modal sheet displaying decrypted PGP plaintext outside Blink's DOM
 * (preventing CSS/form exfiltration side channels).
 *
 * Shows real signature verification state (`signatureStatus`, `signerUserId`,
 * `signerFingerprint`), `"Copy"`, `"Insert into page"` (only when target node
 * is editable), and an explicit `"Close"` button.
 */
@Composable
fun PlaintextSheet(
    plaintext: PgpPlaintextState,
    onCopy: (String) -> Unit,
    onInsert: (PgpPlaintextState) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TrapTheme.colors

    OvBottomSheet(
        title = plaintext.title.ifBlank { "Decrypted Message" },
        onDismiss = onClose,
        modifier = modifier.testTag("plaintext_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SignatureSummaryCard(plaintext = plaintext)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface2)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = plaintext.body,
                    color = colors.textPrimary,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OvSecondaryButton(
                    text = "Copy",
                    glyph = TrapGlyph.Copy,
                    onClick = { onCopy(plaintext.body) },
                    modifier = Modifier.testTag("plaintext_copy"),
                )
                if (plaintext.targetEditable && plaintext.nodeId.isNotEmpty()) {
                    OvSecondaryButton(
                        text = "Insert into page",
                        glyph = TrapGlyph.Insert,
                        accent = colors.amber,
                        onClick = { onInsert(plaintext) },
                        modifier = Modifier.testTag("plaintext_insert"),
                    )
                }
                OvPrimaryButton(
                    text = "Close",
                    onClick = onClose,
                    modifier = Modifier.testTag("plaintext_close"),
                )
            }
        }
    }
}

@Composable
private fun SignatureSummaryCard(plaintext: PgpPlaintextState) {
    val colors = TrapTheme.colors
    val status = plaintext.signatureStatus.lowercase()
    val fpSuffix = plaintext.signerFingerprint
        ?.replace(" ", "")
        ?.takeIf { it.isNotEmpty() }
        ?.takeLast(16)
    val signerName = plaintext.signerUserId?.takeIf { it.isNotBlank() }

    val (headline, accent, container) = when (status) {
        "good" -> Triple(
            if (signerName != null) "Good signature · $signerName" else "Good cryptographic signature",
            colors.green,
            colors.greenContainer,
        )

        "bad" -> Triple(
            "Invalid signature — message modified or forged",
            colors.danger,
            colors.dangerContainer,
        )

        "unknown_key" -> Triple(
            "Signed by unknown key (import sender public key to verify)",
            colors.amber,
            colors.control,
        )

        else -> Triple(
            "Unsigned PGP message",
            colors.textMuted,
            colors.control,
        )
    }

    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .border(1.dp, accent.copy(alpha = 0.6f), shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = headline,
            color = accent,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (fpSuffix != null) {
            Text(
                text = "Key ID · $fpSuffix",
                color = colors.textMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

/**
 * Passphrase prompt shown when decrypting an armored PGP block locked by an
 * encrypted secret key (`needsPassphrase == true`).
 */
@Composable
fun DecryptPassphraseSheet(
    request: DecryptPassphraseRequestState,
    onSubmitPassphrase: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TrapTheme.colors
    var passphrase by remember(request.armoredCiphertext) { mutableStateOf("") }

    OvBottomSheet(
        title = "Unlock Secret Key",
        onDismiss = onDismiss,
        modifier = Modifier.testTag("decrypt_passphrase_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Enter the passphrase for your secret key to decrypt this PGP block.",
                color = colors.textMuted,
                fontSize = 13.sp,
            )

            if (!request.error.isNullOrBlank()) {
                Text(
                    text = request.error,
                    color = colors.danger,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            TrapInputField(
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
                OvSecondaryButton(text = "Cancel", onClick = onDismiss)
                OvPrimaryButton(
                    text = "Decrypt",
                    glyph = TrapGlyph.Decrypt,
                    enabled = passphrase.isNotEmpty(),
                    onClick = { onSubmitPassphrase(passphrase) },
                    modifier = Modifier.testTag("decrypt_passphrase_submit"),
                )
            }
        }
    }
}

/**
 * Recipient & Signer Picker Sheet for encrypting text into a live DOM field
 * (or encrypting text to clipboard).
 */
@Composable
fun EncryptPickerSheet(
    request: EncryptRequestState,
    keys: List<KeyInfo>,
    defaultKeyFingerprint: String,
    errorMessage: String?,
    onOpenKeyring: () -> Unit,
    onConfirmEncrypt: (
        plaintext: String,
        recipientFingerprint: String,
        signerFingerprintOrEmpty: String,
        passphrase: String,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TrapTheme.colors
    val encryptCapable = remember(keys) { keys.filter { it.canEncrypt } }
    val signCapable = remember(keys) { keys.filter { it.hasSecret && it.canSign } }

    val initialRecipient = remember(encryptCapable, defaultKeyFingerprint) {
        encryptCapable.firstOrNull { !it.isOwnKey }?.fingerprint
            ?: encryptCapable.firstOrNull { it.fingerprint == defaultKeyFingerprint }?.fingerprint
            ?: encryptCapable.firstOrNull()?.fingerprint
            ?: ""
    }
    val initialSigner = remember(signCapable, defaultKeyFingerprint) {
        signCapable.firstOrNull { it.fingerprint == defaultKeyFingerprint }?.fingerprint
            ?: ""
    }

    var messageText by remember(request.nodeId, request.initialText) {
        mutableStateOf(request.initialText)
    }
    var selectedRecipient by remember(initialRecipient) { mutableStateOf(initialRecipient) }
    var selectedSigner by remember(initialSigner) { mutableStateOf(initialSigner) }
    var passphrase by remember { mutableStateOf("") }

    OvBottomSheet(
        title = if (request.writeBackToField) "Encrypt Form Field" else "Encrypt PGP Message",
        onDismiss = onDismiss,
        modifier = Modifier.testTag("encrypt_picker_sheet"),
    ) {
        if (encryptCapable.isEmpty()) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.surface2)
                    .border(1.dp, colors.border, shape)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "No PGP keys in keyring yet. Import the hidden service's public key or generate a keypair first.",
                    color = colors.textMuted,
                    fontSize = 13.sp,
                )
                OvPrimaryButton(
                    text = "Open PGP Keyring",
                    glyph = TrapGlyph.Key,
                    onClick = {
                        onDismiss()
                        onOpenKeyring()
                    },
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Message Plaintext",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                TrapInputField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = "Type message to encrypt…",
                    singleLine = false,
                    minHeight = 82.dp,
                    monospace = true,
                    testTag = "encrypt_plaintext_input",
                )

                Text(
                    text = "Recipient Public Key",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    encryptCapable.forEach { key ->
                        KeyChoiceRow(
                            title = key.userId.ifBlank { "PGP Key" },
                            subtitle = formatShortFingerprint(key.fingerprint),
                            badge = if (key.isOwnKey) "Own" else "Recipient",
                            selected = selectedRecipient == key.fingerprint,
                            onClick = { selectedRecipient = key.fingerprint },
                        )
                    }
                }

                if (signCapable.isNotEmpty()) {
                    Text(
                        text = "Sign Message (Optional)",
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        KeyChoiceRow(
                            title = "Unsigned",
                            subtitle = "Do not attach a cryptographic signature",
                            badge = null,
                            selected = selectedSigner.isEmpty(),
                            onClick = { selectedSigner = "" },
                        )
                        signCapable.forEach { key ->
                            KeyChoiceRow(
                                title = key.userId.ifBlank { "Own Signing Key" },
                                subtitle = formatShortFingerprint(key.fingerprint),
                                badge = "Secret",
                                selected = selectedSigner == key.fingerprint,
                                onClick = { selectedSigner = key.fingerprint },
                            )
                        }
                    }

                    if (selectedSigner.isNotEmpty()) {
                        TrapInputField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            placeholder = "Signing key passphrase (leave blank if none)",
                            isPassword = true,
                            singleLine = true,
                            testTag = "encrypt_passphrase_input",
                        )
                    }
                }

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        color = colors.danger,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OvSecondaryButton(text = "Cancel", onClick = onDismiss)
                OvPrimaryButton(
                    text = if (request.writeBackToField) "Encrypt & Fill Field" else "Encrypt & Copy",
                    glyph = TrapGlyph.Encrypt,
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
}

@Composable
private fun KeyChoiceRow(
    title: String,
    subtitle: String,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = TrapTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.control else colors.surface2)
            .border(
                width = 1.dp,
                color = if (selected) colors.teal else colors.cardBorder,
                shape = shape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = colors.textMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            OvBadge(
                text = badge,
                accent = if (selected) colors.teal else colors.textMuted,
                container = colors.surface,
            )
        }
    }
}

private enum class KeyringTab {
    Keys,
    Generate,
    Import,
}

/**
 * Full hardware-backed PGP Keyring Sheet:
 * - Lists keys at `<= 2:1` label density with Export/Copy PubKey, Set Default, and Delete.
 * - Generates new OpenPGP keys with a custom User ID + optional passphrase.
 * - Imports armored public or secret keys + optional passphrase.
 */
@Composable
fun KeyringSheet(
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
    val colors = TrapTheme.colors
    val clipboard = LocalClipboardManager.current
    var activeTab by remember { mutableStateOf(KeyringTab.Keys) }
    var genUserId by remember { mutableStateOf("") }
    var genPassphrase by remember { mutableStateOf("") }
    var importArmored by remember { mutableStateOf("") }
    var importPassphrase by remember { mutableStateOf("") }
    var pendingDeleteKey by remember { mutableStateOf<KeyInfo?>(null) }

    OvBottomSheet(
        title = "PGP Keyring",
        onDismiss = onDismiss,
        modifier = modifier.testTag("keyring_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OvSegmentedRow(
                options = listOf(
                    KeyringTab.Keys to "Keys (${keys.size})",
                    KeyringTab.Generate to "Generate",
                    KeyringTab.Import to "Import",
                ),
                selected = activeTab,
                onSelect = { activeTab = it },
            )

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    color = colors.danger,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            when (activeTab) {
                KeyringTab.Keys -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (keys.isEmpty()) {
                            val shape = RoundedCornerShape(12.dp)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .background(colors.surface2)
                                    .border(1.dp, colors.cardBorder, shape)
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "Keyring is empty. Generate your own keypair or import a hidden service's public key.",
                                    color = colors.textMuted,
                                    fontSize = 13.sp,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OvPrimaryButton(
                                        text = "Generate Key",
                                        glyph = TrapGlyph.NewKey,
                                        onClick = { activeTab = KeyringTab.Generate },
                                        modifier = Modifier.weight(1f),
                                    )
                                    OvSecondaryButton(
                                        text = "Import Key",
                                        glyph = TrapGlyph.Import,
                                        onClick = { activeTab = KeyringTab.Import },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        } else {
                            keys.forEach { key ->
                                val isDefault = key.fingerprint == defaultKeyFingerprint ||
                                    (defaultKeyFingerprint.isEmpty() && key == keys.firstOrNull { it.hasSecret })
                                KeyringItemCard(
                                    key = key,
                                    isDefault = isDefault,
                                    onCopyPubKey = { onExportKey(key.fingerprint) },
                                    onSetDefault = { onSetDefaultKey(key.fingerprint) },
                                    onRequestDelete = { pendingDeleteKey = key },
                                )
                            }
                        }
                    }
                }

                KeyringTab.Generate -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "User ID (handle or pseudonym)",
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TrapInputField(
                            value = genUserId,
                            onValueChange = { genUserId = it },
                            placeholder = "e.g. Whistleblower <drop@onion>",
                            singleLine = true,
                            testTag = "keygen_userid_input",
                        )

                        Text(
                            text = "Passphrase (optional additional protection)",
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TrapInputField(
                            value = genPassphrase,
                            onValueChange = { genPassphrase = it },
                            placeholder = "Optional passphrase",
                            isPassword = true,
                            singleLine = true,
                            testTag = "keygen_passphrase_input",
                        )

                        if (genUserId.isBlank()) {
                            Text(
                                text = "Enter a User ID above to enable key generation.",
                                color = colors.textMuted,
                                fontSize = 12.sp,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            OvPrimaryButton(
                                text = "Generate Keypair",
                                glyph = TrapGlyph.NewKey,
                                enabled = genUserId.isNotBlank(),
                                onClick = {
                                    val uid = genUserId.trim()
                                    val pass = genPassphrase
                                    onGenerateKey(uid, pass)
                                    genUserId = ""
                                    genPassphrase = ""
                                    activeTab = KeyringTab.Keys
                                },
                                modifier = Modifier.testTag("keygen_submit_button"),
                            )
                        }
                    }
                }

                KeyringTab.Import -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "ASCII-Armored Public or Secret Key",
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    clipboard.getText()?.text?.let { pasted ->
                                        importArmored = pasted
                                    }
                                },
                            ) {
                                Text("Paste", color = colors.teal, fontSize = 12.5.sp)
                            }
                        }

                        TrapInputField(
                            value = importArmored,
                            onValueChange = { importArmored = it },
                            placeholder = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
                            singleLine = false,
                            minHeight = 110.dp,
                            monospace = true,
                            testTag = "keyimport_armored_input",
                        )

                        TrapInputField(
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
                            OvPrimaryButton(
                                text = "Import Key",
                                glyph = TrapGlyph.Import,
                                enabled = importArmored.isNotBlank(),
                                onClick = {
                                    val armored = importArmored.trim()
                                    val pass = importPassphrase
                                    onImportKey(armored, pass)
                                    importArmored = ""
                                    importPassphrase = ""
                                    activeTab = KeyringTab.Keys
                                },
                                modifier = Modifier.testTag("keyimport_submit_button"),
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDeleteKey?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteKey = null },
            containerColor = colors.surface,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textMuted,
            title = {
                Text("Delete PGP Key?", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Remove ${target.userId.ifBlank { formatShortFingerprint(target.fingerprint) }} " +
                        "from the device keyring? This action cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val fp = target.fingerprint
                        pendingDeleteKey = null
                        onDeleteKey(fp)
                    },
                ) {
                    Text("Delete", color = colors.danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteKey = null }) {
                    Text("Cancel", color = colors.textMuted)
                }
            },
        )
    }
}

/**
 * Strictly obeys <= 2:1 label density:
 * Primary `userId` + 1 secondary fingerprint line + 1 status badge (`2:1` max).
 */
@Composable
private fun KeyringItemCard(
    key: KeyInfo,
    isDefault: Boolean,
    onCopyPubKey: () -> Unit,
    onSetDefault: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val colors = TrapTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val badgeLabel = when {
        isDefault && key.hasSecret -> "Default · Secret"
        isDefault -> "Default · Public"
        key.hasSecret -> "Secret"
        else -> "Public"
    }
    val badgeAccent = if (key.hasSecret) colors.green else colors.amber

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface2)
            .border(
                width = 1.dp,
                color = if (isDefault) colors.teal.copy(alpha = 0.7f) else colors.cardBorder,
                shape = shape,
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
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatGroupedFingerprint(key.fingerprint),
                    color = colors.textMuted,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            OvBadge(
                text = badgeLabel,
                accent = badgeAccent,
                container = colors.control,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OvSecondaryButton(
                text = "Copy PubKey",
                glyph = TrapGlyph.Copy,
                onClick = onCopyPubKey,
            )
            if (key.hasSecret && !isDefault) {
                OvSecondaryButton(
                    text = "Set Default",
                    accent = colors.amber,
                    onClick = onSetDefault,
                )
            }
            OvIconButton(
                glyph = TrapGlyph.Delete,
                contentDescription = "Delete key ${key.userId}",
                tint = colors.danger,
                onClick = onRequestDelete,
                size = 36.dp,
                forceVector = true,
            )
        }
    }
}

@Composable
internal fun TrapInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    isPassword: Boolean = false,
    monospace: Boolean = false,
    minHeight: Dp = 44.dp,
    testTag: String? = null,
) {
    val colors = TrapTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(shape)
            .background(colors.surface2)
            .border(1.dp, colors.border, shape)
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
                keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
            ),
            textStyle = TextStyle(
                color = colors.textPrimary,
                fontSize = 13.5.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
            cursorBrush = SolidColor(colors.teal),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = colors.textMuted,
                            fontSize = 13.sp,
                            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

internal fun formatShortFingerprint(fp: String): String {
    val clean = fp.replace(" ", "").uppercase()
    return if (clean.length <= 16) clean else "…${clean.takeLast(16)}"
}

internal fun formatGroupedFingerprint(fp: String): String {
    val clean = fp.replace(" ", "").uppercase()
    return clean.takeLast(20).chunked(4).joinToString(" ")
}
