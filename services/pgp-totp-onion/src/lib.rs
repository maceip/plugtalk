//! Zero-JavaScript 3-round OpenPGP TOTP hidden service engine.
//!
//! Cryptographic & Security Guarantees:
//! - Every HTTP response enforces `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'`
//!   and `Cache-Control: no-store`, containing zero `<script>` tags and zero inline JS attributes.
//! - Each round (`1`, `2`, `3`) computes a genuine time-stepped 6-digit HMAC-SHA256 TOTP code
//!   (`time_step = unix_ts / 90`, accepting `[current_step, current_step - 1]` so Tor circuit
//!   latency does not flake valid submissions while expiring strictly after the TOTP window).
//! - Every challenge and the final Prize Certificate are signed by the server's OpenPGP key and
//!   encrypted to the client's submitted OpenPGP certificate via `sequoia-openpgp`.
//! - Sessions enforce strict sequential progression (`1 -> 2 -> 3 -> won`), single-attempt
//!   verification per round (any wrong/expired code burns the session), and zero replay after claim.

use std::{
    collections::HashMap,
    fs,
    io::Write,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{SystemTime, UNIX_EPOCH},
};

use anyhow::{anyhow, bail, Context, Result};
use axum::{
    extract::{DefaultBodyLimit, Form, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Router,
};
use chrono::{TimeZone, Utc};
use hmac::{Hmac, Mac};
use rand::{rngs::OsRng, RngCore};
use sequoia_openpgp::{
    armor,
    cert::{Cert, CertBuilder, CipherSuite},
    parse::Parse,
    policy::StandardPolicy,
    serialize::{
        stream::{Armorer, Encryptor2, LiteralWriter, Message, Signer},
        Marshal,
    },
    types::DataFormat,
};
use serde::Deserialize;
use sha2::Sha256;
use subtle::ConstantTimeEq;

/// Duration of one TOTP step in seconds.
pub const TOTP_STEP_SECS: u64 = 90;

/// Maximum wall-clock lifetime of a single round challenge (`2 * TOTP_STEP_SECS`).
pub const MAX_ROUND_AGE_SECS: u64 = TOTP_STEP_SECS * 2;

/// Maximum retained sessions in memory before oldest entries are evicted.
const MAX_ACTIVE_SESSIONS: usize = 4096;

/// Session TTL before automatic garbage collection (15 minutes).
const SESSION_TTL_SECS: u64 = 900;

/// Exact strict Content-Security-Policy header sent on every response.
pub const STRICT_CSP: &str = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'";

type HmacSha256 = Hmac<Sha256>;

/// Clock abstraction allowing deterministic unit testing of TOTP window expiry.
pub trait Clock: Send + Sync + 'static {
    fn now_unix(&self) -> u64;
}

/// Production OS wall clock.
#[derive(Clone, Copy, Debug, Default)]
pub struct SystemClock;

impl Clock for SystemClock {
    fn now_unix(&self) -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs()
    }
}

/// Configuration for [`TotpServiceState`].
#[derive(Clone, Debug)]
pub struct ServiceConfig {
    /// Secret prize text loaded from `/etc/trap-pgp-totp/prize.txt` at runtime.
    pub prize_text: String,
    /// Optional state directory where the server's signing TSK (`server-tsk.pgp`) is persisted.
    pub state_dir: Option<PathBuf>,
}

#[derive(Clone)]
struct SessionRecord {
    cert_bytes: Vec<u8>,
    fingerprint_hex: String,
    current_round: u8,
    claimed: bool,
    failed: bool,
    round_salts: [[u8; 32]; 3],
    round_issued_unix: u64,
    round_issued_step: u64,
    issued_codes: Vec<String>,
    created_unix: u64,
}

/// Shared application state for the OpenPGP TOTP service.
pub struct TotpServiceState {
    server_cert: Cert,
    server_public_armored: String,
    server_fingerprint_hex: String,
    master_totp_key: [u8; 32],
    prize_text: String,
    sessions: Mutex<HashMap<String, SessionRecord>>,
    clock: Arc<dyn Clock>,
}

impl TotpServiceState {
    /// Initialize state with the production [`SystemClock`].
    pub fn new(config: ServiceConfig) -> Result<Arc<Self>> {
        Self::with_clock(config, Arc::new(SystemClock))
    }

    /// Initialize state with a custom [`Clock`] (used by tests to advance time).
    pub fn with_clock(config: ServiceConfig, clock: Arc<dyn Clock>) -> Result<Arc<Self>> {
        let prize_trimmed = config.prize_text.trim().to_string();
        if prize_trimmed.is_empty() {
            bail!("prize text must not be empty");
        }

        let server_cert = load_or_generate_server_cert(config.state_dir.as_deref())?;
        let server_public_armored = armor_public_cert(&server_cert)?;
        let server_fingerprint_hex = server_cert.fingerprint().to_hex();

        let mut master_totp_key = [0u8; 32];
        OsRng.fill_bytes(&mut master_totp_key);

        Ok(Arc::new(Self {
            server_cert,
            server_public_armored,
            server_fingerprint_hex,
            master_totp_key,
            prize_text: prize_trimmed,
            sessions: Mutex::new(HashMap::new()),
            clock,
        }))
    }

    /// Server signing certificate primary fingerprint (40 uppercase hex chars).
    pub fn server_fingerprint_hex(&self) -> &str {
        &self.server_fingerprint_hex
    }
}

