# Trap Pure-Rust Monero Light Wallet, `XMR402` (`OutProofV2`), & Zero-JS BTC → XMR Swap Architecture

## 1. Zero-Fabrication & Zero-Leak Security Model

Trap's Monero light wallet (`crates/ov-xmr`) and browser swap/`XMR402` integration (`crates/ov-cef`, `crates/ov-core`) enforce strict cryptographic and network invariants:

1. **100% Fail-Closed Tor SOCKS5h Routing (`ov_xmr::transport`)**:
   - Every HTTP/1.1 (`GET`/`POST`) and `monero-daemon-rpc` request connects exclusively to Trap's in-process Arti loopback SOCKS5h proxy (`127.0.0.1:<port>`) using RFC 1928 `ATYP = 0x03` domain names (zero OS DNS resolution).
   - Separate RFC 1929 SOCKS5 usernames (`ov-xmr-rpc` vs `ov-xmr-swap`) isolate wallet RPC circuits from quote aggregator and browser tab circuits.
   - Calls fail closed immediately when `socks_addr` is unset, non-loopback, or before Arti reports `ready_for_traffic()`.
2. **Zero View-Key Disclosure by Default (`.onion` `monerod` View-Key Scanning & Chain-Switch Safety)**:
   - Public `monero-lws` servers queue unapproved accounts behind HTTP `403 Forbidden` manual admin review and require sharing the private view key with a remote server.
   - Trap defaults to `sync_mode = "monerod_onion"`, failing over across 4 verified `.onion` `monerod` nodes (`plowsoff...onion:18089`, `moneronkv...onion:18089`, `2chk3x3...onion:18089`, `24kkg3r...onion:18081`) and scanning blocks locally in pure Rust via `monero_wallet::Scanner` from the wallet's `restore_height`.
   - When `birthday_pending == true`, small `.onion` chains (`tip <= 10_000`) snap `restore_height = 1` so payouts mined during setup are never skipped, while mainnet chains (`tip > 10_000`) snap `restore_height = tip.saturating_sub(5)`.
   - Changing `sync_endpoint` via `nativeXmrSetDaemonEndpointJson` clears cached `xmr_outputs`, `xmr_transactions`, and `xmr_tx_secrets` from the previous chain and resets `synced_height = 0, daemon_height = 0, birthday_pending = true` while preserving wallet keys, subaddresses, and tracked swaps.
   - Self-hosted `monero-lws` servers (`--auto-accept-creation`) are also supported via `sync_mode = "lws"` (`/login`, `/get_address_info`, `/get_unspent_outs`, `/submit_raw_tx`), with client-side key-image verification ($I = (s + \text{offset}) \cdot H_p(P)$) and truthful `403 Forbidden` diagnostics.
3. **Hardware-Backed Keystore Wrapping (`xmr/wallet.db`)**:
   - Both the canonical 32-byte Ed25519 spend scalar (`xmr_wallet_meta.wrapped_spend_key`) and per-transaction outgoing proof secrets (`xmr_tx_secrets.wrapped_secret_blob`, holding `ovk` + spent `(key, commitment)` pairs) are wrapped at rest using Trap's `ov_pgp::MasterKeyProvider` (`AndroidKeystoreMasterKey` `AES-256-GCM` on Android; `0600` `SoftwareMasterKey` on host tests) and zeroized on drop (`zeroize::Zeroizing`).
   - Toggling `nativeSetRequireBiometric` atomically re-wraps OpenPGP secret keys, the Monero spend key, and all `xmr_tx_secrets` rows under the target Keystore alias.
   - `ViewPair` operations (`nativeXmrStatusJson`, `nativeXmrNewSubaddressJson`, incoming block scanning) reconstruct `ViewPair` from `primary_address` + `view_key_bytes` without unwrapping the spend scalar.
   - Calling `nativeXmrNewSubaddressJson("site:<host>")` idempotently reuses or mints a dedicated `(0, minor)` `8...` subaddress per `.onion` origin so cross-site addresses never correlate.
4. **Canonical Monero `OutProofV2` & `XMR402` (`HTTP 402 Payment Required`)**:
   - Reconstructs exact ephemeral transaction scalars $(r_0, r_1, \dots)$ via `monero_wallet::send::TransactionKeys` and produces canonical Monero `OutProofV2` Schnorr DLEQ proofs (`wallet2::get_tx_proof` / `check_tx_proof`, `TXPROOF_V2` separator, un-cofactored $D_i = r_i A$ + cofactor-8 ECDH output/Pedersen verification) formatted for `Authorization: XMR402 txid="<64-hex>", proof="OutProofV2..."`.
5. **Zero-JavaScript Browser Integration (`crates/ov-cef/src/cdp.rs`)**:
   - All swap pages, `XMR402` gates, and payment links run with Chromium JavaScript disabled, inspected solely via C++ CDP `DOM.getDocument({"depth": -1, "pierce": true})` and `Network.responseReceived`.

---

## 2. Crate Layout (`crates/ov-xmr`)

