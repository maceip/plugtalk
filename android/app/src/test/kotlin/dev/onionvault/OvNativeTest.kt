package dev.onionvault

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies 1:1 JNI symbol parity across all 24 [OvNative] `external fun` declarations
 * and `src/lib.rs`'s `#[no_mangle] pub extern "system" fn Java_dev_onionvault_OvNative_*`
 * exports, along with JSON contract parsers for clearnet consent and Tor status.
 */
@RunWith(RobolectricTestRunner::class)
class OvNativeTest {

    @Test
    fun every_external_method_has_a_matching_native_export_across_all_24_symbols() {
        val declared = externalMethods()
        assertEquals(
            "OvNative must declare exact 24 JNI external methods",
            24,
            declared.size,
        )

        val exported = nativeExports()
        assertEquals(
            "src/lib.rs must export exact 24 JNI functions",
            24,
            exported.size,
        )

        assertEquals(
            "OvNative external methods must match src/lib.rs JNI exports 1:1",
            exported.sorted(),
            declared.sorted(),
        )
    }

    @Test
    fun the_library_of_the_engine_is_named_after_the_crate() {
        assertEquals("onion_vault", OvNativeLibrary.NAME)
        assertEquals("onion_vault", OvNative.LIBRARY_NAME)
        assertEquals(259_200L, OvNative.DEFAULT_CLEARNET_TTL_SECONDS)
    }

    @Test
    fun clearnet_url_policy_status_and_rules_json_parsers_match_native_schema() {
        val statusDefault = OvNative.parseTorStatus("")
        assertFalse(statusDefault.hiddenServiceOnly)
        assertEquals("", statusDefault.pendingClearnetHost)

        val statusPending = OvNative.parseTorStatus(
            """{"bootstrapped":true,"bootstrapPercent":100,"bootstrapSummary":"Done","socksAddr":"127.0.0.1:41234","hiddenServiceOnly":false,"isolationToken":"","isolatedGroupCount":1,"pendingClearnetHost":"tor.taxi"}""",
        )
        assertTrue(statusPending.bootstrapped)
        assertFalse(statusPending.hiddenServiceOnly)
        assertEquals("tor.taxi", statusPending.pendingClearnetHost)

        val consentPolicy = OvNative.parseUrlPolicy(
            """{"allowed":false,"normalizedUrl":"https://tor.taxi","isOnion":false,"requiresClearnetConsent":true,"host":"tor.taxi","suggestedPattern":"*.tor.taxi","reason":"Clearnet consent required for tor.taxi"}""",
        )
        assertFalse(consentPolicy.allowed)
        assertFalse(consentPolicy.isOnion)
        assertTrue(consentPolicy.requiresClearnetConsent)
        assertEquals("https://tor.taxi", consentPolicy.normalizedUrl)
        assertEquals("tor.taxi", consentPolicy.host)
        assertEquals("*.tor.taxi", consentPolicy.suggestedPattern)

        val rules = OvNative.parseClearnetRules(
            """[{"pattern":"*.tor.taxi","createdAtMs":1700000000000,"expiresAtMs":1700259200000,"sessionOnly":false},{"pattern":"once.example","createdAtMs":1700000000000,"expiresAtMs":0,"sessionOnly":true}]""",
        )
        assertEquals(2, rules.size)
        assertEquals("*.tor.taxi", rules[0].pattern)
        assertFalse(rules[0].sessionOnly)
        assertEquals(1700259200000L, rules[0].expiresAtMs)
        assertTrue(rules[1].sessionOnly)

        assertEquals("*.tor.taxi", OvNative.suggestGlob("tor.taxi"))
        assertEquals("*.tor.taxi", OvNative.suggestGlob("www.tor.taxi"))
        assertEquals("*.bbc.co.uk", OvNative.suggestGlob("www.bbc.co.uk"))
        assertEquals("1.1.1.1", OvNative.suggestGlob("1.1.1.1"))
        assertEquals("2606:4700:4700::1111", OvNative.suggestGlob("[2606:4700:4700::1111]"))
    }

    private fun externalMethods(): Set<String> {
        val source = sourceFile("android/app/src/main/kotlin/dev/onionvault/OvNative.kt")
        val text = source.readText()
        val pattern = Regex("""external\s+fun\s+(\w+)\s*\(""")
        return pattern.findAll(text)
            .map { it.groupValues[1] }
            .map { "Java_dev_onionvault_OvNative_$it" }
            .toSet()
    }

    private fun nativeExports(): Set<String> {
        val source = sourceFile("src/lib.rs")
        val text = source.readText()
        val pattern = Regex("""fn\s+(Java_\w+)\s*\(""")
        return pattern.findAll(text).map { it.groupValues[1] }.toSet()
    }

    private fun sourceFile(path: String): File {
        val cwd = File(".").canonicalFile
        val root = generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, path).isFile }
            ?: cwd
        val file = File(root, path)
        assertTrue("Expected repository file to exist: ${file.path}", file.isFile)
        return file
    }
}

object OvNativeLibrary {
    const val NAME = "onion_vault"
}