/// Build the Axum HTTP router enforcing body size limits and zero-JS routes.
pub fn build_router(state: Arc<TotpServiceState>) -> Router {
    Router::new()
        .route("/", get(handle_index))
        .route("/enroll", post(handle_enroll))
        .route("/verify", post(handle_verify))
        .route("/server-key.asc", get(handle_server_key))
        .route("/healthz", get(handle_healthz))
        .layer(DefaultBodyLimit::max(64 * 1024))
        .with_state(state)
}

/// Compute the 90-second TOTP step index for a Unix timestamp.
pub fn totp_step(unix_secs: u64) -> u64 {
    unix_secs / TOTP_STEP_SECS
}

/// Compute a 6-digit RFC-6238-style HMAC-SHA256 TOTP code bound to `(session_id, round, salt, step)`.
pub fn compute_totp_code(
    master_key: &[u8; 32],
    session_id: &str,
    round: u8,
    round_salt: &[u8; 32],
    step: u64,
) -> String {
    let mut mac = HmacSha256::new_from_slice(master_key).expect("HMAC-SHA256 accepts 256-bit keys");
    mac.update(b"TRAP-OPENPGP-TOTP-V1\0");
    mac.update(session_id.as_bytes());
    mac.update(&[0u8, round]);
    mac.update(round_salt);
    mac.update(&step.to_be_bytes());
    let digest = mac.finalize().into_bytes();

    let offset = (digest[31] & 0x0f) as usize;
    let binary = u32::from_be_bytes([
        digest[offset] & 0x7f,
        digest[offset + 1],
        digest[offset + 2],
        digest[offset + 3],
    ]);
    format!("{:06}", binary % 1_000_000)
}

#[derive(Deserialize)]
struct EnrollForm {
    pubkey: String,
}

#[derive(Deserialize)]
struct VerifyForm {
    session: String,
    code: String,
}

async fn handle_index() -> Response {
    html_response(StatusCode::OK, &render_enroll_page())
}

async fn handle_server_key(State(state): State<Arc<TotpServiceState>>) -> Response {
    let mut headers = strict_headers("application/pgp-keys; charset=utf-8");
    headers.insert(
        header::CONTENT_DISPOSITION,
        HeaderValue::from_static("inline; filename=\"trap-totp-authority.asc\""),
    );
    (StatusCode::OK, headers, state.server_public_armored.clone()).into_response()
}

async fn handle_healthz() -> Response {
    let headers = strict_headers("text/plain; charset=utf-8");
    (StatusCode::OK, headers, "ok\n").into_response()
}

async fn handle_enroll(
    State(state): State<Arc<TotpServiceState>>,
    Form(form): Form<EnrollForm>,
) -> Response {
    let trimmed = form.pubkey.trim();
    if trimmed.is_empty() {
        return html_response(
            StatusCode::BAD_REQUEST,
            &render_failed_page(0, "Missing OpenPGP public key."),
        );
    }

    let client_cert = match parse_and_validate_encryption_cert(trimmed) {
        Ok(cert) => cert,
        Err(err) => {
            return html_response(
                StatusCode::BAD_REQUEST,
                &render_failed_page(0, &format!("Invalid OpenPGP public key: {err}")),
            );
        }
    };

    let mut cert_bytes = Vec::new();
    if client_cert.serialize(&mut cert_bytes).is_err() {
        return html_response(
            StatusCode::BAD_REQUEST,
            &render_failed_page(0, "Could not serialize OpenPGP certificate."),
        );
    }
    let fingerprint_hex = client_cert.fingerprint().to_hex();

    let mut raw_session = [0u8; 16];
    OsRng.fill_bytes(&mut raw_session);
    let session_id = hex::encode(raw_session);

    let mut round_salts = [[0u8; 32]; 3];
    for salt in &mut round_salts {
        OsRng.fill_bytes(salt);
    }

    let now_unix = state.clock.now_unix();
    let issue_step = totp_step(now_unix);
    let code = compute_totp_code(
        &state.master_totp_key,
        &session_id,
        1,
        &round_salts[0],
        issue_step,
    );

    let plaintext = format_challenge_plaintext(1, &code);
    let armored_challenge =
        match sign_and_encrypt_armored(&state.server_cert, &client_cert, &plaintext) {
            Ok(ct) => ct,
            Err(err) => {
                return html_response(
                    StatusCode::BAD_REQUEST,
                    &render_failed_page(1, &format!("OpenPGP encryption failed: {err}")),
                );
            }
        };

    let record = SessionRecord {
        cert_bytes,
        fingerprint_hex,
        current_round: 1,
        claimed: false,
        failed: false,
        round_salts,
        round_issued_unix: now_unix,
        round_issued_step: issue_step,
        issued_codes: vec![code],
        created_unix: now_unix,
    };

    {
        let mut sessions = state.sessions.lock().expect("session mutex poisoned");
        evict_stale_sessions(&mut sessions, now_unix);
        sessions.insert(session_id.clone(), record);
    }

    html_response(
        StatusCode::OK,
        &render_challenge_page(1, &session_id, &armored_challenge),
    )
}