| Module | Responsibility |
| :--- | :--- |
| `mnemonic.rs` | Canonical 1,626-word Monero English Electrum dictionary (unique 3-char prefixes), 24-word base-1626 little-endian `u32` encoding/decoding + 25th IEEE CRC-32 checksum word, canonical Ed25519 scalar enforcement |
| `wallet.rs` | `view_scalar = Hs(spend_bytes)`, `ViewPair`, mainnet `4...` primary address & `(0, minor)` `8...` subaddress derivation, key image $I = (s + \text{offset}) \cdot H_p(P)$, and `monero-lws` `WalletOutput` reconstruction |
| `uri.rs` | Exact integer `u64` piconero (`10^12`) <-> decimal `XMR` formatting/parsing (`format_atomic_xmr`, `parse_xmr_to_atomic`) with zero floating-point rounding + `monero:` URI parser/formatter |
| `qr.rs` | Pure-Rust ISO/IEC 18004 QR code matrix encoder (`qrcode` `EcLevel::M`, zero image/SVG dependencies) for `monero:` and `bitcoin:` URIs |
| `tx_proof.rs` | Deterministic `TransactionKeys` ephemeral secret reconstruction + canonical Monero `OutProofV2` prover & stateless RingCT Pedersen verifier |
| `xmr402.rs` | `XMR402` (`HTTP 402`) `WWW-Authenticate` RFC 7235 parser + `<meta name="xmr402-challenge">` JSON fallback validator + `.onion` `daemonUrl` policy enforcement |
| `store.rs` | SQLite WAL store (`xmr_wallet_meta`, `xmr_subaddresses`, `xmr_outputs`, `xmr_transactions`, `xmr_tx_secrets`, `btc_xmr_swaps`, `xmr_settings`), chain-switch cache reset, Keystore secret rotation, and automatic swap completion reconciliation |
| `transport.rs` | Async loopback SOCKS5h (`ATYP = 0x03`) + HTTP/1.1 (`Content-Length`, `chunked`, `gzip`/`deflate`, bounded redirects) + `tokio-rustls` TLS + `monero_daemon_rpc::HttpTransport` |
| `sync.rs` | `.onion` `monerod` failover & chunked `ScannableBlock` scanning, small-chain/mainnet `birthday_pending` snapping, `10`-confirmation unlock rule, `monero-lws` REST sync, Ring-16 `RctType::ClsagBulletproofPlus` signing & broadcast, `pay_xmr402_tx`, and `prove_stored_tx` |
| `swap.rs` | Live zero-JS BTC → XMR quote fetching & `<monolith class="rate " data-json="...">` HTML parsing from OrangeFren `.onion` (`filters[nojs]=1`), normalizing WizardSwap `.onion` query parameters and prioritizing `.onion` offers |

---

## 3. Zero-JS CDP DOM & Selection Classifications (`crates/ov-cef`)

- **`"xmr402_challenge"`** (`actionLabel = "Pay XMR402"`): Detects `<meta name="xmr402-challenge" content="...">` in `<head>` or `<body>`, surfacing its JSON/header payload to `nativeXmrParse402ChallengeJson`.
- **`"xmr_address_input"`** (`actionLabel = "Insert XMR Subaddress"`): Matches editable `<input>` and `<textarea>` Monero destination/payout wallet fields (WizardSwap `input#address`, Pablo Cash `input#payoutAddress[name="payout_addr"]`, HiddenSwap `textarea#pair-address[name="address"][data-address]`).
- **Crypto Refund / Source Suppression**: Editable crypto refund, source, BTC destination, TXID, memo, and swap-quantity fields return `None` rather than `"pgp_textarea"`, keeping zero-JS swap forms free of unrelated PGP compose pills.
- **`"btc_deposit_address"`** (`actionLabel = "Copy BTC Deposit"`): Validates Bitcoin Bech32 (`bc1q...`, `tb1q...`, `bcrt1q...`), Bech32m (`bc1p...`, `tb1p...`, `bcrt1p...`), and Base58Check (`1...`, `3...`) checksums via BIP-173/BIP-350 `hrp_expand` polymod and double-SHA-256 in `bitcoin:` links, `<input readonly>`, and leaf order containers, attaching the detected BTC deposit amount in `fingerprintHint`.
- **`"monero_uri"`** (`actionLabel = "Pay XMR"`): Validates `monero:<4_or_8_mainnet_address>?tx_amount=...` links and leaf containers against Monero Base58Check + Ed25519 point decompression.
- **`"monero_address"` Selection Classification** (`actionLabel = "Send XMR"`): `classify_text_with_hints` detects selected/copied `monero:` URIs (attaching `tx_amount` in `fingerprintHint`) and standalone checksum-valid `4...`/`8...` Monero addresses.

---

## 4. `OvNative` JNI Entry Points (`39` Total Exports)

`libonion_vault.so` exports 39 sorted `Java_dev_onionvault_OvNative_*` C/JNI symbols (`24` core/Tor/PGP/CDP + `15` Monero/`XMR402`/Swap/QR):

1. `nativeXmrCreateWalletJson(label)`
2. `nativeXmrDeleteWallet()`
3. `nativeXmrExportSeedJson()`
4. `nativeXmrFetchBtcSwapQuotesJson(btcAmountDecimal)`
5. `nativeXmrNewSubaddressJson(label)`
6. `nativeXmrParse402ChallengeJson(wwwAuthenticateHeader, fallbackMetaJson, pageUrl)`
7. `nativeXmrPay402Json(challengeJson)`
8. `nativeXmrProveTxJson(txid, recipientAddress, message)`
9. `nativeXmrQrMatrixJson(payload)`
10. `nativeXmrRestoreWalletJson(mnemonic, restoreHeight)`
11. `nativeXmrSendJson(destinationAddress, amountDecimalOrAtomic, sweepAll)`
12. `nativeXmrSetDaemonEndpointJson(endpointUrl, mode)`
13. `nativeXmrStatusJson()`
14. `nativeXmrSwapsActionJson(actionJson)`
15. `nativeXmrSyncJson()`
