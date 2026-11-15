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

    /** Default clearnet consent rule TTL: 3 days (`259,200` seconds). */
    const val DEFAULT_CLEARNET_TTL_SECONDS: Long = 3L * 24L * 60L * 60L

    private val COMPOUND_CCTLD_SECOND_LEVELS = setOf(
        "ac", "co", "com", "edu", "go", "gov", "gob", "mil", "ne", "net", "nom", "or", "org", "sch",
    )

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

    // --- Exact 24 JNI entry points exported by `src/lib.rs` ---

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

    @JvmStatic
    external fun nativeAllowClearnet(pattern: String, ttlSeconds: Long): String

    @JvmStatic
    external fun nativeDenyClearnet(host: String): Boolean

    @JvmStatic
    external fun nativeListClearnetRulesJson(): String

    @JvmStatic
    external fun nativeRevokeClearnetRule(pattern: String): String

    @JvmStatic
    external fun nativeClearClearnetRules(): Boolean

    // --- Typed JSON Data Models & Parsers ---

    data class TorStatusSnapshot(
        val bootstrapped: Boolean = false,
        val bootstrapPercent: Int = 0,
        val bootstrapSummary: String = "",
        val socksAddr: String = "",
        val hiddenServiceOnly: Boolean = false,
        val isolationToken: String = "",
        val isolatedGroupCount: Int = 0,
        val pendingClearnetHost: String = "",
    )

    data class UrlPolicyResult(
        val allowed: Boolean,
        val normalizedUrl: String = "",
        val isOnion: Boolean = false,
        val requiresClearnetConsent: Boolean = false,
        val host: String = "",
        val suggestedPattern: String = "",
        val reason: String = "",
    )

    typealias UrlPolicyDecision = UrlPolicyResult

    data class ClearnetRuleItem(
        val pattern: String,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        val sessionOnly: Boolean,
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
                hiddenServiceOnly = obj.optBoolean("hiddenServiceOnly", false),
                isolationToken = obj.optString("isolationToken", ""),
                isolatedGroupCount = obj.optInt("isolatedGroupCount", 0),
                pendingClearnetHost = obj.optString("pendingClearnetHost", ""),
            )
        }.getOrElse { TorStatusSnapshot() }
    }

    fun parseUrlPolicy(json: String): UrlPolicyResult {
        if (json.isBlank()) {
            return UrlPolicyResult(
                allowed = false,
                normalizedUrl = "",
                isOnion = false,
                requiresClearnetConsent = false,
                host = "",
                suggestedPattern = "",
                reason = "Empty URL policy response",
            )
        }
        return runCatching {
            val obj = JSONObject(json)
            UrlPolicyResult(
                allowed = obj.optBoolean("allowed", false),
                normalizedUrl = obj.optString("normalizedUrl", ""),
                isOnion = obj.optBoolean("isOnion", false),
                requiresClearnetConsent = obj.optBoolean("requiresClearnetConsent", false),
                host = obj.optString("host", ""),
                suggestedPattern = obj.optString("suggestedPattern", ""),
                reason = obj.optString("reason", ""),
            )
        }.getOrElse {
            UrlPolicyResult(
                allowed = false,
                normalizedUrl = "",
                isOnion = false,
                requiresClearnetConsent = false,
                host = "",
                suggestedPattern = "",
                reason = "Invalid URL policy response",
            )
        }
    }

    fun parseClearnetRules(json: String): List<ClearnetRuleItem> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val pattern = obj.optString("pattern", "").trim()
                    if (pattern.isEmpty()) continue
                    add(
                        ClearnetRuleItem(
                            pattern = pattern,
                            createdAtMs = obj.optLong("createdAtMs", 0L),
                            expiresAtMs = obj.optLong("expiresAtMs", 0L),
                            sessionOnly = obj.optBoolean("sessionOnly", false),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    /**
     * Pure string-only helper (zero OS DNS calls) matching `ov_tor::suggested_glob_for_host`:
     * returns normalized host for IPv4/IPv6 literals or single-label hosts, and `*.<registrable>`
     * for clearnet domain names (`tor.taxi` -> `*.tor.taxi`, `www.tor.taxi` -> `*.tor.taxi`).
     */
    fun suggestGlob(host: String): String {
        val normalized = host.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .trimEnd('.')
            .lowercase()
        if (normalized.isEmpty()) return ""
        if (isIpv4Literal(normalized) || normalized.contains(':')) return normalized
        if (normalized == "onion" || normalized.endsWith(".onion")) return normalized

        val labels = normalized.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 1) return normalized

        val tld = labels.last()
        val second = labels[labels.size - 2]
        val isCompoundCcTld = tld.length == 2 &&
            tld.all { it in 'a'..'z' } &&
            second in COMPOUND_CCTLD_SECOND_LEVELS

        return if (isCompoundCcTld) {
            if (labels.size >= 3) {
                "*.${labels.takeLast(3).joinToString(".")}"
            } else {
                normalized
            }
        } else {
            "*.${labels.takeLast(2).joinToString(".")}"
        }
    }

    private fun isIpv4Literal(candidate: String): Boolean {
        val parts = candidate.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull() ?: return false
            n in 0..255 && part == n.toString()
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