async fn handle_verify(
    State(state): State<Arc<TotpServiceState>>,
    Form(form): Form<VerifyForm>,
) -> Response {
    let session_id = form.session.trim();
    let submitted_code = form.code.trim();

    if session_id.len() != 32 || !session_id.bytes().all(|b| b.is_ascii_hexdigit()) {
        return html_response(
            StatusCode::BAD_REQUEST,
            &render_failed_page(0, "Invalid or unknown TOTP session."),
        );
    }

    let now_unix = state.clock.now_unix();
    let now_step = totp_step(now_unix);

    enum VerifyOutcome {
        Failed {
            round: u8,
            reason: &'static str,
        },
        NextRound {
            next_round: u8,
            client_cert_bytes: Vec<u8>,
            code: String,
        },
        Won {
            client_cert_bytes: Vec<u8>,
            fingerprint_hex: String,
        },
    }

    let outcome = {
        let mut sessions = state.sessions.lock().expect("session mutex poisoned");
        evict_stale_sessions(&mut sessions, now_unix);

        let Some(record) = sessions.get_mut(session_id) else {
            return html_response(
                StatusCode::BAD_REQUEST,
                &render_failed_page(0, "Session expired or unknown."),
            );
        };

        let round = record.current_round;
        if record.claimed || record.failed || !(1..=3).contains(&round) {
            record.failed = true;
            sessions.remove(session_id);
            VerifyOutcome::Failed {
                round,
                reason: "Session already finalized or replayed.",
            }
        } else if submitted_code.len() != 6 || !submitted_code.bytes().all(|b| b.is_ascii_digit()) {
            record.failed = true;
            sessions.remove(session_id);
            VerifyOutcome::Failed {
                round,
                reason: "TOTP code must be 6 digits.",
            }
        } else if now_unix < record.round_issued_unix
            || now_unix.saturating_sub(record.round_issued_unix) > MAX_ROUND_AGE_SECS
            || now_step > record.round_issued_step.saturating_add(1)
        {
            record.failed = true;
            sessions.remove(session_id);
            VerifyOutcome::Failed {
                round,
                reason: "TOTP window expired.",
            }
        } else {
            let salt = record.round_salts[(round - 1) as usize];
            let expected_issued = compute_totp_code(
                &state.master_totp_key,
                session_id,
                round,
                &salt,
                record.round_issued_step,
            );
            let expected_curr =
                compute_totp_code(&state.master_totp_key, session_id, round, &salt, now_step);
            let expected_prev = compute_totp_code(
                &state.master_totp_key,
                session_id,
                round,
                &salt,
                now_step.saturating_sub(1),
            );

            let matches_issued =
                bool::from(submitted_code.as_bytes().ct_eq(expected_issued.as_bytes()));
            let matches_curr =
                bool::from(submitted_code.as_bytes().ct_eq(expected_curr.as_bytes()));
            let matches_prev =
                bool::from(submitted_code.as_bytes().ct_eq(expected_prev.as_bytes()));

            if !(matches_issued && (matches_curr || matches_prev)) {
                record.failed = true;
                sessions.remove(session_id);
                VerifyOutcome::Failed {
                    round,
                    reason: "Incorrect TOTP code.",
                }
            } else if round < 3 {
                let next_round = round + 1;
                let salt_idx = (next_round - 1) as usize;
                let mut next_code = compute_totp_code(
                    &state.master_totp_key,
                    session_id,
                    next_round,
                    &record.round_salts[salt_idx],
                    now_step,
                );
                // Guarantee distinct TOTP codes across Rounds 1, 2, and 3 even under a 1-in-10^6 collision.
                while record.issued_codes.iter().any(|prev| prev == &next_code) {
                    OsRng.fill_bytes(&mut record.round_salts[salt_idx]);
                    next_code = compute_totp_code(
                        &state.master_totp_key,
                        session_id,
                        next_round,
                        &record.round_salts[salt_idx],
                        now_step,
                    );
                }
                record.current_round = next_round;
                record.round_issued_unix = now_unix;
                record.round_issued_step = now_step;
                record.issued_codes.push(next_code.clone());

                VerifyOutcome::NextRound {
                    next_round,
                    client_cert_bytes: record.cert_bytes.clone(),
                    code: next_code,
                }
            } else {
                record.claimed = true;
                let cert_bytes = record.cert_bytes.clone();
                let fp = record.fingerprint_hex.clone();
                // Retain claimed marker until TTL eviction so replays fail deterministically.
                VerifyOutcome::Won {
                    client_cert_bytes: cert_bytes,
                    fingerprint_hex: fp,
                }
            }
        }
    };

    match outcome {
        VerifyOutcome::Failed { round, reason } => {
            html_response(StatusCode::BAD_REQUEST, &render_failed_page(round, reason))
        }
        VerifyOutcome::NextRound {
            next_round,
            client_cert_bytes,
            code,
        } => {
            let Ok(client_cert) = Cert::from_bytes(&client_cert_bytes) else {
                return html_response(
                    StatusCode::BAD_REQUEST,
                    &render_failed_page(next_round, "Stored certificate corrupted."),
                );
            };
            let plaintext = format_challenge_plaintext(next_round, &code);
            let Ok(armored_challenge) =
                sign_and_encrypt_armored(&state.server_cert, &client_cert, &plaintext)
            else {
                return html_response(
                    StatusCode::BAD_REQUEST,
                    &render_failed_page(next_round, "Failed to encrypt next round challenge."),
                );
            };
            html_response(
                StatusCode::OK,
                &render_challenge_page(next_round, session_id, &armored_challenge),
            )
        }
        VerifyOutcome::Won {
            client_cert_bytes,
            fingerprint_hex,
        } => {
            let Ok(client_cert) = Cert::from_bytes(&client_cert_bytes) else {
                return html_response(
                    StatusCode::BAD_REQUEST,
                    &render_failed_page(3, "Stored certificate corrupted."),
                );
            };
            let issued_iso = Utc
                .timestamp_opt(now_unix as i64, 0)
                .single()
                .map(|dt| dt.to_rfc3339())
                .unwrap_or_else(|| format!("{now_unix}"));
            let cert_plaintext = format!(
                "TRAP OPENPGP TOTP PRIZE CERTIFICATE\n\
                 PRIZE: {}\n\
                 WINNER FINGERPRINT: {}\n\
                 ISSUED AT: {}\n\
                 ROUNDS VERIFIED: 3 OF 3\n\
                 AUTHORITY FINGERPRINT: {}\n",
                state.prize_text, fingerprint_hex, issued_iso, state.server_fingerprint_hex,
            );
            let Ok(armored_prize) =
                sign_and_encrypt_armored(&state.server_cert, &client_cert, &cert_plaintext)
            else {
                return html_response(
                    StatusCode::BAD_REQUEST,
                    &render_failed_page(3, "Failed to encrypt prize certificate."),
                );
            };
            html_response(
                StatusCode::OK,
                &render_won_page(&state.prize_text, &armored_prize),
            )
        }
    }
}

