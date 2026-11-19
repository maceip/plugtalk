# `trap-xmr402-swap` — Zero-JS `XMR402` Vault + Real `BTC → XMR` Swap Tor v3 Hidden Service

Standalone Rust HTTP backend listening strictly on `127.0.0.1:8081` behind `tor@default.service` (`HiddenServiceVersion 3`, `HiddenServicePort 80 127.0.0.1:8081`, `HiddenServicePort 18081 127.0.0.1:18081`).

## Live Tor v3 Hidden Service Endpoints

- **Vault (`HTTP 402` → `HTTP 200` `OutProofV2`)**: `http://6e52wfxr64n2zxjhalrldumrjuqfzjh5gbnyarrkuxrvjrusf25g6yad.onion/vault`
- **Zero-JS `BTC → XMR` Swap Desk**: `http://6e52wfxr64n2zxjhalrldumrjuqfzjh5gbnyarrkuxrvjrusf25g6yad.onion/swap`
- **Restricted Wallet-Safe `monerod` RPC**: `http://6e52wfxr64n2zxjhalrldumrjuqfzjh5gbnyarrkuxrvjrusf25g6yad.onion:18081`

## Security & Cryptographic Properties

- **100% Zero JavaScript**: Every HTTP response enforces `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'` and `Cache-Control: no-store`.
- **Loopback-Only Enforcement**: Refuses to bind or connect to any non-loopback address (`127.0.0.1` / `::1`). Admin `monerod` (`127.0.0.1:18082`) and `bitcoind` (`127.0.0.1:18443`) are unreachable from Tor or clearnet; only restricted `monerod` (`127.0.0.1:18081`, `restricted: true`) and the HTTP web app (`127.0.0.1:8081`) are mapped on the `.onion` descriptor.
- **Stateless Canonical Monero `OutProofV2` Verification**:
  - `GET /` and `GET /vault` issue HMAC-SHA256 challenge nonces (`xmr402-citadel-<unix_ts>-<hmac16>`, 20-minute TTL, retained in a bounded 1024-entry ring buffer) via both `WWW-Authenticate: XMR402 ...` and `<meta name="xmr402-challenge" ...>`.
  - `Authorization: XMR402 txid="<hex>", proof="OutProofV2..."` fetches raw `Transaction::V2` bytes from `monerod` `/get_transactions` (supporting both 0-conf mempool and mined blocks), verifies the Schnorr DLEQ signature over un-cofactored $D_i = r_i A$, derives cofactor-multiplied $8 D_i$, decrypts ECDH compact amounts, verifies Pedersen commitments $C_n = \text{mask}\cdot G + \text{amount}\cdot H \ge 0.25\text{ XMR}$, prevents `txid` replay, mines 1 confirmation block, and unlocks the prize certificate.
- **Real `bitcoind` + Ring-16 `ClsagBulletproofPlus` Swap Desk**:
  - Allocates genuine Bech32 (`bcrt1q...`) addresses via `bitcoind` JSON-RPC, confirms real `0.01 BTC` regtest deposits (`getreceivedbyaddress >= 0.01`), signs and broadcasts a real Ring-16 `ClsagBulletproofPlus` `1.0 XMR` transaction to the user's `8...` subaddress, and mines 11 blocks so the client's `1.0 XMR` UTXO is immediately unlocked for `XMR402` spending.
