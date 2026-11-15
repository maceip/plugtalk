package dev.onionvault

import org.json.JSONArray
import org.json.JSONObject

/**
 * JNI bridge to Trap's Rust library (`libonion_vault.so`, crate `ov-core`).
 *
 * Every `external fun` declared on [OvNative] maps 1:1 to an exported
 * `Java_dev_onionvault_OvNative_*` C-ABI symbol in `src/lib.rs`, verified on
 * every test run by `OvNativeTest`.
 */
object OvNative {

    const val LIBRARY_NAME: String = "onion_vault"

    @Volatile
    var isLoaded: Boolean = false
        private set

    @Synchronized
    fun load() {
        if (!isLoaded) {
            System.loadLibrary(LIBRARY_NAME)
            isLoaded = true
        }
    }

    // --- Exact 19 JNI entry points exported by `src/lib.rs` ---

    external fun nativeInit(
        dataDir: String,
        cacheDir: String,
        hiddenServiceOnly: Boolean,
    ): Boolean

    external fun nativeLastInitError(): String

    external fun nativeSocksProxy(): String

    external fun nativeTorStatusJson(currentUrl: String): String

    external fun nativeSetHiddenServiceOnly(enabled: Boolean)

    external fun nativeResetTorIsolation(targetUrlOrEmpty: String): Int

    external fun nativeCheckUrlPolicy(url: String, hiddenServiceOnly: Boolean): String

    external fun nativeClassifyCdpDom(cdpDomJson: String): String

    external fun nativeClassifyText(text: String): String

    external fun nativeListKeysJson(): String

    external fun nativeGenerateKey(userId: String, passphrase: String): String

    external fun nativeImportKey(armored: String, passphrase: String): String

    external fun nativeExportPublicKey(fingerprintOrEmpty: String): String

    external fun nativeDeleteKey(fingerprint: String): Boolean

    external fun nativeEncrypt(
        plaintext: String,
        recipientFingerprint: String,
        signerFingerprintOrEmpty: String,
        passphrase: String,
    ): String

    external fun nativeDecrypt(armoredCiphertext: String, passphrase: String): String

    external fun nativeSetRequireBiometric(required: Boolean): Boolean

    external fun nativeGetRequireBiometric(): Boolean

    external fun nativeShutdown()

    // --- Typed JSON Data Models & Parsers ---

    data class TorStatusSnapshot(
        val bootstrapped: Boolean = false,
        val bootstrapPercent: Int = 0,
        val bootstrapSummary: String = "",
        val socksAddr: String = "",
        val hiddenServiceOnly: Boolean = true,
        val isolationToken: String = "",
        val isolatedGroupCount: Int = 0,
    )

    data class UrlPolicyDecision(
        val allowed: Boolean,
        val reason: String,
        val isOnion: Boolean,
        val normalizedUrl: String,
    )

    data class TextClassification(
        val kind: String = "plain",
        val actionLabel: String = "",
        val armored: String = "",
        val fingerprintHint: String = "",
    ) {
        val isPgp: Boolean
            get() = kind == "pgp_message" || kind == "pgp_public_key"
    }

    data class ClassifiedDomNode(
        val nodeId: Int,
        val backendNodeId: Int,
        val tag: String,
        val kind: String,
        val actionLabel: String,
        val text: String,
        val fingerprintHint: String,
    )

    data class KeyringEntry(
        val fingerprint: String,
        val userId: String,
        val createdEpochSecs: Long,
        val hasSecret: Boolean,
        val canSign: Boolean,
        val canEncrypt: Boolean,
        val isOwnKey: Boolean,
    )

    data class KeyGenResult(
        val ok: Boolean,
        val fingerprint: String = "",
        val publicKeyArmored: String = "",
        val error: String = "",
    )

    data class KeyImportResult(
        val ok: Boolean,
        val fingerprint: String = "",
        val userId: String = "",
        val hasSecret: Boolean = false,
        val error: String = "",
    )

    data class EncryptResult(
        val ok: Boolean,
        val ciphertext: String = "",
        val signed: Boolean = false,
        val error: String = "",
    )

    data class DecryptResult(
        val ok: Boolean,
        val plaintext: String = "",
        val signatureStatus: String = "",
        val signerFingerprint: String = "",
        val signerUserId: String = "",
        val needsPassphrase: Boolean = false,
        val error: String = "",
    )

    fun parseTorStatus(json: String): TorStatusSnapshot {
        if (json.isBlank()) return TorStatusSnapshot()
        return runCatching {
            val obj = JSONObject(json)
            TorStatusSnapshot(
                bootstrapped = obj.optBoolean("bootstrapped", false),
                bootstrapPercent = obj.optInt("bootstrapPercent", 0),
                bootstrapSummary = obj.optString("bootstrapSummary", ""),
                socksAddr = obj.optString("socksAddr", ""),
                hiddenServiceOnly = obj.optBoolean("hiddenServiceOnly", true),
                isolationToken = obj.optString("isolationToken", ""),
                isolatedGroupCount = obj.optInt("isolatedGroupCount", 0),
            )
        }.getOrElse { TorStatusSnapshot() }
    }