fn format_challenge_plaintext(round: u8, code: &str) -> String {
    format!("TRAP OPENPGP TOTP — ROUND {round} OF 3\nTOTP CODE: {code}\nEXPIRES IN: 90s\n")
}

fn evict_stale_sessions(sessions: &mut HashMap<String, SessionRecord>, now_unix: u64) {
    sessions.retain(|_, rec| now_unix.saturating_sub(rec.created_unix) <= SESSION_TTL_SECS);
    if sessions.len() >= MAX_ACTIVE_SESSIONS {
        if let Some(oldest_key) = sessions
            .iter()
            .min_by_key(|(_, rec)| rec.created_unix)
            .map(|(k, _)| k.clone())
        {
            sessions.remove(&oldest_key);
        }
    }
}

fn load_or_generate_server_cert(state_dir: Option<&Path>) -> Result<Cert> {
    if let Some(dir) = state_dir {
        fs::create_dir_all(dir)
            .with_context(|| format!("failed to create state dir {}", dir.display()))?;
        let tsk_path = dir.join("server-tsk.pgp");
        if tsk_path.exists() {
            let bytes = fs::read(&tsk_path)
                .with_context(|| format!("failed to read {}", tsk_path.display()))?;
            let cert =
                Cert::from_bytes(&bytes).context("failed to parse persisted server OpenPGP TSK")?;
            if cert.is_tsk() {
                return Ok(cert);
            }
        }
        let cert = generate_server_cert()?;
        let mut serialized = Vec::new();
        cert.as_tsk()
            .serialize(&mut serialized)
            .context("failed to serialize server OpenPGP TSK")?;
        write_private_file(&tsk_path, &serialized)?;
        return Ok(cert);
    }
    generate_server_cert()
}

fn write_private_file(path: &Path, data: &[u8]) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        let mut file = fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .mode(0o600)
            .open(path)
            .with_context(|| format!("failed to open {} with 0600", path.display()))?;
        file.write_all(data)?;
        file.sync_all()?;
        Ok(())
    }
    #[cfg(not(unix))]
    {
        fs::write(path, data)?;
        Ok(())
    }
}

fn generate_server_cert() -> Result<Cert> {
    let (cert, _revocation) = CertBuilder::new()
        .set_cipher_suite(CipherSuite::Cv25519)
        .add_userid("Trap OpenPGP TOTP Authority <totp@trap.onion>")
        .add_signing_subkey()
        .generate()
        .map_err(|e| anyhow!("failed to generate server OpenPGP key: {e}"))?;
    Ok(cert)
}

fn armor_public_cert(cert: &Cert) -> Result<String> {
    let mut out = Vec::new();
    {
        let mut writer =
            armor::Writer::new(&mut out, armor::Kind::PublicKey).context("armor writer init")?;
        cert.serialize(&mut writer)
            .context("serialize public cert")?;
        writer.finalize().context("finalize public cert armor")?;
    }
    String::from_utf8(out).context("armored public cert UTF-8")
}

/// Parse a user-submitted OpenPGP certificate, strip any secret key packets, and
/// verify at least one live, policy-valid encryption-capable key exists.
pub fn parse_and_validate_encryption_cert(armored_or_bytes: &str) -> Result<Cert> {
    let cert = Cert::from_bytes(armored_or_bytes.as_bytes())
        .map_err(|e| anyhow!("cannot parse OpenPGP certificate: {e}"))?
        .strip_secret_key_material();
    let policy = StandardPolicy::new();
    let has_enc = cert
        .keys()
        .with_policy(&policy, None)
        .supported()
        .alive()
        .revoked(false)
        .for_transport_encryption()
        .next()
        .is_some()
        || cert
            .keys()
            .with_policy(&policy, None)
            .supported()
            .alive()
            .revoked(false)
            .for_storage_encryption()
            .next()
            .is_some();
    if !has_enc {
        bail!("certificate has no live encryption-capable subkey");
    }
    Ok(cert)
}

