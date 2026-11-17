package dev.onionvault.ui

import androidx.compose.runtime.Immutable
import java.net.URI

/**
 * Semantic category of a verified public-interest Tor v3 hidden service in [TrustedOnionDirectory].
 */
enum class OnionCategory(val displayName: String) {
    Search("Search"),
    IndependentPressAndSecureDrop("Independent Press & SecureDrop"),
    PrivacyAndInfrastructure("Privacy & Open-Source Infrastructure"),
}

/**
 * Editorial zine section grouping rendered on [StartPage].
 */
enum class OnionDirectorySection(val headerTitle: String) {
    SearchAndPress("SEARCH & PRESS"),
    InfrastructureAndDropboxes("INFRASTRUCTURE & DROPBOXES"),
}

/**
 * Immutable descriptor for a cryptographically verified v3 `.onion` hidden service.
 *
 * Every entry's 56-character RFC 4648 base32 service label encodes a real 32-byte
 * Ed25519 public key, 2-byte `SHA3-256(".onion checksum" || pubkey || 0x03)` checksum,
 * and version byte `0x03` per Tor `rend-spec-v3.txt` §6.
 */
@Immutable
data class OnionServiceEntry(
    val id: String,
    val title: String,
    val description: String,
    val url: String,
    val category: OnionCategory,
    val section: OnionDirectorySection,
) {
    /** Canonical lowercase `.onion` hostname extracted from [url]. */
    val onionHost: String = TrustedOnionDirectory.extractHost(url)

    /** 56-character lowercase RFC 4648 base32 v3 root `.onion` label. */
    val rootV3Label: String = TrustedOnionDirectory.extractRootV3OnionLabel(onionHost)
        ?: error("Invalid v3 .onion host in TrustedOnionDirectory entry '$id': $url")

    /**
     * Compact 18-character tail-preserving monospace key (e.g. `p53lf5…u5uqd.onion`)
     * for strict `<= 2:1` label density on [StartPage].
     */
    val shortOnionKey: String = "${rootV3Label.take(6)}\u2026${rootV3Label.takeLast(5)}.onion"
}

/**
 * Statically compiled, cryptographically verified directory of legitimate public-interest
 * Tor v3 `.onion` hidden services and default DuckDuckGo `.onion` zero-JS search resolver.
 */
object TrustedOnionDirectory {

    /** Official DuckDuckGo v3 `.onion` root URL. */
    const val DUCKDUCKGO_ONION_HOME_URL: String =
        "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/"

    /** Official DuckDuckGo zero-JS HTML v3 `.onion` search endpoint prefix. */
    const val DUCKDUCKGO_ONION_HTML_SEARCH_PREFIX: String =
        "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/html/?q="

    private const val V3_ONION_LABEL_LENGTH = 56
    private const val V3_ONION_RAW_BYTES = 35
    private const val V3_ONION_PUBKEY_BYTES = 32
    private const val V3_ONION_VERSION: Byte = 0x03
    private val ONION_CHECKSUM_PREFIX: ByteArray =
        ".onion checksum".encodeToByteArray()

    private val KECCAK_ROUND_CONSTANTS = longArrayOf(
        0x0000000000000001UL.toLong(),
        0x0000000000008082UL.toLong(),
        0x800000000000808aUL.toLong(),
        0x8000000080008000UL.toLong(),
        0x000000000000808bUL.toLong(),
        0x0000000080000001UL.toLong(),
        0x8000000080008081UL.toLong(),
        0x8000000000008009UL.toLong(),
        0x000000000000008aUL.toLong(),
        0x0000000000000088UL.toLong(),
        0x0000000080008009UL.toLong(),
        0x000000008000000aUL.toLong(),
        0x000000008000808bUL.toLong(),
        0x800000000000008bUL.toLong(),
        0x8000000000008089UL.toLong(),
        0x8000000000008003UL.toLong(),
        0x8000000000008002UL.toLong(),
        0x8000000000000080UL.toLong(),
        0x000000000000800aUL.toLong(),
        0x800000008000000aUL.toLong(),
        0x8000000080008081UL.toLong(),
        0x8000000000008080UL.toLong(),
        0x0000000080000001UL.toLong(),
        0x8000000080008008UL.toLong(),
    )

