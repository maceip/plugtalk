# Trap Pure-Rust Monero Light Wallet & Zero-JS BTC → XMR Swap Architecture

## 1. Zero-Fabrication & Zero-Leak Security Model

Trap's Monero light wallet (`crates/ov-xmr`) and browser swap integration (`crates/ov-cef`, `crates/ov-core`) enforce strict cryptographic and network invariants:

1. **100% Fail-Closed Tor SOCKS5h Routing (`ov_xmr::transport`)**:
   - Every HTTP/1.1 (`GET`/`POST`) and `monero-daemon-rpc` request connects exclusively to Trap's in-process Arti loopback SOCKS5h proxy (`127.0.0.1:<port>`) using RFC 1928 `ATYP = 0x03` domain names (zero OS DNS resolution).
   - Separate RFC 1929 SOCKS5 usernames (`ov-xmr-rpc` vs `ov-xmr-swap`) isolate wallet RPC circuits from quote aggregator and browser tab circuits.
   - Calls fail closed immediately when `socks_addr` is unset, non-loopback, or before Arti reports `ready_for_traffic()`.
2. **Zero View-Key Disclosure by Default (`.onion` `monerod` View-Key Scanning)**:
   - Public `monero-lws` servers queue unapproved accounts behind HTTP `403 Forbidden` manual admin review and require sharing the private view key with a remote server.
   - Trap defaults to `sync_mode = "monerod_onion"`, failing over across 4 verified `.onion` `monerod` nodes (`plowsoff...onion:18089`, `moneronkv...onion:18089`, `2chk3x3...onion:18089`, `24kkg3r...onion:18081`) and scanning blocks locally in pure Rust via `monero_wallet::Scanner` from the wallet's `restore_height`.
   - Self-hosted `monero-lws` servers (`--auto-accept-creation`) are also supported via `sync_mode = "lws"` (`/login`, `/get_address_info`, `/get_unspent_outs`, `/submit_raw_tx`), with client-side key-image verification ($I = (s + \text{offset}) \cdot H_p(P)$) and truthful `403 Forbidden` diagnostics.
3. **Hardware-Backed Keystore Wrapping (`xmr/wallet.db`)**:
   - The canonical 32-byte Ed25519 spend scalar is wrapped at rest using Trap's `ov_pgp::MasterKeyProvider` (`AndroidKeystoreMasterKey` `AES-256-GCM` on Android; `0600` `SoftwareMasterKey` on host tests) and zeroized on drop (`zeroize::Zeroizing`).
   - Toggling `nativeSetRequireBiometric` atomically re-wraps both OpenPGP secret keys and the Monero spend key under the target Keystore alias.
   - `ViewPair` operations (`nativeXmrStatusJson`, `nativeXmrNewSubaddressJson`, incoming block scanning) reconstruct `ViewPair` from `primary_address` + `view_key_bytes` without unwrapping the spend scalar.
4. **Zero-JavaScript Browser Integration (`crates/ov-cef/src/cdp.rs`)**:
   - All swap pages and payment links run with Chromium JavaScript disabled, inspected solely via C++ CDP `DOM.getDocument({"depth": -1, "pierce": true})`.

---

## 2. Crate Layout (`crates/ov-xmr`)

| Module | Responsibility |
| :--- | :--- |
| `mnemonic.rs` | Canonical 1,626-word Monero English Electrum dictionary (unique 3-char prefixes), 24-word base-1626 little-endian `u32` encoding/decoding + 25th IEEE CRC-32 checksum word, canonical Ed25519 scalar enforcement |
| `wallet.rs` | `view_scalar = Hs(spend_bytes)`, `ViewPair`, mainnet `4...` primary address & `(0, minor)` `8...` subaddress derivation, key image $I = (s + \text{offset}) \cdot H_p(P)$, and `monero-lws` `WalletOutput` reconstruction |
| `uri.rs` | Exact integer `u64` piconero (`10^12`) <-> decimal `XMR` formatting/parsing (`format_atomic_xmr`, `parse_xmr_to_atomic`) with zero floating-point rounding + `monero:` URI parser/formatter |
| `store.rs` | SQLite WAL store (`xmr_wallet_meta`, `xmr_subaddresses`, `xmr_outputs`, `xmr_transactions`, `btc_xmr_swaps`, `xmr_settings`) + automatic swap completion reconciliation |
| `transport.rs` | Async loopback SOCKS5h (`ATYP = 0x03`) + HTTP/1.1 (`Content-Length`, `chunked`, `gzip`/`deflate`, bounded redirects) + `tokio-rustls` TLS + `monero_daemon_rpc::HttpTransport` |
| `sync.rs` | `.onion` `monerod` failover & chunked `ScannableBlock` scanning, `birthday_pending` tip snapping, `10`-confirmation unlock rule, `monero-lws` REST sync, and Ring-16 `RctType::ClsagBulletproofPlus` transaction signing & broadcast |
| `swap.rs` | Live zero-JS BTC → XMR quote fetching & `<monolith class="rate " data-json="...">` HTML parsing from OrangeFren `.onion` (`filters[nojs]=1`), normalizing WizardSwap `.onion` query parameters and prioritizing `.onion` offers |

---

## 3. Zero-JS CDP DOM Classifications (`crates/ov-cef/src/cdp.rs`)

- **`"xmr_address_input"`** (`actionLabel = "Insert XMR Subaddress"`): Matches editable `<input>` and `<textarea>` Monero destination/payout wallet fields (WizardSwap `input#address`, Pablo Cash `input#payoutAddress[name="payout_addr"]`, HiddenSwap `textarea#pair-address[name="address"][data-address]`).
- **Crypto Refund / Source Suppression**: Editable crypto refund, source, BTC destination, TXID, memo, and swap-quantity fields return `None` rather than `"pgp_textarea"`, keeping zero-JS swap forms free of unrelated PGP compose pills.
- **`"btc_deposit_address"`** (`actionLabel = "Copy BTC Deposit"`): Validates Bitcoin mainnet Bech32 (`bc1q...`), Bech32m (`bc1p...`), and Base58Check (`1...`, `3...`) checksums in `bitcoin:` links, `<input readonly>`, and leaf order containers, attaching the detected BTC deposit amount in `fingerprintHint`.
- **`"monero_uri"`** (`actionLabel = "Pay XMR"`): Validates `monero:<4_or_8_mainnet_address>?tx_amount=...` links and leaf containers against Monero Base58Check + Ed25519 point decompression.

---

## 4. `OvNative` JNI Entry Points (`35` Total Exports)

`libonion_vault.so` exports 35 `Java_dev_onionvault_OvNative_*` C/JNI symbols (`24` core/Tor/PGP/CDP + `11` Monero/Swap):

1. `nativeXmrCreateWalletJson(label)`
2. `nativeXmrDeleteWallet()`
3. `nativeXmrExportSeedJson()`
4. `nativeXmrFetchBtcSwapQuotesJson(btcAmountDecimal)`
5. `nativeXmrNewSubaddressJson(label)`
6. `nativeXmrRestoreWalletJson(mnemonic, restoreHeight)`
7. `nativeXmrSendJson(destinationAddress, amountDecimalOrAtomic, sweepAll)`
8. `nativeXmrSetDaemonEndpointJson(endpointUrl, mode)`
9. `nativeXmrStatusJson()`
10. `nativeXmrSwapsActionJson(actionJson)`
11. `nativeXmrSyncJson()`