    fun parseUrlPolicy(json: String): UrlPolicyDecision {
        if (json.isBlank()) {
            return UrlPolicyDecision(
                allowed = false,
                reason = "Empty URL policy response",
                isOnion = false,
                normalizedUrl = "",
            )
        }
        return runCatching {
            val obj = JSONObject(json)
            UrlPolicyDecision(
                allowed = obj.optBoolean("allowed", false),
                reason = obj.optString("reason", ""),
                isOnion = obj.optBoolean("isOnion", false),
                normalizedUrl = obj.optString("normalizedUrl", ""),
            )
        }.getOrElse {
            UrlPolicyDecision(
                allowed = false,
                reason = "Invalid URL policy response",
                isOnion = false,
                normalizedUrl = "",
            )
        }
    }

    fun parseTextClassification(json: String): TextClassification {
        if (json.isBlank()) return TextClassification()
        return runCatching {
            val obj = JSONObject(json)
            TextClassification(
                kind = obj.optString("kind", "plain"),
                actionLabel = obj.optString("actionLabel", ""),
                armored = obj.optString("armored", ""),
                fingerprintHint = obj.optString("fingerprintHint", ""),
            )
        }.getOrElse { TextClassification() }
    }

    fun parseClassifiedDom(json: String): List<ClassifiedDomNode> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val nodeId = obj.optInt("nodeId", 0)
                    val backendNodeId = obj.optInt("backendNodeId", 0)
                    if (nodeId <= 0 && backendNodeId <= 0) continue
                    add(
                        ClassifiedDomNode(
                            nodeId = nodeId,
                            backendNodeId = backendNodeId,
                            tag = obj.optString("tag", ""),
                            kind = obj.optString("kind", ""),
                            actionLabel = obj.optString("actionLabel", ""),
                            text = obj.optString("text", ""),
                            fingerprintHint = obj.optString("fingerprintHint", ""),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    fun parseKeys(json: String): List<KeyringEntry> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val fp = obj.optString("fingerprint", "")
                    if (fp.isBlank()) continue
                    add(
                        KeyringEntry(
                            fingerprint = fp,
                            userId = obj.optString("userId", ""),
                            createdEpochSecs = obj.optLong("createdEpochSecs", 0L),
                            hasSecret = obj.optBoolean("hasSecret", false),
                            canSign = obj.optBoolean("canSign", false),
                            canEncrypt = obj.optBoolean("canEncrypt", false),
                            isOwnKey = obj.optBoolean("isOwnKey", false),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    fun parseGenerateKey(json: String): KeyGenResult {
        if (json.isBlank()) return KeyGenResult(ok = false, error = "Empty response from keyring")
        return runCatching {
            val obj = JSONObject(json)
            KeyGenResult(
                ok = obj.optBoolean("ok", false),
                fingerprint = obj.optString("fingerprint", ""),
                publicKeyArmored = obj.optString("publicKeyArmored", ""),
                error = obj.optString("error", ""),
            )
        }.getOrElse { err ->
            KeyGenResult(ok = false, error = err.message ?: "Malformed key generation response")
        }
    }

    fun parseImportKey(json: String): KeyImportResult {
        if (json.isBlank()) return KeyImportResult(ok = false, error = "Empty response from keyring")
        return runCatching {
            val obj = JSONObject(json)
            KeyImportResult(
                ok = obj.optBoolean("ok", false),
                fingerprint = obj.optString("fingerprint", ""),
                userId = obj.optString("userId", ""),
                hasSecret = obj.optBoolean("hasSecret", false),
                error = obj.optString("error", ""),
            )
        }.getOrElse { err ->
            KeyImportResult(ok = false, error = err.message ?: "Malformed key import response")
        }
    }

    fun parseEncrypt(json: String): EncryptResult {
        if (json.isBlank()) return EncryptResult(ok = false, error = "Empty encryption response")
        return runCatching {
            val obj = JSONObject(json)
            EncryptResult(
                ok = obj.optBoolean("ok", false),
                ciphertext = obj.optString("ciphertext", ""),
                signed = obj.optBoolean("signed", false),
                error = obj.optString("error", ""),
            )
        }.getOrElse { err ->
            EncryptResult(ok = false, error = err.message ?: "Malformed encryption response")
        }
    }

    fun parseDecrypt(json: String): DecryptResult {
        if (json.isBlank()) return DecryptResult(ok = false, error = "Empty decryption response")
        return runCatching {
            val obj = JSONObject(json)
            DecryptResult(
                ok = obj.optBoolean("ok", false),
                plaintext = obj.optString("plaintext", ""),
                signatureStatus = obj.optString("signatureStatus", ""),
                signerFingerprint = obj.optString("signerFingerprint", ""),
                signerUserId = obj.optString("signerUserId", ""),
                needsPassphrase = obj.optBoolean("needsPassphrase", false),
                error = obj.optString("error", ""),
            )
        }.getOrElse { err ->
            DecryptResult(ok = false, error = err.message ?: "Malformed decryption response")
        }
    }
}
