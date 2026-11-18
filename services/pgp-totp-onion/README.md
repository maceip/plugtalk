# `trap-pgp-totp` — Zero-JS 3-Round OpenPGP TOTP Tor v3 Hidden Service

Standalone Rust HTTP backend designed exclusively to run on `127.0.0.1:8080` behind `tor.service` (`HiddenServiceVersion 3`, `HiddenServicePort 80 127.0.0.1:8080`).

## Security & Cryptographic Properties

- **100% Zero JavaScript**: Every HTTP response enforces `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'` and `Cache-Control: no-store`.
- **Loopback-Only Enforcement**: `main.rs` refuses to start if `TRAP_BIND_ADDR` is not a loopback IP (`127.0.0.1` / `::1`), ensuring clearnet interfaces never expose the service even if host firewall rules were misconfigured.
- **Real `sequoia-openpgp` Encryption & Signing**:
  - `POST /enroll` parses the client's ASCII-armored OpenPGP certificate (`Cert::from_bytes`), strips any secret key packets, and validates live transport/storage encryption capability under `StandardPolicy`.
  - Every round (`1`, `2`, `3`) generates a distinct 6-digit HMAC-SHA256 TOTP code (`time_step = unix_ts / 90`, accepting `[current_step, current_step - 1]` for Tor circuit latency resilience while expiring strictly after 2 steps / 180s), signs it with the server's Curve25519 OpenPGP authority key, and encrypts it to the client's certificate.
  - Any wrong guess, expired code, or replay immediately burns the session (`HTTP 400`, `data-stage="failed"`).
  - Completing Round 3 marks the session claimed and unlocks both `#prize-banner` (loaded at runtime from `/etc/trap-pgp-totp/prize.txt`, never stored in git) and `#pgp-prize` (an OpenPGP-signed & encrypted Prize Certificate bound to the winner's key fingerprint).

## Reproducible Deployment

1. Install `tor` and configure `/etc/tor/torrc` using `deploy/torrc.snippet`.
2. Create system user `trap-pgp-totp`, `/var/lib/trap-pgp-totp` (`0700`), and `/etc/trap-pgp-totp/prize.txt` (`0640`, `root:trap-pgp-totp`).
3. Build release binary (`cargo build --release`) and install to `/usr/local/bin/trap-pgp-totp`.
4. Install `deploy/trap-pgp-totp.service` to `/etc/systemd/system/trap-pgp-totp.service` and run `systemctl enable --now trap-pgp-totp.service`.