/// Sign `plaintext` with `server_cert` and encrypt to `recipient_cert` as ASCII-armored OpenPGP.
pub fn sign_and_encrypt_armored(
    server_cert: &Cert,
    recipient_cert: &Cert,
    plaintext: &str,
) -> Result<String> {
    let policy = StandardPolicy::new();

    let mut recipient_keys: Vec<_> = recipient_cert
        .keys()
        .with_policy(&policy, None)
        .supported()
        .alive()
        .revoked(false)
        .for_transport_encryption()
        .map(|ka| ka.key())
        .collect();
    if recipient_keys.is_empty() {
        recipient_keys = recipient_cert
            .keys()
            .with_policy(&policy, None)
            .supported()
            .alive()
            .revoked(false)
            .for_storage_encryption()
            .map(|ka| ka.key())
            .collect();
    }
    if recipient_keys.is_empty() {
        bail!("recipient certificate has no usable encryption subkey");
    }

    let signing_keypair = server_cert
        .keys()
        .secret()
        .with_policy(&policy, None)
        .supported()
        .alive()
        .revoked(false)
        .for_signing()
        .next()
        .ok_or_else(|| anyhow!("server certificate has no usable signing key"))?
        .key()
        .clone()
        .into_keypair()
        .map_err(|e| anyhow!("cannot unlock server signing keypair: {e}"))?;

    let mut sink = Vec::new();
    {
        let message = Message::new(&mut sink);
        let message = Armorer::new(message)
            .kind(armor::Kind::Message)
            .build()
            .map_err(|e| anyhow!("armorer build failed: {e}"))?;
        let message = Encryptor2::for_recipients(message, recipient_keys)
            .build()
            .map_err(|e| anyhow!("encryptor build failed: {e}"))?;
        let message = Signer::new(message, signing_keypair)
            .build()
            .map_err(|e| anyhow!("signer build failed: {e}"))?;
        let mut literal = LiteralWriter::new(message)
            .format(DataFormat::Text)
            .build()
            .map_err(|e| anyhow!("literal writer build failed: {e}"))?;
        literal
            .write_all(plaintext.as_bytes())
            .map_err(|e| anyhow!("literal write failed: {e}"))?;
        literal
            .finalize()
            .map_err(|e| anyhow!("message finalize failed: {e}"))?;
    }

    String::from_utf8(sink).context("armored ciphertext UTF-8")
}

fn strict_headers(content_type: &'static str) -> HeaderMap {
    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, HeaderValue::from_static(content_type));
    headers.insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_static(STRICT_CSP),
    );
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    headers.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    headers.insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("DENY"));
    headers.insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
    headers
}

fn html_response(status: StatusCode, body: &str) -> Response {
    (
        status,
        strict_headers("text/html; charset=utf-8"),
        body.to_owned(),
    )
        .into_response()
}

fn escape_html(input: &str) -> String {
    let mut out = String::with_capacity(input.len() + 16);
    for ch in input.chars() {
        match ch {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#x27;"),
            _ => out.push(ch),
        }
    }
    out
}