    /**
     * Complete immutable catalogue of known-good v3 `.onion` hidden services.
     */
    val entries: List<OnionServiceEntry> = listOf(
        OnionServiceEntry(
            id = "duckduckgo",
            title = "DuckDuckGo",
            description = "Private .onion Search",
            url = DUCKDUCKGO_ONION_HOME_URL,
            category = OnionCategory.Search,
            section = OnionDirectorySection.SearchAndPress,
        ),
        OnionServiceEntry(
            id = "propublica",
            title = "ProPublica",
            description = "Investigative Journalism",
            url = "https://p53lf57qovyuvwsc6xnrppyply3vtqm7l6pcobkmyqsiofyeznfu5uqd.onion/",
            category = OnionCategory.IndependentPressAndSecureDrop,
            section = OnionDirectorySection.SearchAndPress,
        ),
        OnionServiceEntry(
            id = "nytimes",
            title = "The New York Times",
            description = "Newsroom Hidden Service",
            url = "https://www.nytimesn7cgmftshazwhfgzm37qxb44r64ytbb2dj3x62d2lljsciiyd.onion/",
            category = OnionCategory.IndependentPressAndSecureDrop,
            section = OnionDirectorySection.SearchAndPress,
        ),
        OnionServiceEntry(
            id = "bbc_news",
            title = "BBC News",
            description = "International News",
            url = "https://www.bbcweb3hytmzhn5d532owbu6oqadra5z3ar726vq5kgwwn6aucdccrad.onion/",
            category = OnionCategory.IndependentPressAndSecureDrop,
            section = OnionDirectorySection.SearchAndPress,
        ),
        OnionServiceEntry(
            id = "the_guardian",
            title = "The Guardian",
            description = "News & Whistleblowing",
            url = "https://www.guardian2zotagl6tmjucg3lrhxdk4dw3lhbqnkvvkywawy3oqfoprid.onion/",
            category = OnionCategory.IndependentPressAndSecureDrop,
            section = OnionDirectorySection.SearchAndPress,
        ),
        OnionServiceEntry(
            id = "securedrop",
            title = "SecureDrop Project",
            description = "Freedom of the Press Foundation",
            url = "http://sdolvtfhatvsysc6l34d65ymdwxcujausv7k5jk4cy5ttzhjoi6fzvyd.onion/",
            category = OnionCategory.IndependentPressAndSecureDrop,
            section = OnionDirectorySection.InfrastructureAndDropboxes,
        ),
        OnionServiceEntry(
            id = "tor_project",
            title = "Tor Project",
            description = "Official Tor Project",
            url = "http://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion/",
            category = OnionCategory.PrivacyAndInfrastructure,
            section = OnionDirectorySection.InfrastructureAndDropboxes,
        ),
        OnionServiceEntry(
            id = "debian",
            title = "Debian Archive",
            description = "Official Debian Packages",
            url = "http://5ekxbftvqg26oir5wle3p27ax3wksbxcecnm6oemju7bjra2pn26s3qd.onion/",
            category = OnionCategory.PrivacyAndInfrastructure,
            section = OnionDirectorySection.InfrastructureAndDropboxes,
        ),
        OnionServiceEntry(
            id = "proton",
            title = "Proton",
            description = "Encrypted Mail & Services",
            url = "https://protonmailrmez3lotccipshtkleegetolb73fuirgj7r4o4vfu7ozyd.onion/",
            category = OnionCategory.PrivacyAndInfrastructure,
            section = OnionDirectorySection.InfrastructureAndDropboxes,
        ),
        OnionServiceEntry(
            id = "riseup",
            title = "Riseup",
            description = "Autonomous Tech Collective",
            url = "http://vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion/",
            category = OnionCategory.PrivacyAndInfrastructure,
            section = OnionDirectorySection.InfrastructureAndDropboxes,
        ),
        OnionServiceEntry(
            id = "mullvad",
            title = "Mullvad",
            description = "Privacy Infrastructure",
            url = "https://o54hon2e2vj6c7m3aqqu6uyece65by3vgoxxhlqlsvkmacw6a7m7kiad.onion/",
            category = OnionCategory.PrivacyAndInfrastructure,
            section = OnionDirectorySection.InfrastructureAndDropboxes,
        ),
    )

