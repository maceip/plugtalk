package dev.onionvault

import androidx.compose.runtime.Immutable
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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

    private const val MONERO_BASE58_ALPHABET =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

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

    // --- Exact 35 JNI entry points exported by `src/lib.rs` ---

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

    external fun nativeXmrCreateWalletJson(label: String): String

    external fun nativeXmrDeleteWallet(): Boolean

    external fun nativeXmrExportSeedJson(): String

    external fun nativeXmrFetchBtcSwapQuotesJson(btcAmountDecimal: String): String

    external fun nativeXmrNewSubaddressJson(label: String): String

    external fun nativeXmrRestoreWalletJson(mnemonic: String, restoreHeight: Long): String

    external fun nativeXmrSendJson(
        destinationAddress: String,
        amountDecimalOrAtomic: String,
        sweepAll: Boolean,
    ): String

    external fun nativeXmrSetDaemonEndpointJson(endpointUrl: String, mode: String): String

    external fun nativeXmrStatusJson(): String

    external fun nativeXmrSwapsActionJson(actionJson: String): String

    external fun nativeXmrSyncJson(): String

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

    // --- Monero Light Wallet (`crates/ov-xmr`) & Browser-Wired BTC -> XMR Swap Models ---

    @Immutable
    data class XmrSubaddressItem(
        val index: Int,
        val label: String = "",
        val address: String,
        val receivedAtomic: String = "0",
        val receivedXmr: String = "0",
    )

    @Immutable
    data class XmrTxItem(
        val txid: String,
        val direction: String = "in",
        val amountAtomic: String = "0",
        val amountXmr: String = "0",
        val feeAtomic: String = "0",
        val feeXmr: String = "0",
        val height: Long = 0L,
        val timestamp: Long = 0L,
        val subaddressIndex: Int = 0,
        val unlocked: Boolean = false,
    )

    @Immutable
    data class TrackedBtcXmrSwap(
        val swapId: String,
        val provider: String,
        val btcAmount: String,
        val quotedXmr: String = "",
        val launchUrl: String = "",
        val subaddressIndex: Int = 0,
        val subaddress: String,
        val btcDepositAddress: String = "",
        val btcDepositAmount: String = "",
        val providerOrderId: String = "",
        val orderUrl: String = "",
        val status: String = "awaiting_form",
        val payoutTxid: String = "",
        val receivedAtomic: String = "0",
        val receivedXmr: String = "0",
        val createdEpochSecs: Long = 0L,
    )

    @Immutable
    data class XmrStatusSnapshot(
        val ok: Boolean = true,
        val hasWallet: Boolean = false,
        val walletLabel: String = "",
        val primaryAddress: String = "",
        val activeSubaddress: String = "",
        val activeSubaddressIndex: Int = 0,
        val balanceAtomic: String = "0",
        val unlockedBalanceAtomic: String = "0",
        val balanceXmr: String = "0",
        val unlockedBalanceXmr: String = "0",
        val restoreHeight: Long = 0L,
        val syncedHeight: Long = 0L,
        val daemonHeight: Long = 0L,
        val birthdayPending: Boolean = false,
        val syncEndpoint: String = "",
        val syncMode: String = "monerod_onion",
        val lastSyncEpochSecs: Long = 0L,
        val subaddresses: List<XmrSubaddressItem> = emptyList(),
        val recentTxs: List<XmrTxItem> = emptyList(),
        val swaps: List<TrackedBtcXmrSwap> = emptyList(),
        val error: String = "",
    )

    @Immutable
    data class XmrSeedResult(
        val ok: Boolean,
        val mnemonic: String = "",
        val primaryAddress: String = "",
        val activeSubaddress: String = "",
        val activeSubaddressIndex: Int = 0,
        val viewKeyHex: String = "",
        val restoreHeight: Long = 0L,
        val error: String = "",
    )

    @Immutable
    data class XmrNewSubaddressResult(
        val ok: Boolean,
        val index: Int = 0,
        val label: String = "",
        val address: String = "",
        val error: String = "",
    )

    @Immutable
    data class XmrSendResult(
        val ok: Boolean,
        val txid: String = "",
        val feeAtomic: String = "0",
        val feeXmr: String = "0",
        val amountAtomic: String = "0",
        val amountXmr: String = "0",
        val rawTxBytes: Int = 0,
        val error: String = "",
    )

    @Immutable
    data class BtcSwapQuoteItem(
        val id: String,
        val provider: String,
        val estimatedXmr: String,
        val rateXmrPerBtc: String = "",
        val spreadTag: String = "",
        val neverKyc: Boolean = false,
        val kycWarning: String = "",
        val isOnionUrl: Boolean = false,
        val launchUrl: String = "",
    )

    @Immutable
    data class BtcSwapQuotesResult(
        val ok: Boolean,
        val btcAmount: String = "",
        val sourceOnion: String = "",
        val fetchedAtEpochSecs: Long = 0L,
        val quotes: List<BtcSwapQuoteItem> = emptyList(),
        val error: String = "",
    )

    @Immutable
    data class XmrSwapsActionResult(
        val ok: Boolean,
        val swap: TrackedBtcXmrSwap? = null,
        val swaps: List<TrackedBtcXmrSwap> = emptyList(),
        val error: String = "",
    )

    @Immutable
    data class MoneroPaymentRequest(
        val address: String,
        val amountXmr: String = "",
        val txDescription: String = "",
        val recipientName: String = "",
    )

    fun parseXmrStatus(json: String): XmrStatusSnapshot {
        if (json.isBlank()) return XmrStatusSnapshot()
        return runCatching {
            val obj = JSONObject(json)
            val subArr = obj.optJSONArray("subaddresses")
            val subs = if (subArr != null) {
                buildList(subArr.length()) {
                    for (i in 0 until subArr.length()) {
                        val sub = subArr.optJSONObject(i) ?: continue
                        val addr = sub.optString("address", "").trim()
                        if (addr.isEmpty()) continue
                        add(
                            XmrSubaddressItem(
                                index = sub.optInt("index", 0),
                                label = sub.optString("label", ""),
                                address = addr,
                                receivedAtomic = sub.optString("receivedAtomic", "0"),
                                receivedXmr = sub.optString("receivedXmr", "0"),
                            ),
                        )
                    }
                }
            } else {
                emptyList()
            }

            val txArr = obj.optJSONArray("recentTxs")
            val txs = if (txArr != null) {
                buildList(txArr.length()) {
                    for (i in 0 until txArr.length()) {
                        val tx = txArr.optJSONObject(i) ?: continue
                        val txid = tx.optString("txid", "").trim()
                        if (txid.isEmpty()) continue
                        add(
                            XmrTxItem(
                                txid = txid,
                                direction = tx.optString("direction", "in"),
                                amountAtomic = tx.optString("amountAtomic", "0"),
                                amountXmr = tx.optString("amountXmr", "0"),
                                feeAtomic = tx.optString("feeAtomic", "0"),
                                feeXmr = tx.optString("feeXmr", "0"),
                                height = tx.optLong("height", 0L),
                                timestamp = tx.optLong("timestamp", 0L),
                                subaddressIndex = tx.optInt("subaddressIndex", 0),
                                unlocked = tx.optBoolean("unlocked", false),
                            ),
                        )
                    }
                }
            } else {
                emptyList()
            }

            val swaps = parseSwapArray(obj.optJSONArray("swaps"))
            XmrStatusSnapshot(
                ok = obj.optBoolean("ok", true),
                hasWallet = obj.optBoolean("hasWallet", false),
                walletLabel = obj.optString("walletLabel", ""),
                primaryAddress = obj.optString("primaryAddress", ""),
                activeSubaddress = obj.optString("activeSubaddress", ""),
                activeSubaddressIndex = obj.optInt("activeSubaddressIndex", 0),
                balanceAtomic = obj.optString("balanceAtomic", "0"),
                unlockedBalanceAtomic = obj.optString("unlockedBalanceAtomic", "0"),
                balanceXmr = obj.optString("balanceXmr", "0"),
                unlockedBalanceXmr = obj.optString("unlockedBalanceXmr", "0"),
                restoreHeight = obj.optLong("restoreHeight", 0L),
                syncedHeight = obj.optLong("syncedHeight", 0L),
                daemonHeight = obj.optLong("daemonHeight", 0L),
                birthdayPending = obj.optBoolean("birthdayPending", false),
                syncEndpoint = obj.optString("syncEndpoint", ""),
                syncMode = obj.optString("syncMode", "monerod_onion"),
                lastSyncEpochSecs = obj.optLong("lastSyncEpochSecs", 0L),
                subaddresses = subs,
                recentTxs = txs,
                swaps = swaps,
                error = obj.optString("error", ""),
            )
        }.getOrElse { err ->
            XmrStatusSnapshot(
                ok = false,
                error = err.message ?: "Malformed Monero status response",
            )
        }
    }

    fun parseXmrSeedResult(json: String): XmrSeedResult {
        if (json.isBlank()) return XmrSeedResult(ok = false, error = "Empty Monero seed response")
        return runCatching {
            val obj = JSONObject(json)
            XmrSeedResult(
                ok = obj.optBoolean("ok", false),
                mnemonic = obj.optString("mnemonic", "").trim(),
                primaryAddress = obj.optString("primaryAddress", "").trim(),
                activeSubaddress = obj.optString("activeSubaddress", "").trim(),
                activeSubaddressIndex = obj.optInt("activeSubaddressIndex", 0),
                viewKeyHex = obj.optString("viewKeyHex", "").trim(),
                restoreHeight = obj.optLong("restoreHeight", 0L),
                error = obj.optString("error", ""),
            )
        }.getOrElse { err ->
            XmrSeedResult(ok = false, error = err.message ?: "Malformed Monero seed response")
        }
    }

    fun parseXmrNewSubaddressResult(json: String): XmrNewSubaddressResult {
        if (json.isBlank()) {
            return XmrNewSubaddressResult(ok = false, error = "Empty subaddress response")
        }
        return runCatching {
            val obj = JSONObject(json)
            XmrNewSubaddressResult(
                ok = obj.optBoolean("ok", false),
                index = obj.optInt("index", 0),
                label = obj.optString("label", ""),
                address = obj.optString("address", "").trim(),
                error = obj.optString("error", ""),
            )
        }.getOrElse { err ->
            XmrNewSubaddressResult(
                ok = false,
                error = err.message ?: "Malformed subaddress response",
            )
        }
    }

    fun parseXmrSendResult(json: String): XmrSendResult {
        if (json.isBlank()) return XmrSendResult(ok = false, error = "Empty Monero send response")
        return runCatching {
            val obj = JSONObject(json)
            XmrSendResult(
                ok = obj.optBoolean("ok", false),
                txid = obj.optString("txid", "").trim(),
                feeAtomic = obj.optString("feeAtomic", "0"),
                feeXmr = obj.optString("feeXmr", "0"),
                amountAtomic = obj.optString("amountAtomic", "0"),
                amountXmr = obj.optString("amountXmr", "0"),
                rawTxBytes = obj.optInt("rawTxBytes", 0),
                error = obj.optString("error", ""),
            )
        }.getOrElse { err ->
            XmrSendResult(ok = false, error = err.message ?: "Malformed Monero send response")
        }
    }

    fun parseBtcSwapQuotes(json: String): BtcSwapQuotesResult {
        if (json.isBlank()) {
            return BtcSwapQuotesResult(ok = false, error = "Empty swap quotes response")
        }
        return runCatching {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("quotes")
            val quotes = if (arr != null) {
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val q = arr.optJSONObject(i) ?: continue
                        val provider = q.optString("provider", "").trim()
                        val launchUrl = q.optString("launchUrl", "").trim()
                        if (provider.isEmpty() || launchUrl.isEmpty()) continue
                        add(
                            BtcSwapQuoteItem(
                                id = q.optString("id", "").ifBlank { "$provider-$i" },
                                provider = provider,
                                estimatedXmr = q.optString("estimatedXmr", ""),
                                rateXmrPerBtc = q.optString("rateXmrPerBtc", ""),
                                spreadTag = q.optString("spreadTag", ""),
                                neverKyc = q.optBoolean("neverKyc", false),
                                kycWarning = q.optString("kycWarning", ""),
                                isOnionUrl = q.optBoolean("isOnionUrl", false),
                                launchUrl = launchUrl,
                            ),
                        )
                    }
                }
            } else {
                emptyList()
            }
            BtcSwapQuotesResult(
                ok = obj.optBoolean("ok", false),
                btcAmount = obj.optString("btcAmount", ""),
                sourceOnion = obj.optString("sourceOnion", ""),
                fetchedAtEpochSecs = obj.optLong("fetchedAtEpochSecs", 0L),
                quotes = quotes,
                error = obj.optString("error", ""),
            )
        }.getOrElse { err ->
            BtcSwapQuotesResult(
                ok = false,
                error = err.message ?: "Malformed swap quotes response",
            )
        }
    }

    fun parseXmrSwapsAction(json: String): XmrSwapsActionResult {
        if (json.isBlank()) {
            return XmrSwapsActionResult(ok = false, error = "Empty swap action response")
        }
        return runCatching {
            val obj = JSONObject(json)
            val singleSwapObj = obj.optJSONObject("swap")
            val singleSwap = singleSwapObj?.let { parseTrackedSwapObject(it) }
            val swaps = parseSwapArray(obj.optJSONArray("swaps"))
            XmrSwapsActionResult(
                ok = obj.optBoolean("ok", false),
                swap = singleSwap,
                swaps = swaps,
                error = obj.optString("error", ""),
            )
        }.getOrElse { err ->
            XmrSwapsActionResult(
                ok = false,
                error = err.message ?: "Malformed swap action response",
            )
        }
    }

    private fun parseSwapArray(arr: JSONArray?): List<TrackedBtcXmrSwap> {
        if (arr == null) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                parseTrackedSwapObject(obj)?.let { add(it) }
            }
        }
    }

    private fun parseTrackedSwapObject(obj: JSONObject): TrackedBtcXmrSwap? {
        val swapId = obj.optString("swapId", "").trim()
        val subaddress = obj.optString("subaddress", "").trim()
        if (swapId.isEmpty() || subaddress.isEmpty()) return null
        return TrackedBtcXmrSwap(
            swapId = swapId,
            provider = obj.optString("provider", ""),
            btcAmount = obj.optString("btcAmount", ""),
            quotedXmr = obj.optString("quotedXmr", ""),
            launchUrl = obj.optString("launchUrl", ""),
            subaddressIndex = obj.optInt("subaddressIndex", 0),
            subaddress = subaddress,
            btcDepositAddress = obj.optString("btcDepositAddress", ""),
            btcDepositAmount = obj.optString("btcDepositAmount", ""),
            providerOrderId = obj.optString("providerOrderId", ""),
            orderUrl = obj.optString("orderUrl", ""),
            status = obj.optString("status", "awaiting_form"),
            payoutTxid = obj.optString("payoutTxid", ""),
            receivedAtomic = obj.optString("receivedAtomic", "0"),
            receivedXmr = obj.optString("receivedXmr", "0"),
            createdEpochSecs = obj.optLong("createdEpochSecs", 0L),
        )
    }

    fun buildSwapsListActionJson(): String =
        JSONObject().put("op", "list").toString()

    fun buildSwapCreateActionJson(
        provider: String,
        btcAmount: String,
        quotedXmr: String,
        launchUrl: String,
    ): String = JSONObject()
        .put("op", "create")
        .put("provider", provider.trim())
        .put("btcAmount", btcAmount.trim())
        .put("quotedXmr", quotedXmr.trim())
        .put("launchUrl", launchUrl.trim())
        .toString()

    fun buildSwapUpdateOrderActionJson(
        swapId: String,
        btcDepositAddress: String,
        btcDepositAmount: String = "",
        providerOrderId: String = "",
        orderUrl: String = "",
        status: String = "awaiting_btc_deposit",
    ): String = JSONObject()
        .put("op", "update_order")
        .put("swapId", swapId.trim())
        .put("btcDepositAddress", btcDepositAddress.trim())
        .put("btcDepositAmount", btcDepositAmount.trim())
        .put("providerOrderId", providerOrderId.trim())
        .put("orderUrl", orderUrl.trim())
        .put("status", status.trim())
        .toString()

    fun buildSwapDismissActionJson(swapId: String): String =
        JSONObject()
            .put("op", "dismiss")
            .put("swapId", swapId.trim())
            .toString()

    fun isValidMoneroAddress(candidate: String): Boolean {
        val trimmed = candidate.trim()
        if (trimmed.length != 95 && trimmed.length != 106) return false
        val first = trimmed[0]
        if (first != '4' && first != '8') return false
        if (trimmed.length == 106 && first != '4') return false
        return trimmed.all { ch -> MONERO_BASE58_ALPHABET.indexOf(ch) >= 0 }
    }

    /**
     * Pure-Kotlin parser for `monero:<address>?tx_amount=...&tx_description=...&recipient_name=...`
     * payment URIs (zero OS DNS / network calls).
     */
    fun parseMoneroUri(uri: String): MoneroPaymentRequest? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("monero:", ignoreCase = true)) return null
        val afterScheme = trimmed.substring("monero:".length).removePrefix("//")
        val withoutFragment = afterScheme.substringBefore('#')
        val qIdx = withoutFragment.indexOf('?')
        val address = (if (qIdx >= 0) withoutFragment.substring(0, qIdx) else withoutFragment).trim()
        if (!isValidMoneroAddress(address)) return null

        var amountXmr = ""
        var txDescription = ""
        var recipientName = ""

        if (qIdx >= 0 && qIdx + 1 < withoutFragment.length) {
            val rawQuery = withoutFragment.substring(qIdx + 1)
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
                val rawVal = if (eq >= 0) pair.substring(eq + 1) else ""
                val key = decodeUriComponentSafe(rawKey).trim().lowercase()
                val value = decodeUriComponentSafe(rawVal).trim()
                when (key) {
                    "tx_amount", "amount" -> {
                        if (value.isNotEmpty()) {
                            if (!isValidNonNegativeDecimal(value)) return null
                            amountXmr = value
                        }
                    }

                    "tx_description", "message" -> txDescription = value
                    "recipient_name", "label" -> recipientName = value
                }
            }
        }

        return MoneroPaymentRequest(
            address = address,
            amountXmr = amountXmr,
            txDescription = txDescription,
            recipientName = recipientName,
        )
    }

    private fun decodeUriComponentSafe(raw: String): String =
        runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)

    private fun isValidNonNegativeDecimal(raw: String): Boolean {
        if (raw.isEmpty()) return false
        var seenDot = false
        var digitCount = 0
        var fractionalCount = 0
        for (ch in raw) {
            when {
                ch in '0'..'9' -> {
                    digitCount++
                    if (seenDot) {
                        fractionalCount++
                        if (fractionalCount > 12) return false
                    }
                }

                ch == '.' -> {
                    if (seenDot) return false
                    seenDot = true
                }

                else -> return false
            }
        }
        return digitCount > 0
    }
}
