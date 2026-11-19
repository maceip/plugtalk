package dev.onionvault

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies 1:1 JNI symbol parity across all 35 [OvNative] `external fun` declarations
 * and `src/lib.rs`'s `#[no_mangle] pub extern "system" fn Java_dev_onionvault_OvNative_*`
 * exports, along with JSON contract parsers for clearnet consent, Tor status,
 * Monero light wallet (`crates/ov-xmr`), and zero-JS BTC -> XMR Tor swap tracking.
 */
@RunWith(RobolectricTestRunner::class)
class OvNativeTest {

    @Test
    fun every_external_method_has_a_matching_native_export_across_all_35_symbols() {
        val declared = externalMethods()
        assertEquals(
            "OvNative must declare exact 35 JNI external methods",
            35,
            declared.size,
        )

        val exported = nativeExports()
        assertEquals(
            "src/lib.rs must export exact 35 JNI functions",
            35,
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

    @Test
    fun xmr_wallet_status_seed_subaddress_and_send_json_parsers_match_ov_xmr_contract() {
        val emptyStatus = OvNative.parseXmrStatus("")
        assertFalse(emptyStatus.hasWallet)
        assertEquals("0", emptyStatus.unlockedBalanceXmr)

        val primaryAddr =
            "47WtH2aYaa6ceF14pr6FpHQjhLNz9wSA3Q6UHdJK4tAMAQgXbEYbo6x4FvJYZVCjuMBBxSMJDiGRU3tSjJt1HZTHNH3sAns"
        val subAddr1 =
            "82ffYh7xRhFBvBHEHGV8TNRBoYucBFNoyYnwrJJjnMGpHKPWosZeB4BDr7pBG4DCux1WKepdAV6rRTimi1LqWMacF88hvQm"

        val statusJson = """
            {
              "ok": true,
              "hasWallet": true,
              "walletLabel": "Trap Light Wallet",
              "primaryAddress": "$primaryAddr",
              "activeSubaddress": "$subAddr1",
              "activeSubaddressIndex": 1,
              "balanceAtomic": "1239077910000",
              "unlockedBalanceAtomic": "1239077910000",
              "balanceXmr": "1.23907791",
              "unlockedBalanceXmr": "1.23907791",
              "restoreHeight": 3809600,
              "syncedHeight": 3809609,
              "daemonHeight": 3809609,
              "birthdayPending": false,
              "syncEndpoint": "http://plowsoffjexmxalw73tkjmf422gq6575fc7vicuu4javzn2ynnte6tyd.onion:18089",
              "syncMode": "monerod_onion",
              "lastSyncEpochSecs": 1732000123,
              "subaddresses": [
                {
                  "index": 1,
                  "label": "Swap: WizardSwap (0.01 BTC)",
                  "address": "$subAddr1",
                  "receivedAtomic": "1239077910000",
                  "receivedXmr": "1.23907791"
                }
              ],
              "recentTxs": [
                {
                  "txid": "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12",
                  "direction": "in",
                  "amountAtomic": "1239077910000",
                  "amountXmr": "1.23907791",
                  "feeAtomic": "20000000",
                  "feeXmr": "0.00002",
                  "height": 3809605,
                  "timestamp": 1732000000,
                  "subaddressIndex": 1,
                  "unlocked": true
                }
              ],
              "swaps": [
                {
                  "swapId": "swap-01",
                  "provider": "WizardSwap",
                  "btcAmount": "0.01",
                  "quotedXmr": "1.23907791",
                  "launchUrl": "http://wizardswgtu2ovor7r2esg3cxdpt7tv4nrugi32lldv53zmtonbz6sid.onion/?from=BTC&to=XMR&amount=0.01",
                  "subaddressIndex": 1,
                  "subaddress": "$subAddr1",
                  "btcDepositAddress": "bc1qwxcndrucjwn7f4u2vrq2lx0hv543sjz57e77q2",
                  "btcDepositAmount": "0.01",
                  "providerOrderId": "3X314OQK",
                  "orderUrl": "http://wizardswgtu2ovor7r2esg3cxdpt7tv4nrugi32lldv53zmtonbz6sid.onion/id=3X314OQK",
                  "status": "awaiting_btc_deposit",
                  "payoutTxid": "",
                  "receivedAtomic": "0",
                  "receivedXmr": "0",
                  "createdEpochSecs": 1732000050
                }
              ],
              "error": ""
            }
        """.trimIndent()

        val status = OvNative.parseXmrStatus(statusJson)
        assertTrue(status.ok)
        assertTrue(status.hasWallet)
        assertEquals("Trap Light Wallet", status.walletLabel)
        assertEquals(primaryAddr, status.primaryAddress)
        assertEquals(subAddr1, status.activeSubaddress)
        assertEquals(1, status.activeSubaddressIndex)
        assertEquals("1.23907791", status.unlockedBalanceXmr)
        assertEquals(3809609L, status.syncedHeight)
        assertEquals(3809609L, status.daemonHeight)
        assertEquals("monerod_onion", status.syncMode)
        assertEquals(1, status.subaddresses.size)
        assertEquals("Swap: WizardSwap (0.01 BTC)", status.subaddresses[0].label)
        assertEquals(1, status.recentTxs.size)
        assertTrue(status.recentTxs[0].unlocked)
        assertEquals(1, status.swaps.size)
        assertEquals("bc1qwxcndrucjwn7f4u2vrq2lx0hv543sjz57e77q2", status.swaps[0].btcDepositAddress)

        val seedRes = OvNative.parseXmrSeedResult(
            """{"ok":true,"mnemonic":"abbey abducts ability ablaze abnormal abort abrasive absorb abyss academy aces aching acidic acoustic acquire across actress acumen adapt addicted adept adhesive adjust adopt abbey","primaryAddress":"$primaryAddr","activeSubaddress":"$subAddr1","activeSubaddressIndex":1,"viewKeyHex":"2335d165d03c9b604957c16be26060f323528424c057df314390f2f9304b8e00","restoreHeight":3809605,"error":""}""",
        )
        assertTrue(seedRes.ok)
        assertEquals(25, seedRes.mnemonic.split(" ").size)
        assertEquals(3809605L, seedRes.restoreHeight)

        val newSub = OvNative.parseXmrNewSubaddressResult(
            """{"ok":true,"index":2,"label":"Tip Jar","address":"$subAddr1","error":""}""",
        )
        assertTrue(newSub.ok)
        assertEquals(2, newSub.index)
        assertEquals("Tip Jar", newSub.label)

        val sendRes = OvNative.parseXmrSendResult(
            """{"ok":true,"txid":"deadbeefcafebabe","feeAtomic":"31400000","feeXmr":"0.0000314","amountAtomic":"500000000000","amountXmr":"0.5","rawTxBytes":1507,"error":""}""",
        )
        assertTrue(sendRes.ok)
        assertEquals("deadbeefcafebabe", sendRes.txid)
        assertEquals("0.0000314", sendRes.feeXmr)
        assertEquals(1507, sendRes.rawTxBytes)
    }

    @Test
    fun btc_xmr_swap_quotes_actions_and_monero_uri_parsers_handle_edge_cases() {
        val subAddr1 =
            "82ffYh7xRhFBvBHEHGV8TNRBoYucBFNoyYnwrJJjnMGpHKPWosZeB4BDr7pBG4DCux1WKepdAV6rRTimi1LqWMacF88hvQm"

        val quotesJson = """
            {
              "ok": true,
              "btcAmount": "0.01",
              "sourceOnion": "rnwis2whetqcj4oknksnc5l24jbh33nflunifff3xtjjonnoxu3ld6id.onion",
              "fetchedAtEpochSecs": 1732000200,
              "quotes": [
                {
                  "id": "wizardswap-1",
                  "provider": "WizardSwap",
                  "estimatedXmr": "1.23907791",
                  "rateXmrPerBtc": "123.907791",
                  "spreadTag": "-0.42%",
                  "neverKyc": true,
                  "kycWarning": "",
                  "isOnionUrl": true,
                  "launchUrl": "http://wizardswgtu2ovor7r2esg3cxdpt7tv4nrugi32lldv53zmtonbz6sid.onion/?from=BTC&to=XMR&amount=0.01"
                }
              ],
              "error": ""
            }
        """.trimIndent()
        val quotes = OvNative.parseBtcSwapQuotes(quotesJson)
        assertTrue(quotes.ok)
        assertEquals("0.01", quotes.btcAmount)
        assertEquals(1, quotes.quotes.size)
        assertEquals("WizardSwap", quotes.quotes[0].provider)
        assertTrue(quotes.quotes[0].isOnionUrl)
        assertTrue(quotes.quotes[0].neverKyc)

        val actionRes = OvNative.parseXmrSwapsAction(
            """
            {
              "ok": true,
              "swap": {
                "swapId": "sw-99",
                "provider": "WizardSwap",
                "btcAmount": "0.01",
                "quotedXmr": "1.23907791",
                "launchUrl": "http://wizardswgtu2ovor7r2esg3cxdpt7tv4nrugi32lldv53zmtonbz6sid.onion/?from=BTC&to=XMR&amount=0.01",
                "subaddressIndex": 3,
                "subaddress": "$subAddr1",
                "btcDepositAddress": "",
                "btcDepositAmount": "",
                "providerOrderId": "",
                "orderUrl": "",
                "status": "awaiting_form",
                "payoutTxid": "",
                "receivedAtomic": "0",
                "receivedXmr": "0",
                "createdEpochSecs": 1732000300
              },
              "swaps": [],
              "error": ""
            }
            """.trimIndent(),
        )
        assertTrue(actionRes.ok)
        assertNotNull(actionRes.swap)
        assertEquals("sw-99", actionRes.swap?.swapId)
        assertEquals(subAddr1, actionRes.swap?.subaddress)

        val parsedUri = OvNative.parseMoneroUri(
            "monero:$subAddr1?tx_amount=0.25&tx_description= whistle%20drop &recipient_name=Source+Desk",
        )
        assertNotNull(parsedUri)
        assertEquals(subAddr1, parsedUri?.address)
        assertEquals("0.25", parsedUri?.amountXmr)
        assertEquals("whistle drop", parsedUri?.txDescription)
        assertEquals("Source Desk", parsedUri?.recipientName)

        assertNull(OvNative.parseMoneroUri("https://example.org"))
        assertNull(OvNative.parseMoneroUri("monero:not_a_valid_monero_address"))
        assertNull(OvNative.parseMoneroUri("monero:$subAddr1?tx_amount=-1.5"))
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