    /** Entries grouped by [OnionDirectorySection] in display order. */
    val entriesBySection: List<Pair<OnionDirectorySection, List<OnionServiceEntry>>> =
        OnionDirectorySection.entries.map { section ->
            section to entries.filter { it.section == section }
        }

    /** Entries grouped by semantic [OnionCategory]. */
    fun entriesForCategory(category: OnionCategory): List<OnionServiceEntry> =
        entries.filter { it.category == category }

    /**
     * Resolves [StartPage] search/address bar input:
     * - Returns `null` when blank.
     * - Preserves explicit `http://`, `https://`, and `about:` URLs verbatim.
     * - Preserves direct host/domain inputs (`.onion`, `localhost`, IPv4/IPv6, or valid `host.tld`).
     * - Routes all search queries to DuckDuckGo's zero-JS HTML v3 `.onion` endpoint
     *   (`https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/html/?q=<RFC3986>`).
     */
    fun resolveSearchOrAddress(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        val lower = trimmed.lowercase()
        if (lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("about:")
        ) {
            return trimmed
        }

        if (looksLikeDirectHostInput(trimmed)) {
            return trimmed
        }

        return buildDuckDuckGoOnionSearchUrl(trimmed)
    }

    /**
     * Builds a zero-JS HTML DuckDuckGo v3 `.onion` search URL with strict RFC 3986 percent-encoding.
     */
    fun buildDuckDuckGoOnionSearchUrl(query: String): String =
        DUCKDUCKGO_ONION_HTML_SEARCH_PREFIX + encodeRfc3986(query.trim())

    /**
     * Encodes [input] as UTF-8 RFC 3986 percent-encoded bytes (spaces become `%20`, never `+`).
     */
    fun encodeRfc3986(input: String): String {
        val utf8 = input.encodeToByteArray()
        val sb = StringBuilder(utf8.size + 16)
        val hex = "0123456789ABCDEF"
        for (byte in utf8) {
            val b = byte.toInt() and 0xFF
            val isUnreserved = (b in 'A'.code..'Z'.code) ||
                (b in 'a'.code..'z'.code) ||
                (b in '0'.code..'9'.code) ||
                b == '-'.code ||
                b == '.'.code ||
                b == '_'.code ||
                b == '~'.code
            if (isUnreserved) {
                sb.append(b.toChar())
            } else {
                sb.append('%')
                sb.append(hex[b ushr 4])
                sb.append(hex[b and 0x0F])
            }
        }
        return sb.toString()
    }

    private fun looksLikeDirectHostInput(trimmed: String): Boolean {
        if (trimmed.any(Char::isWhitespace)) return false
        if (trimmed.startsWith("?") || trimmed.startsWith("/")) return false

        val authority = trimmed
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        if (authority.isEmpty()) return false

        if (authority.startsWith("[")) {
            val closeIdx = authority.indexOf(']')
            if (closeIdx <= 1) return false
            val portPart = authority.substring(closeIdx + 1)
            return portPart.isEmpty() ||
                (portPart.startsWith(":") && portPart.drop(1).let { it.isNotEmpty() && it.all(Char::isDigit) })
        }

        val host = if (':' in authority) {
            val schemeOrHost = authority.substringBefore(':')
            val afterColon = authority.substringAfter(':')
            if (afterColon.isEmpty() || !afterColon.all(Char::isDigit)) {
                return false
            }
            schemeOrHost
        } else {
            authority
        }.trimEnd('.')

        if (host.isEmpty()) return false
        val lowerHost = host.lowercase()
        if (lowerHost == "localhost" || lowerHost.endsWith(".onion")) {
            return isValidHostnameSyntax(lowerHost)
        }
        if (isIpv4Literal(lowerHost)) return true

        val labels = lowerHost.split('.')
        if (labels.size < 2) return false
        if (!isValidHostnameSyntax(lowerHost)) return false
        val tld = labels.last()
        return tld.length >= 2 && tld.all { it in 'a'..'z' }
    }