const COMPACT_CSS: &str = r#"
*{box-sizing:border-box;margin:0;padding:0}
body{background:#0b0f14;color:#e6edf3;font:13px/1.35 ui-monospace,SFMono-Regular,Menlo,monospace;padding:10px;max-width:440px;margin:0 auto}
header{display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #1f2937;padding-bottom:6px;margin-bottom:8px}
h1{font-size:13px;font-weight:700;color:#2ec4b0;letter-spacing:.03em}
#totp-status{font-size:12px;font-weight:700;padding:4px 8px;border-radius:4px;background:#13222e;color:#7ee787;margin-bottom:8px;border:1px solid #1f3a4d}
#totp-status[data-stage="failed"]{background:#2d1216;color:#ff7b72;border-color:#6e222b}
#totp-status[data-stage="won"]{background:#0d2b24;color:#2ec4b0;border-color:#2ec4b0}
textarea{width:100%;background:#111820;color:#e6edf3;border:1px solid #30363d;border-radius:6px;padding:8px;font:11px/1.3 ui-monospace,monospace;resize:none}
pre{background:#111820;color:#c9d1d9;border:1px solid #30363d;border-radius:6px;padding:7px;font:9.5px/1.22 ui-monospace,monospace;white-space:pre-wrap;word-break:break-all;margin-bottom:8px}
.row{display:flex;gap:8px;align-items:center;margin-top:8px}
input[type="text"]{flex:1;min-width:0;height:38px;background:#111820;color:#e6edf3;border:1px solid #30363d;border-radius:6px;padding:0 10px;font:15px/1 ui-monospace,monospace}
button,a.btn{display:inline-flex;align-items:center;justify-content:center;height:38px;padding:0 14px;background:#2ec4b0;color:#062a26;font:700 13px/1 ui-monospace,monospace;border:0;border-radius:6px;text-decoration:none;cursor:pointer;white-space:nowrap}
#enroll-submit{width:100%;margin-top:12px}
#prize-banner{background:#0d2b24;border:2px solid #2ec4b0;color:#f0f6fc;border-radius:6px;padding:8px 10px;font:700 12px/1.35 ui-monospace,monospace;margin-bottom:8px;word-break:break-word}
p.hint{font-size:11px;color:#8b949e;margin-bottom:6px}
"#;

fn page_shell(title: &str, inner_html: &str) -> String {
    format!(
        "<!DOCTYPE html>\n\
         <html lang=\"en\">\n\
         <head>\n\
         <meta charset=\"utf-8\">\n\
         <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n\
         <title>{}</title>\n\
         <style>{}</style>\n\
         </head>\n\
         <body>\n\
         <header><h1>TRAP OPENPGP TOTP</h1></header>\n\
         {}\n\
         </body>\n\
         </html>\n",
        escape_html(title),
        COMPACT_CSS.trim(),
        inner_html
    )
}

fn render_enroll_page() -> String {
    let inner = concat!(
        "<div id=\"totp-status\" data-stage=\"enroll\" data-round=\"0\">ENROLL OPENPGP KEY</div>\n",
        "<p class=\"hint\">Submit your OpenPGP public key to decrypt 3 sequential 90s TOTP rounds.</p>\n",
        "<form id=\"enroll-form\" method=\"POST\" action=\"/enroll\">\n",
        "<textarea id=\"pubkey\" name=\"pubkey\" rows=\"6\" placeholder=\"Paste PGP public key\"></textarea>\n",
        "<button id=\"enroll-submit\" type=\"submit\">Start Round 1</button>\n",
        "</form>"
    );
    page_shell("Trap OpenPGP TOTP — Enroll", inner)
}

fn render_challenge_page(round: u8, session_id: &str, armored_pgp: &str) -> String {
    let inner = format!(
        "<div id=\"totp-status\" data-stage=\"challenge\" data-round=\"{round}\">ROUND {round} OF 3</div>\n\
         <pre id=\"pgp-challenge\">{armored}</pre>\n\
         <form id=\"verify-form\" method=\"POST\" action=\"/verify\">\n\
         <input type=\"hidden\" name=\"session\" value=\"{session}\">\n\
         <div class=\"row\">\n\
         <input id=\"totp-code\" name=\"code\" type=\"text\" inputmode=\"numeric\" autocomplete=\"off\" placeholder=\"6-digit TOTP code\">\n\
         <button id=\"verify-submit\" type=\"submit\">Verify Round {round}</button>\n\
         </div>\n\
         </form>",
        round = round,
        armored = escape_html(armored_pgp.trim()),
        session = escape_html(session_id),
    );
    page_shell(&format!("Trap OpenPGP TOTP — Round {round}"), &inner)
}

fn render_won_page(prize: &str, armored_prize_cert: &str) -> String {
    let inner = format!(
        "<div id=\"totp-status\" data-stage=\"won\" data-round=\"3\" data-won=\"true\">ROUND 3 OF 3 VERIFIED — PRIZE UNLOCKED</div>\n\
         <div id=\"prize-banner\">{prize}</div>\n\
         <pre id=\"pgp-prize\">{armored}</pre>",
        prize = escape_html(prize),
        armored = escape_html(armored_prize_cert.trim()),
    );
    page_shell("Trap OpenPGP TOTP — Prize Unlocked", &inner)
}

fn render_failed_page(round: u8, reason: &str) -> String {
    let inner = format!(
        "<div id=\"totp-status\" data-stage=\"failed\" data-round=\"{round}\">VERIFICATION FAILED</div>\n\
         <p class=\"hint\">{reason}</p>\n\
         <a id=\"restart-link\" class=\"btn\" href=\"/\">Restart Enrollment</a>",
        round = round,
        reason = escape_html(reason),
    );
    page_shell("Trap OpenPGP TOTP — Verification Failed", &inner)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;
    use std::sync::atomic::{AtomicU64, Ordering};

    use axum::body::{to_bytes, Body};
    use axum::http::Request;
    use sequoia_openpgp::crypto::SessionKey;
    use sequoia_openpgp::parse::stream::{
        DecryptionHelper, DecryptorBuilder, MessageStructure, VerificationHelper,
    };
    use sequoia_openpgp::types::SymmetricAlgorithm;
    use sequoia_openpgp::Cert;
    use tower::ServiceExt;

    struct MockClock {
        now: AtomicU64,
    }

    impl MockClock {
        fn new(initial: u64) -> Arc<Self> {
            Arc::new(Self {
                now: AtomicU64::new(initial),
            })
        }

        fn advance(&self, secs: u64) {
            self.now.fetch_add(secs, Ordering::SeqCst);
        }
    }

    impl Clock for MockClock {
        fn now_unix(&self) -> u64 {
            self.now.load(Ordering::SeqCst)
        }
    }

    struct ClientDecryptHelper<'a> {
        client_tsk: &'a Cert,
        server_pub: &'a Cert,
        verified_sig: bool,
    }

    impl VerificationHelper for ClientDecryptHelper<'_> {
        fn get_certs(
            &mut self,
            _ids: &[sequoia_openpgp::KeyHandle],
        ) -> sequoia_openpgp::Result<Vec<Cert>> {
            Ok(vec![self.server_pub.clone()])
        }

        fn check(&mut self, structure: MessageStructure) -> sequoia_openpgp::Result<()> {
            for layer in structure {
                if let sequoia_openpgp::parse::stream::MessageLayer::SignatureGroup { results } =
                    layer
                {
                    if results.iter().any(|r| r.is_ok()) {
                        self.verified_sig = true;
                    }
                }
            }
            Ok(())
        }
    }

    impl DecryptionHelper for ClientDecryptHelper<'_> {
        fn decrypt<D>(
            &mut self,
            pkesks: &[sequoia_openpgp::packet::PKESK],
            _skesks: &[sequoia_openpgp::packet::SKESK],
            sym_algo: Option<SymmetricAlgorithm>,
            mut decrypt: D,
        ) -> sequoia_openpgp::Result<Option<sequoia_openpgp::Fingerprint>>
        where
            D: FnMut(SymmetricAlgorithm, &SessionKey) -> bool,
        {
            let policy = StandardPolicy::new();
            for ka in self
                .client_tsk
                .keys()
                .secret()
                .with_policy(&policy, None)
                .for_transport_encryption()
            {
                let mut keypair = ka.key().clone().into_keypair()?;
                for pkesk in pkesks {
                    if let Some((algo, sk)) = pkesk.decrypt(&mut keypair, sym_algo) {
                        if decrypt(algo, &sk) {
                            return Ok(Some(self.client_tsk.fingerprint()));
                        }
                    }
                }
            }
            Err(anyhow!("failed to decrypt session key"))
        }
    }

    fn generate_client_key() -> (Cert, String) {
        let (cert, _) = CertBuilder::new()
            .set_cipher_suite(CipherSuite::Cv25519)
            .add_userid("Test Solver <solver@trap.onion>")
            .add_signing_subkey()
            .add_transport_encryption_subkey()
            .generate()
            .expect("client keygen");
        let armored = armor_public_cert(&cert).expect("armor client pubkey");
        (cert, armored)
    }

    fn decrypt_and_verify(client_tsk: &Cert, server_pub: &Cert, armored: &str) -> String {
        let policy = StandardPolicy::new();
        let helper = ClientDecryptHelper {
            client_tsk,
            server_pub,
            verified_sig: false,
        };
        let mut decryptor = DecryptorBuilder::from_bytes(armored.as_bytes())
            .expect("parse pgp message")
            .with_policy(&policy, None, helper)
            .expect("decrypt pgp message");
        let mut plain = Vec::new();
        decryptor.read_to_end(&mut plain).expect("read plaintext");
        let helper = decryptor.into_helper();
        assert!(
            helper.verified_sig,
            "expected valid server OpenPGP signature inside encrypted message"
        );
        String::from_utf8(plain).expect("utf8 plaintext")
    }

    fn extract_between<'a>(haystack: &'a str, start: &str, end: &str) -> &'a str {
        let s = haystack
            .find(start)
            .unwrap_or_else(|| panic!("missing `{start}` in:\n{haystack}"))
            + start.len();
        let e = haystack[s..]
            .find(end)
            .unwrap_or_else(|| panic!("missing `{end}` in:\n{haystack}"))
            + s;
        &haystack[s..e]
    }

    fn extract_code_from_plaintext(plaintext: &str) -> String {
        for line in plaintext.lines() {
            if let Some(rest) = line.strip_prefix("TOTP CODE: ") {
                let code = rest.trim();
                assert_eq!(code.len(), 6);
                return code.to_string();
            }
        }
        panic!("no TOTP CODE found in {plaintext}");
    }

    #[tokio::test]
    async fn full_three_round_totp_wins_prize_and_blocks_replay() {
        let clock = MockClock::new(1_700_000_085); // 5s before 90s step boundary!
        let state = TotpServiceState::with_clock(
            ServiceConfig {
                prize_text: "TEST-ONLY-EPHEMERAL-UNIT-PRIZE-9911".to_string(),
                state_dir: None,
            },
            clock.clone(),
        )
        .expect("init state");
        let server_pub = Cert::from_bytes(state.server_public_armored.as_bytes()).unwrap();
        let app = build_router(state);

        // 1. GET /
        let res = app
            .clone()
            .oneshot(Request::builder().uri("/").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        assert_eq!(
            res.headers()
                .get(header::CONTENT_SECURITY_POLICY)
                .unwrap()
                .to_str()
                .unwrap(),
            STRICT_CSP
        );
        let index_html =
            String::from_utf8(to_bytes(res.into_body(), 65536).await.unwrap().to_vec()).unwrap();
        assert!(index_html.contains("placeholder=\"Paste PGP public key\""));
        assert!(!index_html.to_ascii_lowercase().contains("<script"));

        // 2. POST /enroll -> Round 1
        let (client_tsk, client_pub_armored) = generate_client_key();
        let enroll_body =
            serde_urlencoded::to_string([("pubkey", client_pub_armored.as_str())]).unwrap();
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/enroll")
                    .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .body(Body::from(enroll_body))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let r1_html =
            String::from_utf8(to_bytes(res.into_body(), 65536).await.unwrap().to_vec()).unwrap();
        assert!(r1_html.contains("data-stage=\"challenge\" data-round=\"1\""));
        let session_id = extract_between(&r1_html, "name=\"session\" value=\"", "\"").to_string();
        let pgp_r1 = extract_between(&r1_html, "<pre id=\"pgp-challenge\">", "</pre>");
        let plain_r1 = decrypt_and_verify(&client_tsk, &server_pub, pgp_r1);
        assert!(plain_r1.contains("TRAP OPENPGP TOTP — ROUND 1 OF 3"));
        let code_r1 = extract_code_from_plaintext(&plain_r1);

        // Advance 20s across the 90s TOTP step boundary (`current_step - 1` grace check!)
        clock.advance(20);

        // 3. POST /verify Round 1 -> Round 2
        let v1_body = serde_urlencoded::to_string([
            ("session", session_id.as_str()),
            ("code", code_r1.as_str()),
        ])
        .unwrap();
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/verify")
                    .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .body(Body::from(v1_body))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let r2_html =
            String::from_utf8(to_bytes(res.into_body(), 65536).await.unwrap().to_vec()).unwrap();
        assert!(r2_html.contains("data-stage=\"challenge\" data-round=\"2\""));
        let pgp_r2 = extract_between(&r2_html, "<pre id=\"pgp-challenge\">", "</pre>");
        let plain_r2 = decrypt_and_verify(&client_tsk, &server_pub, pgp_r2);
        assert!(plain_r2.contains("TRAP OPENPGP TOTP — ROUND 2 OF 3"));
        let code_r2 = extract_code_from_plaintext(&plain_r2);
        assert_ne!(
            code_r1, code_r2,
            "Round 2 TOTP code must differ from Round 1"
        );

        // 4. POST /verify Round 2 -> Round 3
        clock.advance(12);
        let v2_body = serde_urlencoded::to_string([
            ("session", session_id.as_str()),
            ("code", code_r2.as_str()),
        ])
        .unwrap();
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/verify")
                    .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .body(Body::from(v2_body))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let r3_html =
            String::from_utf8(to_bytes(res.into_body(), 65536).await.unwrap().to_vec()).unwrap();
        assert!(r3_html.contains("data-stage=\"challenge\" data-round=\"3\""));
        let pgp_r3 = extract_between(&r3_html, "<pre id=\"pgp-challenge\">", "</pre>");
        let plain_r3 = decrypt_and_verify(&client_tsk, &server_pub, pgp_r3);
        assert!(plain_r3.contains("TRAP OPENPGP TOTP — ROUND 3 OF 3"));
        let code_r3 = extract_code_from_plaintext(&plain_r3);
        assert_ne!(code_r3, code_r1);
        assert_ne!(code_r3, code_r2);

        // 5. POST /verify Round 3 -> WINS PRIZE!
        clock.advance(8);
        let v3_body = serde_urlencoded::to_string([
            ("session", session_id.as_str()),
            ("code", code_r3.as_str()),
        ])
        .unwrap();
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/verify")
                    .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .body(Body::from(v3_body.clone()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let won_html =
            String::from_utf8(to_bytes(res.into_body(), 65536).await.unwrap().to_vec()).unwrap();
        assert!(won_html.contains("data-stage=\"won\" data-round=\"3\" data-won=\"true\""));
        let banner = extract_between(&won_html, "<div id=\"prize-banner\">", "</div>");
        assert_eq!(banner, "TEST-ONLY-EPHEMERAL-UNIT-PRIZE-9911");
        let pgp_prize = extract_between(&won_html, "<pre id=\"pgp-prize\">", "</pre>");
        let plain_cert = decrypt_and_verify(&client_tsk, &server_pub, pgp_prize);
        assert!(plain_cert.contains("TRAP OPENPGP TOTP PRIZE CERTIFICATE"));
        assert!(plain_cert.contains("PRIZE: TEST-ONLY-EPHEMERAL-UNIT-PRIZE-9911"));
        assert!(plain_cert.contains(&client_tsk.fingerprint().to_hex()));

        // 6. Replay of winning POST must fail with HTTP 400 + data-stage="failed"
        let replay = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/verify")
                    .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .body(Body::from(v3_body))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(replay.status(), StatusCode::BAD_REQUEST);
        let replay_html =
            String::from_utf8(to_bytes(replay.into_body(), 65536).await.unwrap().to_vec()).unwrap();
        assert!(replay_html.contains("data-stage=\"failed\""));
    }

    #[tokio::test]
    async fn expired_and_wrong_codes_reject_and_burn_session() {
        let clock = MockClock::new(1_700_100_000);
        let state = TotpServiceState::with_clock(
            ServiceConfig {
                prize_text: "TEST-PRIZE-EXPIRY".to_string(),
                state_dir: None,
            },
            clock.clone(),
        )
        .unwrap();
        let server_pub = Cert::from_bytes(state.server_public_armored.as_bytes()).unwrap();
        let app = build_router(state);
        let (client_tsk, client_pub_armored) = generate_client_key();

        // Case A: Wrong code burns session so subsequent right code also fails
        let enroll_body =
            serde_urlencoded::to_string([("pubkey", client_pub_armored.as_str())]).unwrap();
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/enroll")
                    .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .body(Body::from(enroll_body.clone()))
                    .unwrap(),
            )
            .await
            .unwrap();
        let r1_html =
            String::from_utf8(to_bytes(res.into_body(), 65536).await.unwrap().to_vec()).unwrap();
        let session_a = extract_between(&r1_html, "name=\"session\" value=\"", "\"").to_string();
        let plain_a = decrypt_and_verify(
            &client_tsk,
            &server_pub,
            extract_between(&r1_html, "<pre id=\"pgp-challenge\">", "</pre>"),
        );
        let real_code_a = extract_code_from_plaintext(&plain_a);
        let wrong_code = if real_code_a == "000000" {
            "999999"
        } else {
            "000000"
        };

        let bad_res = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/verify")
                    .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .body(Body::from(
                        serde_urlencoded::to_string([
                            ("session", session_a.as_str()),
                            ("code", wrong_code),
                        ])
                        .unwrap(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(bad_res.status(), StatusCode::BAD_REQUEST);

        // Retrying with the real code on the burned session must STILL fail!
        let retry_res = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/verify")
                    .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .body(Body::from(
                        serde_urlencoded::to_string([
                            ("session", session_a.as_str()),
                            ("code", real_code_a.as_str()),
                        ])
                        .unwrap(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(retry_res.status(), StatusCode::BAD_REQUEST);

        // Case B: Expired TOTP window (> 2 steps / > 180s) fails with HTTP 400
        let res_b = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/enroll")
                    .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .body(Body::from(enroll_body))
                    .unwrap(),
            )
            .await
            .unwrap();
        let r1_b_html =
            String::from_utf8(to_bytes(res_b.into_body(), 65536).await.unwrap().to_vec()).unwrap();
        let session_b = extract_between(&r1_b_html, "name=\"session\" value=\"", "\"").to_string();
        let plain_b = decrypt_and_verify(
            &client_tsk,
            &server_pub,
            extract_between(&r1_b_html, "<pre id=\"pgp-challenge\">", "</pre>"),
        );
        let code_b = extract_code_from_plaintext(&plain_b);

        clock.advance(185); // Exceeds 2 * 90s TOTP window!

        let exp_res = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/verify")
                    .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .body(Body::from(
                        serde_urlencoded::to_string([
                            ("session", session_b.as_str()),
                            ("code", code_b.as_str()),
                        ])
                        .unwrap(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(exp_res.status(), StatusCode::BAD_REQUEST);
    }
}