    private fun isValidHostnameSyntax(host: String): Boolean {
        val labels = host.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return false
        return labels.all { label ->
            !label.startsWith('-') &&
                !label.endsWith('-') &&
                label.all { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '-' }
        }
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all(Char::isDigit) &&
                (part.toIntOrNull() ?: -1) in 0..255
        }
    }

    /**
     * Extracts the canonical lowercase hostname from an `http://` or `https://` URL.
     */
    fun extractHost(url: String): String {
        val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return ""
        val scheme = parsed.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return ""
        return parsed.host?.trimEnd('.')?.lowercase().orEmpty()
    }

    /**
     * Extracts the 56-character root label right before `.onion` if syntactically base32,
     * allowing optional valid DNS subdomain labels (e.g. `www.<56-char>.onion`).
     */
    fun extractRootV3OnionLabel(host: String): String? {
        val clean = host.trim().trimEnd('.').lowercase()
        if (!clean.endsWith(".onion")) return null
        val withoutTld = clean.removeSuffix(".onion")
        if (withoutTld.isEmpty()) return null
        val labels = withoutTld.split('.')
        if (labels.any { it.isEmpty() }) return null
        val subLabels = labels.dropLast(1)
        val validSubdomains = subLabels.all { sub ->
            sub.length <= 63 &&
                !sub.startsWith('-') &&
                !sub.endsWith('-') &&
                sub.all { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '-' }
        }
        if (!validSubdomains) return null

        val rootLabel = labels.last()
        if (rootLabel.length != V3_ONION_LABEL_LENGTH) return null
        if (!rootLabel.all { ch -> ch in 'a'..'z' || ch in '2'..'7' }) return null
        return rootLabel
    }

    /**
     * Cryptographically verifies a Tor v3 `.onion` URL per `rend-spec-v3.txt` §6:
     * 1. Valid `http://` or `https://` URL with `.onion` host and 56-char RFC 4648 base32 root label.
     * 2. Decodes to 35 bytes: `pubkey[32] || checksum[2] || version[1]`.
     * 3. Asserts `version == 0x03`, non-zero 32-byte Ed25519 public key, and
     *    `SHA3-256(".onion checksum" || pubkey || 0x03)[0..1] == checksum[0..1]`.
     */
    fun isCryptographicallyValidV3OnionUrl(url: String): Boolean {
        val host = extractHost(url)
        if (host.isEmpty()) return false
        val rootLabel = extractRootV3OnionLabel(host) ?: return false
        return isCryptographicallyValidV3OnionLabel(rootLabel)
    }

    /**
     * Cryptographically verifies a 56-character lowercase RFC 4648 base32 v3 `.onion` label.
     */
    fun isCryptographicallyValidV3OnionLabel(label: String): Boolean {
        if (label.length != V3_ONION_LABEL_LENGTH) return false
        val decoded = decodeBase32Lower(label) ?: return false
        if (decoded.size != V3_ONION_RAW_BYTES) return false

        val version = decoded[34]
        if (version != V3_ONION_VERSION) return false

        val pubkey = decoded.copyOfRange(0, V3_ONION_PUBKEY_BYTES)
        if (pubkey.all { it == 0.toByte() }) return false

        val checksum0 = decoded[32]
        val checksum1 = decoded[33]

        val digestInput = ByteArray(ONION_CHECKSUM_PREFIX.size + V3_ONION_PUBKEY_BYTES + 1)
        ONION_CHECKSUM_PREFIX.copyInto(digestInput, destinationOffset = 0)
        pubkey.copyInto(digestInput, destinationOffset = ONION_CHECKSUM_PREFIX.size)
        digestInput[digestInput.lastIndex] = version

        val digest = sha3_256(digestInput)
        return digest[0] == checksum0 && digest[1] == checksum1
    }

    /**
     * Decodes unpadded lowercase RFC 4648 Base32 (`a-z`, `2-7`) into raw bytes.
     */
    internal fun decodeBase32Lower(input: String): ByteArray? {
        if (input.isEmpty()) return ByteArray(0)
        val totalBits = input.length * 5
        if (totalBits % 8 != 0) return null
        val out = ByteArray(totalBits / 8)
        var buffer = 0
        var bitsLeft = 0
        var outIndex = 0
        for (ch in input) {
            val value = when (ch) {
                in 'a'..'z' -> ch - 'a'
                in '2'..'7' -> (ch - '2') + 26
                else -> return null
            }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out[outIndex++] = ((buffer ushr bitsLeft) and 0xFF).toByte()
            }
        }
        return if (outIndex == out.size) out else null
    }

    /**
     * Pure-Kotlin NIST FIPS 202 `SHA3-256` (`Keccak[c=512]`, domain suffix `0x06`, 24-round `Keccak-f[1600]`).
     */
    internal fun sha3_256(input: ByteArray): ByteArray {
        val rateBytes = 136
        val state = LongArray(25)
        var offset = 0

        while (offset + rateBytes <= input.size) {
            xorRateBlock(state, input, offset)
            keccakF1600(state)
            offset += rateBytes
        }

        val finalBlock = ByteArray(rateBytes)
        val remaining = input.size - offset
        if (remaining > 0) {
            input.copyInto(finalBlock, destinationOffset = 0, startIndex = offset, endIndex = input.size)
        }
        finalBlock[remaining] = (finalBlock[remaining].toInt() xor 0x06).toByte()
        finalBlock[rateBytes - 1] = (finalBlock[rateBytes - 1].toInt() xor 0x80).toByte()
        xorRateBlock(state, finalBlock, 0)
        keccakF1600(state)

        val digest = ByteArray(32)
        for (lane in 0 until 4) {
            val word = state[lane]
            val base = lane * 8
            for (b in 0 until 8) {
                digest[base + b] = ((word ushr (b * 8)) and 0xFFL).toByte()
            }
        }
        return digest
    }

    private fun xorRateBlock(state: LongArray, block: ByteArray, offset: Int) {
        for (lane in 0 until 17) {
            var word = 0L
            val base = offset + lane * 8
            for (b in 0 until 8) {
                word = word or ((block[base + b].toLong() and 0xFFL) shl (b * 8))
            }
            state[lane] = state[lane] xor word
        }
    }

    private fun keccakF1600(state: LongArray) {
        val c = LongArray(5)
        val d = LongArray(5)
        for (round in 0 until 24) {
            // Theta
            for (x in 0 until 5) {
                c[x] = state[x] xor state[x + 5] xor state[x + 10] xor state[x + 15] xor state[x + 20]
            }
            for (x in 0 until 5) {
                d[x] = c[(x + 4) % 5] xor c[(x + 1) % 5].rotateLeft(1)
            }
            for (i in 0 until 25) {
                state[i] = state[i] xor d[i % 5]
            }

            // Rho + Pi
            var x = 1
            var y = 0
            var current = state[1]
            for (t in 0 until 24) {
                val nextX = y
                val nextY = (2 * x + 3 * y) % 5
                val shift = ((t + 1) * (t + 2) / 2) % 64
                val idx = nextX + 5 * nextY
                val tmp = state[idx]
                state[idx] = current.rotateLeft(shift)
                current = tmp
                x = nextX
                y = nextY
            }

            // Chi
            for (row in 0 until 25 step 5) {
                val s0 = state[row]
                val s1 = state[row + 1]
                val s2 = state[row + 2]
                val s3 = state[row + 3]
                val s4 = state[row + 4]
                state[row] = s0 xor (s1.inv() and s2)
                state[row + 1] = s1 xor (s2.inv() and s3)
                state[row + 2] = s2 xor (s3.inv() and s4)
                state[row + 3] = s3 xor (s4.inv() and s0)
                state[row + 4] = s4 xor (s0.inv() and s1)
            }

            // Iota
            state[0] = state[0] xor KECCAK_ROUND_CONSTANTS[round]
        }
    }
}
